package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.media.MediaHasher
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** The fsck, prune and snapshot commands. They can run while the service does. */
object Maintenance {
    /** Uploaded but not yet pushed blobs are left alone for this long, so prune can't race a sync. */
    private val BLOB_GRACE = 7.days

    fun fsck(repo: Repository, full: Boolean): Int {
        val problems = mutableListOf<String>()
        val db = repo.db
        db.read {
            db.query("PRAGMA integrity_check") { it.getString(1) }.filter { it != "ok" }.forEach { problems += "database: $it" }

            val blobs = db.query("SELECT hash, size FROM blob") { it.getString(1) to it.getLong(2) }
            println("Checking ${blobs.size} blobs${if (full) ", re-hashing each" else ""}…")
            for ((hash, size) in blobs) {
                val file = repo.dir.blob(hash)
                when {
                    !Files.exists(file) -> problems += "blob $hash is missing"
                    Files.size(file) != size -> problems += "blob $hash is ${Files.size(file)} bytes, expected $size"
                    full && BlobStore.sha256(file) != hash -> problems += "blob $hash is corrupt: its contents no longer match its hash"
                }
            }
            val known = blobs.map { it.first }.toSet()
            Files.walk(repo.dir.blobs).use { files ->
                files.filter { Files.isRegularFile(it) && it.name !in known }.forEach { problems += "stray file in blobs/: $it" }
            }

            db.query("SELECT media_hash, base_blob, format FROM media") { Triple(it.getString(1), it.getString(2), it.getString(3)) }
                .forEach { (media, blob, format) ->
                    if (blob !in known) {
                        problems += "media $media has no base blob"
                    } else if (full && Files.exists(repo.dir.blob(blob))) {
                        val actual = MediaHasher.hash(repo.dir.blob(blob), "file.$format").hex
                        if (actual != media) problems += "media $media: its base blob $blob now hashes to $actual"
                    }
                }

            val mediaHashes = db.query("SELECT media_hash FROM media") { it.getString(1) }.toSet()
            var versions = 0
            db.query("SELECT * FROM asset_version") { with(repo) { it.toVersion() } }.forEach { v ->
                versions++
                v.renditions.filter { it !in mediaHashes }.forEach { problems += "asset ${v.assetId} r${v.rev}: unknown media $it" }
                v.pictures.filter { it.blob !in known }.forEach { problems += "asset ${v.assetId} r${v.rev}: missing picture blob ${it.blob}" }
            }
            db.query(
                """
                SELECT h.asset_id FROM asset_head h
                WHERE h.rev != (SELECT MAX(rev) FROM asset_version v WHERE v.asset_id = h.asset_id)
                """.trimIndent(),
            ) { it.getString(1) }.forEach { problems += "asset $it: head isn't its latest version" }
            println("Checked $versions versions.")
        }
        problems.forEach(::println)
        println(if (problems.isEmpty()) "No problems found." else "${problems.size} problem(s) found.")
        return if (problems.isEmpty()) 0 else 1
    }

    /**
     * Drops versions older than [olderThan] except each asset's latest, removes assets deleted
     * before then entirely, then deletes blobs nothing references any more.
     */
    fun prune(repo: Repository, olderThan: String): Int {
        val age = parseAge(olderThan)
        val cutoffTime = repo.clock() - age.inWholeMilliseconds
        val db = repo.db
        val (versions, assets, blobs) = db.tx {
            val cutoffRev = db.long("SELECT COALESCE(MAX(rev), 0) FROM revision WHERE created_at < ?", cutoffTime)
            val goneAssets = db.query("SELECT asset_id FROM asset_head WHERE deleted = 1 AND rev <= ?", cutoffRev) { it.getString(1) }
            for (id in goneAssets) {
                db.exec("DELETE FROM asset_version WHERE asset_id = ?", id)
                db.exec("DELETE FROM asset_head WHERE asset_id = ?", id)
                db.exec("DELETE FROM asset_import WHERE asset_id = ?", id)
                db.exec("DELETE FROM issue WHERE asset_id = ?", id)
            }
            val droppedVersions = db.exec(
                "DELETE FROM asset_version WHERE rev <= ? AND rev < (SELECT h.rev FROM asset_head h WHERE h.asset_id = asset_version.asset_id)",
                cutoffRev,
            )

            // Whatever a version, an open issue or a rendition still points at stays.
            val usedMedia = HashSet<String>()
            val usedBlobs = HashSet<String>()
            db.query("SELECT renditions, pictures FROM asset_version") {
                it.getString(1) to it.getString(2)
            }.forEach { (renditions, pictures) ->
                usedMedia += com.tlisonbee.mediasync.api.ApiJson.decodeFromString(Repository.stringList, renditions)
                usedBlobs += com.tlisonbee.mediasync.api.ApiJson.decodeFromString(Repository.pictureList, pictures).map { it.blob }
            }
            db.query("SELECT candidates FROM issue WHERE resolved_rev IS NULL") { it.getString(1) }.forEach {
                usedMedia += com.tlisonbee.mediasync.api.ApiJson.decodeFromString(Repository.candidateList, it).mapNotNull { c -> c.mediaHash }
            }
            usedMedia += db.query("SELECT media_hash FROM rendition") { it.getString(1) }

            // Media uploaded within the grace period may be about to be pushed, so it stays.
            val graceCutoff = repo.clock() - BLOB_GRACE.inWholeMilliseconds
            db.query("SELECT m.media_hash, m.base_blob, b.created_at FROM media m JOIN blob b ON b.hash = m.base_blob") {
                Triple(it.getString(1), it.getString(2), it.getLong(3))
            }.forEach { (media, blob, createdAt) ->
                if (media in usedMedia || createdAt >= graceCutoff) usedBlobs += blob else db.exec("DELETE FROM media WHERE media_hash = ?", media)
            }
            val doomed = db.query("SELECT hash FROM blob WHERE created_at < ?", graceCutoff) { it.getString(1) }.filter { it !in usedBlobs }
            doomed.forEach { db.exec("DELETE FROM blob WHERE hash = ?", it) }
            Triple(droppedVersions, goneAssets.size, doomed)
        }
        // Files go only after the database no longer mentions them.
        var freed = 0L
        for (hash in blobs) {
            val file = repo.dir.blob(hash)
            if (Files.exists(file)) freed += Files.size(file)
            Files.deleteIfExists(file)
        }
        println("Pruned $versions old versions and $assets deleted assets; removed ${blobs.size} blobs (${freed / (1 shl 20)} MiB).")
        return 0
    }

    /** A consistent copy of the database for backups, taken while the server runs. */
    fun snapshot(repo: Repository) {
        Files.createDirectories(repo.dir.snapshots)
        val target = repo.dir.snapshots.resolve("repo.db")
        val tmp = repo.dir.snapshots.resolve("repo.db.tmp")
        Files.deleteIfExists(tmp)
        repo.db.read { repo.db.exec("VACUUM INTO ?", tmp.toString()) }
        Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)
        println("Wrote $target")
    }

    fun parseAge(text: String): Duration {
        val match = Regex("""(\d+)([dwmy])""").matchEntire(text.trim()) ?: throw IllegalArgumentException("Give an age like 30d, 12w, 6m or 1y")
        val n = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "d" -> n.days
            "w" -> (n * 7).days
            "m" -> (n * 30).days
            else -> (n * 365).days
        }
    }
}
