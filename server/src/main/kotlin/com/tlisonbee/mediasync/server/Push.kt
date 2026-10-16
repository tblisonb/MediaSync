package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Candidate
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.Fields
import com.tlisonbee.mediasync.api.IssueKind
import com.tlisonbee.mediasync.api.Limits
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.PushOutcome
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.api.PushResponse
import com.tlisonbee.mediasync.api.PushResult
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.library.ThreeWayMerge
import com.tlisonbee.mediasync.library.VideoNames
import java.util.UUID
import kotlin.math.abs

/** Files in another format whose tags match and whose length is this close are offered as renditions. */
private const val RENDITION_DURATION_MS = 2_000

/**
 * Applies one device's local changes as a single revision. Each change is merged field by field
 * against the version the device last synced; nothing waits for the user, and disagreements go to
 * the resolve queue with the server's value in use.
 */
fun Repository.push(device: Device, request: PushRequest): PushResponse {
    if (request.changes.size > Limits.MAX_CHANGES_PER_PUSH) badRequest("at most ${Limits.MAX_CHANGES_PER_PUSH} changes per push")
    if ((request.message?.length ?: 0) > Limits.MAX_MESSAGE) badRequest("message too long")
    return write {
        val scope = RevisionScope(this, device.id, request.message)
        val applied = request.changes.mapIndexed { i, change ->
            try {
                applyChange(scope, device, change)
            } catch (e: RequestException) {
                throw RequestException("change $i (${change.path}): ${e.message}", e.status)
            }
        }
        scope.refreshAlbums()
        val rev = scope.finish() ?: head()
        // Album renames may have moved a pushed asset again, so report each one's final state.
        PushResponse(rev, applied.map { (outcome, assetId) ->
            PushResult(assetId, outcome, toDto(headVersion(assetId)!!), scope.issuesFor(assetId))
        })
    }
}

private data class Applied(val outcome: PushOutcome, val assetId: String)

private fun Repository.applyChange(scope: RevisionScope, device: Device, change: PushChange): Applied {
    change.mediaHash?.let(BlobStore::requireHash)
    change.baseMediaHash?.let(BlobStore::requireHash)
    change.pictures.forEach { if (blob(it.blob) == null) badRequest("picture blob ${it.blob} hasn't been uploaded") }
    if (change.tags.size > 100) badRequest("too many tag fields")
    val path = try {
        RepoPaths.clean(change.path)
    } catch (e: IllegalArgumentException) {
        badRequest(e.message ?: "bad path")
    }
    val assetId = change.assetId
    return if (assetId == null) {
        checkSends(device, path, change.kind)
        importNew(scope, device, change, path)
    } else {
        val head = headVersion(assetId) ?: notFound("no asset $assetId")
        checkSends(device, head.path, head.kind)
        // Outside music the device's path counts, so moving a file needs rights at both ends.
        if (!RepoPaths.isMusic(head.path) && path != head.path) checkSends(device, path, head.kind)
        mergeExisting(scope, change, head)
    }
}

private fun checkSends(device: Device, path: String, kind: AssetKind) {
    if (device.kind != DeviceKind.WEB && !device.subscription.sends(path, kind)) {
        throw RequestException("$path isn't in a two-way or send-only rule of this device's subscription", 403)
    }
}

private fun Repository.requireMedia(change: PushChange): MediaRow {
    val hash = change.mediaHash ?: badRequest("mediaHash is required")
    val media = media(hash) ?: throw RequestException("media $hash hasn't been uploaded", 409)
    val duration = change.durationMs
    if (media.durationMs == null && duration != null && duration > 0) {
        db.exec("UPDATE media SET duration_ms = ? WHERE media_hash = ?", duration, hash)
    }
    return media
}

