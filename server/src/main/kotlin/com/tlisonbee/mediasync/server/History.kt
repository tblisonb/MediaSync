package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetDiff
import com.tlisonbee.mediasync.api.AssetHistory
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.ChangeType
import com.tlisonbee.mediasync.api.Fields
import com.tlisonbee.mediasync.api.HistoryEntry
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.api.RevertResponse
import com.tlisonbee.mediasync.api.RevisionDiff
import com.tlisonbee.mediasync.api.RevisionDto
import com.tlisonbee.mediasync.api.RevisionsResponse
import com.tlisonbee.mediasync.api.SkippedField
import com.tlisonbee.mediasync.library.RepoPaths
import java.sql.ResultSet

fun Repository.revisions(before: Long?, limit: Int): RevisionsResponse = db.read {
    val size = limit.coerceIn(1, 500)
    val rows = db.query(
        "SELECT r.*, d.name AS device_name FROM revision r JOIN device d ON d.id = r.device_id WHERE r.rev < ? ORDER BY r.rev DESC LIMIT ?",
        before ?: Long.MAX_VALUE, size + 1,
    ) { it.toRevision() }
    RevisionsResponse(rows.take(size), more = rows.size > size)
}

fun Repository.revision(rev: Long): RevisionDto? = db.read {
    db.one("SELECT r.*, d.name AS device_name FROM revision r JOIN device d ON d.id = r.device_id WHERE r.rev = ?", rev) { it.toRevision() }
}

/** Every asset a revision touched, field by field, old → new. */
fun Repository.diff(rev: Long): RevisionDiff = db.read {
    val revision = revision(rev) ?: notFound("no revision $rev")
    val assets = db.query("SELECT * FROM asset_version WHERE rev = ? ORDER BY path", rev) { it.toVersion() }.map { v ->
        val old = versionAt(v.assetId, rev - 1)
        AssetDiff(v.assetId, v.path, changeTypes(old, v), fieldDiffs(old, v))
    }
    RevisionDiff(revision, assets)
}

fun Repository.history(assetId: String): AssetHistory = db.read {
    val versions = versions(assetId).ifEmpty { notFound("no asset $assetId") }
    val entries = versions.mapIndexed { i, v ->
        val old = versions.getOrNull(i + 1)
        val revision = revision(v.rev)!!
        HistoryEntry(toDto(v), revision.deviceId, revision.deviceName, revision.createdAt, changeTypes(old, v), fieldDiffs(old, v))
    }
    AssetHistory(assetId, entries)
}

/**
 * Undo never rewrites history: it writes a new revision. Reverting revision N puts every field N
 * changed back as it was before N, except fields that changed again since, which are skipped.
 */
fun Repository.revert(deviceId: String, request: RevertRequest): RevertResponse = write {
    val rev = request.rev
    val assetId = request.assetId
    val toRev = request.toRev
    when {
        rev != null -> revertRevision(deviceId, rev)
        assetId != null && toRev != null -> restore(deviceId, assetId, toRev, request.fields)
        else -> badRequest("give rev, or assetId and toRev")
    }
}

private fun Repository.revertRevision(deviceId: String, rev: Long): RevertResponse {
    if (revision(rev) == null) notFound("no revision $rev")
    val scope = RevisionScope(this, deviceId, "Reverted r$rev", reverts = rev)
    val skipped = mutableListOf<SkippedField>()
    val atRev = db.query("SELECT * FROM asset_version WHERE rev = ?", rev) { it.toVersion() }
    for (changed in atRev) {
        val head = headVersion(changed.assetId) ?: continue
        val before = versionAt(changed.assetId, rev - 1)
        if (before == null) {
            // Added in that revision: undoing it deletes the asset (recoverably).
            if (!head.deleted) scope.write(head.copy(deleted = true))
            continue
        }
        val headFields = comparable(head)
        val changedFields = comparable(changed)
        val beforeFields = comparable(before)
        val target = headFields.toMutableMap()
        for (field in changedFields.keys + beforeFields.keys) {
            if (changedFields[field] == beforeFields[field]) continue
            if (headFields[field] == changedFields[field]) {
                beforeFields[field]?.let { target[field] = it } ?: target.remove(field)
            } else {
                skipped += SkippedField(changed.assetId, field)
            }
        }
        // A plain file made a track (a WMA converted) goes back to being one, at the path it had.
        val reverted = if (changed.kind != before.kind && head.kind == changed.kind) head.copy(kind = before.kind) else head
        scope.write(reverted.withFields(target + keepPathIfMusic(reverted)))
    }
    scope.refreshAlbums()
    return RevertResponse(scope.finish(), skipped)
}

/** Puts one asset (or some of its [fields]) back as it was at [toRev]. */
private fun Repository.restore(deviceId: String, assetId: String, toRev: Long, fields: List<String>?): RevertResponse {
    val head = headVersion(assetId) ?: notFound("no asset $assetId")
    val old = version(assetId, toRev) ?: notFound("asset $assetId has no version at r$toRev")
    val scope = RevisionScope(this, deviceId, "Restored ${RepoPaths.name(head.path)} to r$toRev")
    val oldFields = comparable(old)
    val target = comparable(head).toMutableMap()
    for (field in fields ?: (oldFields.keys + target.keys).toList()) {
        oldFields[field]?.let { target[field] = it } ?: target.remove(field)
    }
    val restored = if (fields == null) head.copy(kind = old.kind) else head
    scope.write(restored.withFields(target + keepPathIfMusic(restored)))
    scope.refreshAlbums()
    return RevertResponse(scope.finish(), emptyList())
}

/** Fields a revert compares; a music path isn't one, since it follows the tags. */
private fun comparable(v: Version): Map<String, List<String>> {
    val fields = v.fields()
    return if (isMusic(v)) fields - Fields.PATH else fields
}

private fun keepPathIfMusic(head: Version): Map<String, List<String>> =
    if (isMusic(head)) mapOf(Fields.PATH to listOf(head.path)) else emptyMap()

private fun isMusic(v: Version) = RepoPaths.followsFields(v.kind, v.path, v.category)

private fun ResultSet.toRevision(): RevisionDto {
    val counts = ApiJson.decodeFromString(Repository.changeCounts, getString("changes"))
    val reverts = longOrNull("reverts")
    return RevisionDto(
        rev = getLong("rev"),
        deviceId = getString("device_id"),
        deviceName = getString("device_name"),
        createdAt = getLong("created_at"),
        message = getString("message") ?: describe(counts),
        reverts = reverts,
        changes = counts,
    )
}

/** "Retagged 12, moved 12" when a device gave no message. */
fun describe(counts: Map<ChangeType, Int>): String {
    if (counts.isEmpty()) return "No file changes"
    val words = mapOf(
        ChangeType.ADDED to "added", ChangeType.RETAGGED to "retagged", ChangeType.MOVED to "moved",
        ChangeType.REPLACED to "replaced", ChangeType.DELETED to "deleted", ChangeType.RESTORED to "restored",
    )
    val text = ChangeType.entries.filter { (counts[it] ?: 0) > 0 }.joinToString(", ") { "${words[it]} ${counts[it]}" }
    return text.replaceFirstChar { it.uppercase() }
}
