package com.tlisonbee.mediasync.api

import com.tlisonbee.mediasync.library.Subscription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Request and response types of the sync API (/api/v1), shared by the server and every client so
 * the two ends can't drift apart. Hashes are lowercase hex SHA-256. Tags are canonical field names
 * (see TagField) mapped to their values.
 */

/** JSON settings for the API, used by both ends. Unknown fields are ignored so either side can add some. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

typealias Tags = Map<String, List<String>>

@Serializable
enum class AssetKind {
    @SerialName("audio") AUDIO,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("other") OTHER,
    ;

    val apiName get() = name.lowercase()
}

@Serializable
enum class DeviceKind {
    @SerialName("desktop") DESKTOP,
    @SerialName("android") ANDROID,

    /** The server's own web UI, which records edits made there. */
    @SerialName("web") WEB,
}

/** A picture embedded in an asset's files, such as the front cover. Its bytes are a blob. */
@Serializable
data class PictureRef(val type: String, val blob: String, val mime: String)

/** One encoding of an asset. A device fetches [blob] and writes the asset's tags into it. */
@Serializable
data class RenditionDto(
    val mediaHash: String,
    val blob: String,
    /** File extension the rendition is stored with: flac, mp3, m4a, ogg, opus, jpg, … */
    val format: String,
    val size: Long,
    val durationMs: Long? = null,
)

/** The full state of one asset at one revision. */
@Serializable
data class AssetDto(
    val assetId: String,
    /** The revision that wrote this version: a device's merge base when it pushes a change. */
    val rev: Long,
    val deleted: Boolean,
    /** Repository path without extension; each rendition adds its own [RenditionDto.format]. */
    val path: String,
    val kind: AssetKind,
    val category: String? = null,
    /** The original first. */
    val renditions: List<RenditionDto>,
    val tags: Tags,
    val pictures: List<PictureRef>,
)

// ---- Pairing and devices ----

@Serializable
data class PairRequest(val code: String, val name: String, val kind: DeviceKind)

@Serializable
data class PairResponse(val deviceId: String, val token: String, val head: Long)

@Serializable
data class DeviceDto(
    val id: String,
    val kind: DeviceKind,
    val name: String,
    val syncedRev: Long,
    val lastSeenAt: Long?,
    val subscription: Subscription,
)

// ---- Changes ----

@Serializable
data class ChangesResponse(
    /** The newest revision. Once [more] is false, the device has everything up to it. */
    val head: Long,
    /** Latest version of each in-scope asset changed after `since`, deleted ones included. */
    val assets: List<AssetDto>,
    /** Assets that left this device's scope after `since`, through a retag or a move. */
    val left: List<String>,
    /**
     * The device's subscription changed after `since`, so this is a full listing of everything in
     * scope: anything the device holds that isn't listed has left its scope.
     */
    val reset: Boolean,
    val more: Boolean,
    /** When [more]: pass as `after` (with the same `since`) for the next page. */
    val next: String?,
)

// ---- Blobs and uploads ----

@Serializable
data class MediaDto(val mediaHash: String, val blob: String, val format: String, val size: Long, val durationMs: Long? = null)

/** Starts (or finds) a resumable upload of one file. [name] supplies the format by its extension. */
@Serializable
data class UploadRequest(val hash: String, val size: Long, val name: String)

@Serializable
data class UploadResponse(
    /** Null when [done]: the server already has the blob. */
    val id: String?,
    /** Bytes already received; continue with PATCH /uploads/{id} and `Upload-Offset: offset`. */
    val offset: Long,
    val done: Boolean,
    /** Set once done, for audio and other media files. */
    val media: MediaDto? = null,
)

// ---- Push ----

@Serializable
data class PushRequest(
    val message: String? = null,
    val changes: List<PushChange>,
)

