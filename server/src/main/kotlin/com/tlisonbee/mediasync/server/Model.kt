package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.ChangeType
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.FieldDiff
import com.tlisonbee.mediasync.api.Fields
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.library.Subscription

/** A request the server refuses; [status] becomes the HTTP status. */
class RequestException(message: String, val status: Int = 400) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw RequestException(message)

fun notFound(message: String): Nothing = throw RequestException(message, 404)

/** One row of asset_version: the full state of an asset at one revision. */
data class Version(
    val assetId: String,
    val rev: Long,
    val deleted: Boolean,
    val path: String,
    val kind: AssetKind,
    val category: String?,
    val renditions: List<String>,
    val tags: Tags,
    val pictures: List<PictureRef>,
) {
    fun sameState(other: Version) = copy(rev = 0) == other.copy(rev = 0)

    /** Every field as a list of strings: the form used for diffs, reverts and the resolve queue. */
    fun fields(): Map<String, List<String>> = buildMap {
        putAll(tags)
        put(Fields.PATH, listOf(path))
        put(Fields.MEDIA, renditions)
        if (pictures.isNotEmpty()) put(Fields.PICTURES, pictures.map(::encodePicture))
        category?.let { put(Fields.CATEGORY, listOf(it)) }
        if (deleted) put(Fields.DELETED, listOf("true"))
    }

    /** The inverse of [fields]: unknown names are tags; missing pseudo-fields take their empty value. */
    fun withFields(fields: Map<String, List<String>>) = copy(
        tags = fields.filterKeys { it !in Fields.ALL },
        path = fields[Fields.PATH]?.firstOrNull() ?: path,
        renditions = fields[Fields.MEDIA]?.takeIf { it.isNotEmpty() } ?: renditions,
        pictures = fields[Fields.PICTURES].orEmpty().map(::decodePicture),
        category = fields[Fields.CATEGORY]?.firstOrNull(),
        deleted = fields[Fields.DELETED]?.firstOrNull() == "true",
    )

    companion object {
        fun encodePicture(p: PictureRef) = "${p.type}:${p.blob}:${p.mime}"

        fun decodePicture(s: String): PictureRef {
            val (type, blob, mime) = s.split(':', limit = 3)
            return PictureRef(type, blob, mime)
        }
    }
}

/** How [new] differs from [old], for the revision log. */
fun changeTypes(old: Version?, new: Version): List<ChangeType> {
    if (old == null) return listOf(ChangeType.ADDED)
    return buildList {
        if (new.deleted && !old.deleted) add(ChangeType.DELETED)
        if (!new.deleted && old.deleted) add(ChangeType.RESTORED)
        if (new.tags != old.tags || new.pictures != old.pictures) add(ChangeType.RETAGGED)
        if (new.path != old.path) add(ChangeType.MOVED)
        if (new.renditions != old.renditions) add(ChangeType.REPLACED)
    }
}

fun fieldDiffs(old: Version?, new: Version): List<FieldDiff> {
    val before = old?.fields().orEmpty()
    val after = new.fields()
    return (before.keys + after.keys).filter { before[it] != after[it] }.map { FieldDiff(it, before[it], after[it]) }
}

data class Device(
    val id: String,
    val kind: DeviceKind,
    val name: String,
    val subscription: Subscription,
    /** The subscription changed since the device last finished a full listing. */
    val rescan: Boolean,
    val syncedRev: Long,
    val createdAt: Long,
    val lastSeenAt: Long?,
    val revokedAt: Long?,
    /** Set when the app was allowed into the web UI (WebAccess.kt). */
    val webGrant: String? = null,
)

data class MediaRow(val mediaHash: String, val baseBlob: String, val format: String, val size: Long, val durationMs: Long?)

data class BlobRow(val hash: String, val size: Long, val mime: String, val createdAt: Long)
