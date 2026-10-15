package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.Choice
import com.tlisonbee.mediasync.api.Fields
import com.tlisonbee.mediasync.api.IssueDto
import com.tlisonbee.mediasync.api.IssueKind
import com.tlisonbee.mediasync.api.ResolveRequest
import com.tlisonbee.mediasync.api.ResolveResponse
import com.tlisonbee.mediasync.library.RepoPaths
import java.sql.ResultSet
import java.util.UUID

fun Repository.openIssues(): List<IssueDto> = db.read {
    db.query("SELECT * FROM issue WHERE resolved_rev IS NULL ORDER BY id") { it.toIssue() }.map { withNames(it) }
}

fun Repository.issue(id: Long): IssueDto? = db.read {
    db.one("SELECT * FROM issue WHERE id = ?", id) { it.toIssue() }?.let { withNames(it) }
}

fun Repository.openIssueCount(): Long = db.read { db.long("SELECT COUNT(*) FROM issue WHERE resolved_rev IS NULL") }

/**
 * Settles a resolve-queue entry, like `svn resolve --accept`. Every choice, even keeping the
 * current value, is a revision of its own, so it can be reverted like anything else.
 */
fun Repository.resolve(deviceId: String, id: Long, request: ResolveRequest): ResolveResponse = write {
    val issue = db.one("SELECT * FROM issue WHERE id = ?", id) { it.toIssue() } ?: notFound("no issue $id")
    if (issue.resolvedRev != null) throw RequestException("issue $id was already resolved in r${issue.resolvedRev}", 409)
    val allowed = when (issue.kind) {
        IssueKind.FIELD_CONFLICT -> setOf(Choice.KEEP, Choice.OTHER, Choice.MERGE)
        IssueKind.DUPLICATE -> setOf(Choice.KEEP, Choice.OTHER, Choice.MERGE, Choice.BOTH)
        IssueKind.POSSIBLE_RENDITION -> setOf(Choice.KEEP, Choice.BOTH, Choice.LINK)
        IssueKind.PATH_COLLISION -> setOf(Choice.KEEP)
        IssueKind.MEDIA_CONFLICT -> setOf(Choice.KEEP, Choice.OTHER)
    }
    if (request.choice !in allowed) badRequest("${request.choice.name.lowercase()} doesn't apply to a ${issue.kind.dbName}")

    val scope = RevisionScope(this, deviceId, "Resolved #$id: ${describe(request.choice)}")
    val head = headVersion(issue.assetId) ?: notFound("no asset ${issue.assetId}")
    when (request.choice) {
        Choice.KEEP -> Unit
        Choice.OTHER -> when (issue.kind) {
            IssueKind.MEDIA_CONFLICT -> {
                val current = issue.candidates[0].mediaHash
                val other = issue.candidates[1].mediaHash!!
                scope.write(head.copy(renditions = head.renditions.map { if (it == current) other else it }.distinct()))
            }
            else -> scope.write(head.withFields(head.fields() + chosen(issue, head) { 1 }))
        }
        Choice.MERGE -> {
            val picks = request.fields ?: badRequest("merge needs fields: {field: candidate index}")
            picks.values.forEach { if (it !in issue.candidates.indices) badRequest("no candidate $it") }
            scope.write(head.withFields(head.fields() + chosen(issue, head) { field -> picks[field] ?: issue.provisional }))
        }
        Choice.BOTH -> if (issue.kind == IssueKind.DUPLICATE) {
            // Split the imported file off as an asset of its own.
            val incoming = issue.candidates[1]
            val split = head.copy(assetId = UUID.randomUUID().toString(), path = incoming.path?.let { cleanOr(it, head.path) } ?: head.path)
            scope.write(split.withFields(split.fields().filterKeys { it in Fields.ALL && it != Fields.PICTURES } + incoming.fields))
        }
        Choice.LINK -> {
            // The new file becomes a second rendition of the existing track, and its own asset goes.
            val existing = headVersion(issue.candidates[0].assetId!!) ?: notFound("the existing track is gone")
            val newAsset = headVersion(issue.candidates[1].assetId!!) ?: notFound("the new track is gone")
            scope.write(newAsset.copy(deleted = true))
            scope.write(existing.copy(renditions = (existing.renditions + newAsset.renditions).distinct()))
            // Anything else waiting on the asset that just went is settled with it.
            db.exec("UPDATE issue SET resolved_rev = ? WHERE asset_id = ? AND resolved_rev IS NULL AND id != ?", scope.rev(), newAsset.assetId, id)
        }
    }
    scope.refreshAlbums()
    val rev = scope.rev()
    db.exec("UPDATE issue SET resolved_rev = ? WHERE id = ?", rev, id)
    scope.finish()
    ResolveResponse(rev)
}

/** The disputed fields taken from the candidate [pick] names for each; absent there means removed. */
private fun chosen(issue: IssueDto, head: Version, pick: (String) -> Int): Map<String, List<String>> {
    val fields = issue.fields.ifEmpty { issue.candidates.flatMap { it.fields.keys }.distinct() + tagNames(head) }
    return fields.associateWith { field -> issue.candidates[pick(field)].fields[field].orEmpty() }
}

private fun tagNames(head: Version) = head.tags.keys + Fields.PICTURES

private fun cleanOr(path: String, fallback: String) =
    runCatching { RepoPaths.clean(path.replace('\\', '/').substringBeforeLast('.')) }.getOrDefault(fallback)

private fun describe(choice: Choice) = when (choice) {
    Choice.KEEP -> "kept current"
    Choice.OTHER -> "used the other side"
    Choice.MERGE -> "merged field by field"
    Choice.BOTH -> "kept both"
    Choice.LINK -> "linked as renditions"
}

private fun Repository.withNames(issue: IssueDto) =
    issue.copy(deviceName = deviceName(issue.deviceId), path = headVersion(issue.assetId)?.path ?: "")

private fun ResultSet.toIssue() = IssueDto(
    id = getLong("id"),
    kind = issueKind(getString("kind")),
    assetId = getString("asset_id"),
    path = "",
    rev = getLong("rev"),
    deviceId = getString("device_id"),
    deviceName = "",
    fields = ApiJson.decodeFromString(Repository.stringList, getString("fields")),
    candidates = ApiJson.decodeFromString(Repository.candidateList, getString("candidates")),
    provisional = getInt("provisional"),
    resolvedRev = longOrNull("resolved_rev"),
)