/** One file's local state. Unchanged fields are fine: only differences from [baseRev] count. */
@Serializable
data class PushChange(
    /** Null for a file the device has never synced. */
    val assetId: String? = null,
    /** [AssetDto.rev] of the version the device last synced: the merge base. */
    val baseRev: Long? = null,
    /** Repository path without extension, e.g. "music/Radiohead/OK Computer/01 Airbag". Music paths are recomputed from the tags. */
    val path: String,
    val kind: AssetKind,
    /** The media hash of the device's file. Uploaded first; may be null only for a deletion. */
    val mediaHash: String? = null,
    /** The media hash the device had at [baseRev], so the server knows which rendition it replaces. */
    val baseMediaHash: String? = null,
    val durationMs: Long? = null,
    val tags: Tags = emptyMap(),
    val pictures: List<PictureRef> = emptyList(),
    val deleted: Boolean = false,
    /** New files only: every tag as read from the file, before the tag policy filtered it. */
    val rawTags: Tags? = null,
    /** New files only: where the file was found on the device. */
    val sourcePath: String? = null,
)

@Serializable
enum class PushOutcome {
    /** A new asset. */
    @SerialName("created") CREATED,

    /** The change was merged into the asset. */
    @SerialName("updated") UPDATED,

    /** Nothing differed from the server's version. */
    @SerialName("unchanged") UNCHANGED,

    /** A new file whose media and tags the repository already had: it belongs to that asset. */
    @SerialName("adopted") ADOPTED,

    /** A new file whose media the repository had with other tags: it belongs to that asset, and a duplicate entry waits in the resolve queue. */
    @SerialName("duplicate") DUPLICATE,
}

@Serializable
data class PushResult(
    val assetId: String,
    val outcome: PushOutcome,
    /** The asset's state after the push: record it as the new base for this file. */
    val asset: AssetDto,
    /** Resolve-queue entries raised by this change. */
    val issues: List<Long> = emptyList(),
)

@Serializable
data class PushResponse(
    /** The revision this push created, or the current head if it changed nothing. It doesn't advance the device's `since`. */
    val rev: Long,
    /** One per change, in order. */
    val results: List<PushResult>,
)

// ---- History ----

@Serializable
enum class ChangeType {
    @SerialName("added") ADDED,
    @SerialName("retagged") RETAGGED,
    @SerialName("moved") MOVED,
    @SerialName("replaced") REPLACED,
    @SerialName("deleted") DELETED,
    @SerialName("restored") RESTORED,
}

@Serializable
data class RevisionDto(
    val rev: Long,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Long,
    /** The device's message, or one generated from [changes]. */
    val message: String,
    val reverts: Long? = null,
    /** How many assets had each kind of change. */
    val changes: Map<ChangeType, Int>,
)

@Serializable
data class RevisionsResponse(val revisions: List<RevisionDto>, val more: Boolean)

/**
 * One field before and after; null means absent. Fields are tag names plus [Fields]' pseudo-fields:
 * path, media (rendition hashes), pictures ("front:<blob>:<mime>"), category and deleted.
 */
@Serializable
data class FieldDiff(val field: String, val old: List<String>?, val new: List<String>?)

@Serializable
data class AssetDiff(val assetId: String, val path: String, val changes: List<ChangeType>, val fields: List<FieldDiff>)

@Serializable
data class RevisionDiff(val revision: RevisionDto, val assets: List<AssetDiff>)

@Serializable
data class HistoryEntry(
    val version: AssetDto,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Long,
    val changes: List<ChangeType>,
    val fields: List<FieldDiff>,
)

/** Every version of one asset, newest first. */
@Serializable
data class AssetHistory(val assetId: String, val versions: List<HistoryEntry>)

/** Either [rev] (undo a whole revision) or [assetId] + [toRev] (restore one asset, optionally only some [fields]). */
@Serializable
data class RevertRequest(
    val rev: Long? = null,
    val assetId: String? = null,
    val toRev: Long? = null,
    val fields: List<String>? = null,
)

@Serializable
data class SkippedField(val assetId: String, val field: String)

@Serializable
data class RevertResponse(
    /** Null when there was nothing to change. */
    val rev: Long?,
    /** Fields left alone because they changed again after the reverted revision. */
    val skipped: List<SkippedField>,
)

