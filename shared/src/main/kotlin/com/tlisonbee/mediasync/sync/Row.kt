package com.tlisonbee.mediasync.sync

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Tags
import java.nio.file.Path

/**
 * What one local file was, the last time MediaSync looked at it ([media], [tags], [cover]), and
 * what it was when it was last in sync with the server ([seen]* and [base]). A file needs pushing
 * when the two differ, when it has never been synced, or when it went missing.
 */
data class Row(
    val path: String,
    /** The prefix of the synced folder it lives in. */
    val prefix: String,
    val size: Long,
    val mtime: Long,
    val kind: AssetKind,
    val media: String,
    val tags: Tags,
    /** SHA-256 of the front cover's bytes, which is also its blob hash. */
    val cover: String?,
    val coverMime: String?,
    val durationMs: Long?,
    val seenMedia: String? = null,
    val seenTags: Tags? = null,
    val seenCover: String? = null,
    val assetId: String? = null,
    val base: AssetDto? = null,
    /** Gone from disk; its deletion hasn't been pushed yet. */
    val missing: Boolean = false,
    /** Set when MediaSync leaves the file alone, e.g. a second local copy of an asset. */
    val ignored: String? = null,
    /** The file holds tags outside the tag policy, to be removed at the next sync. */
    val extras: Boolean = false,
) {
    val file: Path get() = Path.of(path)

    val extension: String get() = file.fileName.toString().substringAfterLast('.', "")

    val changed: Boolean
        get() = assetId == null || missing || media != seenMedia || tags != seenTags || cover != seenCover

    /** This row as freshly in sync with [asset]: what it holds now is what the server has. */
    fun synced(asset: AssetDto) = copy(
        seenMedia = media, seenTags = tags, seenCover = cover, assetId = asset.assetId, base = asset, missing = false,
    )
}

/** Where a client keeps its [Row]s and a few values of state: SQLite on both platforms. */
interface FileIndex {
    fun all(): List<Row>
    fun get(path: Path): Row?
    fun byAsset(assetId: String): List<Row>
    fun inFolder(prefix: String): List<Row>
    fun put(row: Row)
    fun delete(path: String)

    /** Moves a row to a new path (the file was renamed). */
    fun replace(old: String, row: Row) = transaction {
        delete(old)
        put(row)
    }

    fun <T> transaction(block: () -> T): T
    fun state(key: String): String?
    fun setState(key: String, value: String?)

    /** Server changes staged for review, one per asset. */
    fun remote(): List<RemoteChange>
    fun putRemote(change: RemoteChange)
    fun deleteRemote(assetId: String)
}
