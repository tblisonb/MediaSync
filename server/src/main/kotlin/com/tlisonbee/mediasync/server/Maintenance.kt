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

    /** The prune command: [Repository.prune] through the last revision older than [olderThan]. */
    fun prune(repo: Repository, olderThan: String, dryRun: Boolean = false): Int {
        val through = repo.revBefore(repo.clock() - parseAge(olderThan).inWholeMilliseconds)
        if (through <= repo.prunedThrough()) {
            println("Nothing older than $olderThan that isn't pruned already.")
            return 0
        }
        val plan = repo.prune(through, dryRun)
        val what = "${plan.versions} old versions and ${plan.assets} deleted files, r${plan.from}–r${plan.through}; " +
            "${plan.blobs.size} blobs (${plan.bytes / (1 shl 20)} MiB)"
        println(if (dryRun) "Would prune $what. Nothing changed." else "Pruned $what.")
        plan.heldUntil?.let {
            println("${plan.heldBytes / (1 shl 20)} MiB of unpushed uploads stay until ${java.time.Instant.ofEpochMilli(it)}, a week after upload.")
        }
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