private fun Repository.importNew(scope: RevisionScope, device: Device, change: PushChange, path: String): Applied {
    if (change.deleted) badRequest("a new file can't be deleted")
    val media = requireMedia(change)
    var tags = policy.filter(change.kind, change.tags)
    val pictures = policy.filterPictures(change.kind, change.pictures)
    // A video's fields come from its name: a first guess, corrected in the web UI.
    var category: String? = null
    if (change.kind == AssetKind.VIDEO && path.startsWith(RepoPaths.VIDEOS)) {
        val guess = VideoNames.guess(change.sourcePath ?: "$path.${media.format}")
        category = guess.category
        if (tags.isEmpty()) tags = policy.filter(change.kind, guess.tags)
    }

    // The repository already has this media: the file belongs to that asset.
    liveWithMedia(media.mediaHash).firstOrNull()?.let { existing ->
        if (existing.tags == tags && existing.pictures == pictures) return Applied(PushOutcome.ADOPTED, existing.assetId)
        val incoming = existing.copy(tags = tags, pictures = pictures)
        scope.raise(
            IssueKind.DUPLICATE, existing.assetId,
            candidates = listOf(
                Candidate("In the repository", assetId = existing.assetId, path = existing.path, fields = tagFields(existing), mediaHash = media.mediaHash),
                Candidate("Imported from ${deviceName(device.id)}", deviceId = device.id, path = change.sourcePath ?: path, fields = tagFields(incoming), mediaHash = media.mediaHash),
            ),
            provisional = 0,
        )
        return Applied(PushOutcome.DUPLICATE, existing.assetId)
    }

    val assetId = UUID.randomUUID().toString()
    db.exec(
        "INSERT INTO asset_import (asset_id, rev, device_id, source_path, raw_tags) VALUES (?, ?, ?, ?, ?)",
        assetId, scope.rev(), device.id, change.sourcePath ?: path,
        ApiJson.encodeToString(Repository.tagsSerializer, change.rawTags ?: change.tags),
    )
    // Brought in by a device that keeps only what it picks: it keeps this.
    if (device.subscription.rules.any { it.pick && it.mode != SyncMode.SEND_ONLY && it.covers(path, change.kind) }) {
        db.exec("INSERT OR IGNORE INTO pick (device_id, asset_id) VALUES (?, ?)", device.id, assetId)
    }
    val proposed = Version(assetId, 0, false, path, change.kind, category, listOf(media.mediaHash), tags, pictures)
    // Another format of a track has the same tags, so it would also collide on its path; the
    // rendition entry covers that, and linking makes the moved-aside copy go away.
    val match = possibleRendition(proposed, media)
    // A subtitle named like its video ("Movie.srt" beside "Movie.mp4") shares its path until it's
    // filed beside the video, at the end of the push.
    val sidecar = path.startsWith(RepoPaths.VIDEOS) && media.format in SUBTITLE_FORMATS + POSTER_FORMATS
    val written = scope.write(proposed, raiseCollision = match == null && !sidecar)
    if (match != null) raiseRendition(scope, match, written, media)
    return Applied(PushOutcome.CREATED, assetId)
}

/** An existing track in another format with the same tags and length. */
private fun Repository.possibleRendition(new: Version, media: MediaRow): Version? {
    val key = Repository.matchKey(new.kind, new.tags) ?: return null
    val duration = media(media.mediaHash)?.durationMs ?: return null
    return liveWithMatchKey(key).firstOrNull { other ->
        other.assetId != new.assetId && other.renditions.mapNotNull(::media).any {
            it.format != media.format && it.durationMs != null && abs(it.durationMs - duration) <= RENDITION_DURATION_MS
        }
    }
}

/** Suggests linking; never links on its own. */
private fun Repository.raiseRendition(scope: RevisionScope, match: Version, new: Version, media: MediaRow) {
    scope.raise(
        IssueKind.POSSIBLE_RENDITION, new.assetId,
        candidates = listOf(
            Candidate("Existing track", assetId = match.assetId, path = match.path, fields = tagFields(match), mediaHash = match.renditions.first()),
            Candidate("New file", assetId = new.assetId, path = new.path, fields = tagFields(new), mediaHash = media.mediaHash),
        ),
        provisional = 1,
    )
}

