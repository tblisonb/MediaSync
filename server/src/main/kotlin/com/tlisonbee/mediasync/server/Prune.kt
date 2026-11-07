package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import java.nio.file.Files
import kotlin.time.Duration.Companion.days

/**
 * Uploaded blobs no revision has used yet are left alone for this long, so prune can't race a sync
 * that's about to push them. A blob some version used (a replaced original, say) gets no grace: it
 * was pushed already.
 */
private val BLOB_GRACE = 7.days

/**
 * What pruning through [through] drops (or dropped): revisions [from]–[through] lose the versions
 * no later state needs, deleted files go for good, and [blobs] nothing references any more go.
 */
class PrunePlan(
    val from: Long,
    val through: Long,
    val versions: Int,
    val assets: Int,
    val blobs: List<String>,
    val bytes: Long,
    /** Unused uploads kept anyway for being under [BLOB_GRACE] old, and when the last of them is past it. */
    val heldBytes: Long = 0,
    val heldUntil: Long? = null,
)

/** One prune, as the History page tells of it. */
class PruneRecord(val through: Long, val at: Long, val versions: Int, val bytes: Long)

private data class UnusedBlob(val hash: String, val size: Long, val createdAt: Long)

private class DryRun(val plan: PrunePlan) : RuntimeException(null, null, false, false)

/** The newest revision made before [time]; 0 if none. */
fun Repository.revBefore(time: Long): Long = db.long("SELECT COALESCE(MAX(rev), 0) FROM revision WHERE created_at < ?", time)

/**
 * How far history is pruned: revisions up to this one may have lost the versions before them, so
 * they can't be reverted. 0 when nothing is.
 */
fun Repository.prunedThrough(): Long = db.long("SELECT COALESCE(MAX(through_rev), 0) FROM prune")

fun Repository.lastPrune(): PruneRecord? = db.one("SELECT * FROM prune ORDER BY through_rev DESC LIMIT 1") {
    PruneRecord(it.getLong("through_rev"), it.getLong("at"), it.getInt("versions"), it.getLong("bytes"))
}

/** Why history can't be pruned now, or null: a conversion reads and replaces blobs as it goes. */
fun Repository.pruneBlocker(): String? = when {
    conversion.get()?.finished == false -> "An MP3 conversion is running. Prune once it finishes."
    videoConversion.get()?.finished == false -> "A video conversion is running. Prune once it finishes."
    else -> null
}

/**
 * Drops history through [through], keeping every state after it: for each file, the versions
 * before the one current at [through] go, so any later revision can still be reverted. Files
 * deleted by then go entirely, once every device has synced past the deletion (a device behind
 * would never hear of it otherwise). Then blobs nothing references any more are deleted.
 * [dryRun] works it all out and changes nothing.
 */
fun Repository.prune(through: Long, dryRun: Boolean = false): PrunePlan {
    val from = prunedThrough() + 1
    val plan = try {
        write {
            // A device that hasn't synced yet gets a full listing, deletions or not.
            val synced = db.long(
                "SELECT COALESCE(MIN(synced_rev), ?) FROM device WHERE revoked_at IS NULL AND kind != 'web' AND synced_rev > 0",
                through,
            )
            // What any version used, before versions go: those blobs were pushed, so no grace for them.
            val committedMedia = HashSet<String>()
            val committedBlobs = HashSet<String>()
            db.query("SELECT renditions, pictures FROM asset_version") { it.getString(1) to it.getString(2) }.forEach { (renditions, pictures) ->
                committedMedia += ApiJson.decodeFromString(Repository.stringList, renditions)
                committedBlobs += ApiJson.decodeFromString(Repository.pictureList, pictures).map { it.blob }
            }
            val goneAssets = db.query("SELECT asset_id FROM asset_head WHERE deleted = 1 AND rev <= ?", minOf(through, synced)) { it.getString(1) }
            for (id in goneAssets) {
                db.exec("DELETE FROM asset_version WHERE asset_id = ?", id)
                db.exec("DELETE FROM asset_head WHERE asset_id = ?", id)
                db.exec("DELETE FROM asset_import WHERE asset_id = ?", id)
                db.exec("DELETE FROM issue WHERE asset_id = ?", id)
            }
            val versions = db.exec(
                """
                DELETE FROM asset_version WHERE rev <= ?1
                  AND rev < (SELECT MAX(v.rev) FROM asset_version v WHERE v.asset_id = asset_version.asset_id AND v.rev <= ?1)
                """.trimIndent(),
                through,
            )

            // Whatever a version, an open issue or a rendition still points at stays.
            val usedMedia = HashSet<String>()
            val usedBlobs = HashSet<String>()
            db.query("SELECT renditions, pictures FROM asset_version") { it.getString(1) to it.getString(2) }.forEach { (renditions, pictures) ->
                usedMedia += ApiJson.decodeFromString(Repository.stringList, renditions)
                usedBlobs += ApiJson.decodeFromString(Repository.pictureList, pictures).map { it.blob }
            }
            db.query("SELECT candidates FROM issue WHERE resolved_rev IS NULL") { it.getString(1) }.forEach {
                usedMedia += ApiJson.decodeFromString(Repository.candidateList, it).mapNotNull { c -> c.mediaHash }
            }
            usedMedia += db.query("SELECT media_hash FROM rendition") { it.getString(1) }

            // Media uploaded within the grace period that no version used may be about to be pushed, so it stays.
            val graceCutoff = clock() - BLOB_GRACE.inWholeMilliseconds
            val heldBlobs = HashSet<String>()
            db.query("SELECT m.media_hash, m.base_blob, b.created_at FROM media m JOIN blob b ON b.hash = m.base_blob") {
                Triple(it.getString(1), it.getString(2), it.getLong(3))
            }.forEach { (media, blob, createdAt) ->
                when {
                    media in usedMedia -> usedBlobs += blob
                    media !in committedMedia && createdAt >= graceCutoff -> heldBlobs += blob
                    else -> {
                        committedBlobs += blob
                        db.exec("DELETE FROM media WHERE media_hash = ?", media)
                    }
                }
            }
            val (doomed, held) = db.query("SELECT hash, size, created_at FROM blob") { UnusedBlob(it.getString(1), it.getLong(2), it.getLong(3)) }
                .filter { it.hash !in usedBlobs }
                .partition { it.hash !in heldBlobs && (it.createdAt < graceCutoff || it.hash in committedBlobs) }
            doomed.forEach { (hash) ->
                db.exec("DELETE FROM blob WHERE hash = ?", hash)
                db.exec("DELETE FROM image_info WHERE blob = ?", hash)
            }
            val plan = PrunePlan(
                from, through, versions, goneAssets.size, doomed.map { it.hash }, doomed.sumOf { it.size },
                heldBytes = held.sumOf { it.size },
                heldUntil = held.maxOfOrNull { it.createdAt + BLOB_GRACE.inWholeMilliseconds },
            )
            if (dryRun) throw DryRun(plan)
            db.exec("INSERT INTO prune (through_rev, at, versions, bytes) VALUES (?, ?, ?, ?)", through, clock(), versions, plan.bytes)
            plan
        }
    } catch (e: DryRun) {
        return e.plan
    }
    // Files go only after the database no longer mentions them.
    for (hash in plan.blobs) {
        Files.deleteIfExists(dir.blob(hash))
        Files.deleteIfExists(dir.thumb(hash))
    }
    ownDataSize.set(null)
    return plan
}