// ---- Resolve queue ----

@Serializable
enum class IssueKind {
    @SerialName("field_conflict") FIELD_CONFLICT,
    @SerialName("duplicate") DUPLICATE,
    @SerialName("possible_rendition") POSSIBLE_RENDITION,
    @SerialName("path_collision") PATH_COLLISION,
    @SerialName("media_conflict") MEDIA_CONFLICT,
}

/**
 * One side of a resolve-queue entry. [fields] holds its values in [FieldDiff]'s form: tag names,
 * plus pictures ("front:<blob>:<mime>") where they matter. Which members are set depends on the kind.
 */
@Serializable
data class Candidate(
    val label: String,
    val deviceId: String? = null,
    val assetId: String? = null,
    val path: String? = null,
    val fields: Tags = emptyMap(),
    val mediaHash: String? = null,
)

@Serializable
data class IssueDto(
    val id: Long,
    val kind: IssueKind,
    val assetId: String,
    val path: String,
    val rev: Long,
    val deviceId: String,
    val deviceName: String,
    /** For field conflicts: the fields in dispute (tag names, or "pictures"). */
    val fields: List<String>,
    val candidates: List<Candidate>,
    /** Index of the candidate in use until resolved. */
    val provisional: Int,
    val resolvedRev: Long? = null,
)

@Serializable
enum class Choice {
    /** The provisional value becomes final. */
    @SerialName("keep") KEEP,

    /** The other candidate replaces it. */
    @SerialName("other") OTHER,

    /** Field by field: [ResolveRequest.fields] picks a candidate for each. */
    @SerialName("merge") MERGE,

    /** Duplicates: split into two assets. */
    @SerialName("both") BOTH,

    /** Possible renditions: merge into one asset with both formats. */
    @SerialName("link") LINK,
}

@Serializable
data class ResolveRequest(val choice: Choice, val fields: Map<String, Int>? = null)

@Serializable
data class ResolveResponse(val rev: Long)

/** Names of the fields of an asset that aren't tags. No tag policy may allow a tag by these names. */
object Fields {
    const val PATH = "path"
    const val MEDIA = "media"
    const val PICTURES = "pictures"
    const val CATEGORY = "category"
    const val DELETED = "deleted"
    val ALL = setOf(PATH, MEDIA, PICTURES, CATEGORY, DELETED)
}

/** Sent on GET /events whenever a revision lands. */
@Serializable
data class HeadEvent(val head: Long)

object Limits {
    const val MAX_CHANGES_PER_PUSH = 10_000
    const val MAX_CHANGES_PAGE = 2_000
    const val MAX_TAG_VALUES = 50
    const val MAX_TAG_VALUE = 2_000
    const val MAX_PATH_BYTES = 1_024
    const val MAX_MESSAGE = 1_000
    const val MAX_DEVICE_NAME = 100
    const val MAX_RULES = 100
}

/** A video, and whether the asking device keeps it. */
@Serializable
data class PickItem(
    val assetId: String,
    val path: String,
    val category: String?,
    /** "Inception (2010)", "Fringe S01E02 · The Same Old Story". */
    val label: String,
    val size: Long,
    val picked: Boolean,
)

@Serializable
data class PickRequest(val pick: List<String> = emptyList(), val unpick: List<String> = emptyList())

/** Every asset whose place on the device changed: the videos, with their subtitles and posters. */
@Serializable
data class PickResponse(val changed: List<String>)

/**
 * Field edits made on a device, such as a tag editor: one revision. [edits] maps an asset to the
 * fields that change ("category" sets a video's kind); an empty list removes a field. With
 * [expectedRevs], nothing is written if any of those assets changed since.
 */
@Serializable
data class EditRequest(
    val message: String? = null,
    val edits: Map<String, Map<String, List<String>>>,
    val expectedRevs: Map<String, Long> = emptyMap(),
)

@Serializable
data class EditResponse(val rev: Long?)