private fun Repository.mergeExisting(scope: RevisionScope, change: PushChange, head: Version): Applied {
    val base = change.baseRev?.let { version(head.assetId, it) }
    val unchanged = Applied(PushOutcome.UNCHANGED, head.assetId)

    if (change.deleted) {
        if (head.deleted) return unchanged
        // Deleted here, edited elsewhere: the edit wins and the device gets the asset back.
        if (base == null || !base.sameState(head)) return unchanged
        scope.write(head.copy(deleted = true))
        return Applied(PushOutcome.UPDATED, head.assetId)
    }

    val kind = head.kind
    // Named from its fields (music, movies, episodes): the device's path doesn't count.
    val music = RepoPaths.followsFields(kind, head.path, head.category)
    val ours = head.copy(
        tags = policy.filter(kind, change.tags),
        pictures = policy.filterPictures(kind, change.pictures),
        path = if (music) head.path else RepoPaths.clean(change.path),
        deleted = false,
    )

    // Tags, pictures and (outside music, where it follows the tags) the path, field by field.
    fun mergeable(v: Version) = v.fields() - Fields.MEDIA - Fields.DELETED - Fields.CATEGORY - (if (music) Fields.PATH else "")
    val merge = ThreeWayMerge.merge(base?.let(::mergeable), mergeable(ours), mergeable(head))
    var result = head.withFields(merge.merged + mapOf(Fields.PATH to listOf(head.path), Fields.MEDIA to head.renditions))
        .copy(deleted = head.deleted, category = head.category)
    if (!music) result = result.copy(path = merge.merged[Fields.PATH]?.firstOrNull() ?: head.path)
    if (merge.conflicts.isNotEmpty()) {
        val fields = merge.conflicts.toList()
        val oursFields = ours.fields()
        val theirsFields = head.fields()
        scope.raise(
            IssueKind.FIELD_CONFLICT, head.assetId,
            candidates = listOf(
                Candidate("Current", path = head.path, fields = fields.mapNotNull { f -> theirsFields[f]?.let { f to it } }.toMap()),
                Candidate("From ${deviceName(scope.deviceId)}", deviceId = scope.deviceId, fields = fields.mapNotNull { f -> oursFields[f]?.let { f to it } }.toMap()),
            ),
            provisional = 0,
            fields = fields,
        )
    }

    // Media: the device's file either still has the media it synced, or it was replaced.
    val oursMedia = requireMedia(change).mediaHash
    val baseMedia = change.baseMediaHash ?: base?.renditions?.firstOrNull()
    if (oursMedia != baseMedia) {
        result = when {
            oursMedia in head.renditions -> result
            baseMedia != null && baseMedia in head.renditions ->
                result.copy(renditions = head.renditions.map { if (it == baseMedia) oursMedia else it })
            else -> {
                // Replaced on both sides: keep the server's media and queue the device's.
                scope.raise(
                    IssueKind.MEDIA_CONFLICT, head.assetId,
                    candidates = listOf(
                        Candidate("Current", path = head.path, mediaHash = head.renditions.first()),
                        Candidate("From ${deviceName(scope.deviceId)}", deviceId = scope.deviceId, mediaHash = oursMedia),
                    ),
                    provisional = 0,
                )
                result
            }
        }
    }

    // Deleted on the server but edited here: the edit wins and the asset comes back.
    if (head.deleted) {
        val edited = base == null || mergeable(base) != mergeable(ours) || oursMedia != baseMedia
        if (!edited) return unchanged
        result = result.copy(deleted = false)
    }

    val written = scope.write(result)
    val outcome = if (written.rev == head.rev) PushOutcome.UNCHANGED else PushOutcome.UPDATED
    return Applied(outcome, head.assetId)
}

/** Tags and pictures in the resolve queue's field form. */
fun tagFields(v: Version): Map<String, List<String>> = v.fields().filterKeys { it !in Fields.ALL || it == Fields.PICTURES }
