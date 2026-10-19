package com.tlisonbee.mediasync.sync

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** The revision a server change came in, for the review's headings. */
@Serializable
data class RevisionInfo(val rev: Long, val deviceName: String, val createdAt: Long, val message: String)

/**
 * A server change this device hasn't applied yet, waiting for approval. [asset] is the asset's
 * newest version; null means it left this device's folders (a retag or a subscription change).
 */
@Serializable
data class RemoteChange(val assetId: String, val rev: Long, val asset: AssetDto?, val revision: RevisionInfo? = null)

/** One file in a review, and what would happen to it. [id] is the asset (incoming) or the local path (outgoing). */
data class ReviewItem(val id: String, val path: String, val detail: String, val conflict: Boolean = false)

/**
 * Changes that are accepted or rejected together: one server revision, or one kind of local
 * change ("Retagged here"). [version] changes whenever the items do, so a decision made on an
 * older list can be refused.
 */
data class ReviewGroup(val key: String, val incoming: Boolean, val title: String, val subtitle: String?, val items: List<ReviewItem>, val rev: Long? = null) {
    val version: String = digest(items.joinToString("\n") { "${it.id}\t${it.detail}" })
}

/** What waits for approval: changes from the server, and changes made on this device. */
data class Review(val incoming: List<ReviewGroup>, val outgoing: List<ReviewGroup>, val warnings: List<String> = emptyList()) {
    val groups get() = incoming + outgoing
    val isEmpty get() = incoming.isEmpty() && outgoing.isEmpty()
    val incomingCount get() = incoming.sumOf { it.items.size }
    val outgoingCount get() = outgoing.sumOf { it.items.size }

    /** Changes when anything waiting does; a notification is shown again only then. */
    val version: String get() = digest(groups.joinToString("\n") { it.key + ":" + it.version })

    fun group(key: String) = groups.firstOrNull { it.key == key }

    /** "816 changes from the server and 3 from this phone". */
    fun summary(device: String = "this device"): String {
        val parts = listOfNotNull(
            incomingCount.takeIf { it > 0 }?.let { "${count(it, "change")} from the server" },
            outgoingCount.takeIf { it > 0 }?.let { "${count(it, "change")} made on $device" },
        )
        return parts.joinToString(" and ").ifEmpty { "Nothing waiting" }
    }

    /** Every group accepted. */
    fun acceptAll() = Decisions(
        acceptIncoming = incoming.flatMap { g -> g.items.map { it.id } }.toSet(),
        acceptOutgoing = outgoing.flatMap { g -> g.items.map { it.id } }.toSet(),
    )

    /** The decision a group's button stands for. */
    fun decide(group: ReviewGroup, action: ReviewAction): Decisions {
        val ids = group.items.map { it.id }.toSet()
        return when (action) {
            ReviewAction.ACCEPT -> if (group.incoming) Decisions(acceptIncoming = ids) else Decisions(acceptOutgoing = ids)
            ReviewAction.UNDO -> if (group.incoming) Decisions() else Decisions(undo = ids)
            ReviewAction.REVERT -> Decisions(revert = setOfNotNull(group.rev))
        }
    }

    companion object {
        val EMPTY = Review(emptyList(), emptyList())
    }
}

enum class ReviewAction {
    /** Apply it: server changes are written into the files, local ones are pushed. */
    ACCEPT,

    /** Local changes only: put the files back as the server has them (a new file stays off the server). */
    UNDO,

    /** Server changes only: revert that revision on the server, for every device. */
    REVERT,
}

/** What to apply: incoming changes by asset, outgoing ones by local path, and revisions to revert. */
@Serializable
data class Decisions(
    val acceptIncoming: Set<String> = emptySet(),
    val acceptOutgoing: Set<String> = emptySet(),
    val undo: Set<String> = emptySet(),
    val revert: Set<Long> = emptySet(),
) {
    val isEmpty get() = acceptIncoming.isEmpty() && acceptOutgoing.isEmpty() && undo.isEmpty() && revert.isEmpty()
}

/** Builds a [Review] from the index alone: what the last check staged and what the last scan found. */
class ReviewBuilder(private val config: ClientConfig, private val index: FileIndex, private val pathKey: (FolderConfig, String) -> String) {
    fun build(warnings: List<String> = emptyList()): Review {
        val rows = index.all()
        val byAsset = rows.filter { it.assetId != null && it.ignored == null }.associateBy { it.assetId }
        val staged = index.remote()

        val incoming = staged.groupBy { if (it.asset == null) null else it.rev }.map { (rev, changes) ->
            val info = changes.firstNotNullOfOrNull { it.revision }
            val items = changes.mapNotNull { change -> incomingItem(change, byAsset[change.assetId]) }.sortedBy { it.path.lowercase() }
            ReviewGroup(
                key = if (rev == null) "in:left" else "in:r$rev",
                incoming = true,
                title = when {
                    rev == null -> "No longer in this device's folders"
                    info != null -> "r$rev · ${info.message}"
                    else -> "r$rev"
                },
                subtitle = info?.let { "${it.deviceName} · ${formatTime(it.createdAt)}" },
                items = items,
                rev = rev,
            )
        }.filter { it.items.isNotEmpty() }.sortedBy { it.rev ?: Long.MAX_VALUE }

        // By kind of change and by folder, so a folder's changes (a new folder's thousands of
        // files, say) can be accepted or undone apart from the rest. Music keeps the plain keys.
        val stagedIds = staged.map { it.assetId }.toSet()
        val folders = config.folders.map { it.prefix }
        val outgoing = rows.filter { row -> row.ignored == null && pending(row) }
            .groupBy { kindOf(it) to it.prefix }
            .map { (group, list) ->
                val (kind, prefix) = group
                ReviewGroup(
                    key = "out:${kind.name.lowercase()}" + if (prefix == RepoPaths.MUSIC) "" else ":$prefix",
                    incoming = false,
                    title = if (folders.size > 1) "${kind.title}, in $prefix" else kind.title,
                    subtitle = null,
                    items = list.map { outgoingItem(it, kind, it.assetId in stagedIds) }.sortedBy { it.path.lowercase() },
                ) to group
            }
            .sortedWith(compareBy({ folders.indexOf(it.second.second) }, { it.second.first.ordinal }))
            .map { it.first }
        return Review(incoming, outgoing, warnings)
    }

    /** A row with a change to push: the same rule as the sync's push. */
    fun pending(row: Row): Boolean {
        val folder = folderOf(row) ?: return false
        if (folder.mode == SyncMode.RECEIVE_ONLY) return false
        return row.changed || movedOutsideMusic(folder, row)
    }

    private fun movedOutsideMusic(folder: FolderConfig, row: Row): Boolean {
        val base = row.base ?: return false
        if (row.missing || RepoPaths.followsFields(base.kind, base.path, base.category)) return false
        return pathKey(folder, LocalFiles.repoPath(folder, row.file)) != pathKey(folder, base.path)
    }

    private fun folderOf(row: Row) = config.folders.firstOrNull { it.prefix == row.prefix }

    private fun incomingItem(change: RemoteChange, row: Row?): ReviewItem? {
        val asset = change.asset
        val conflict = row != null && pending(row)
        if (asset == null) {
            val path = row?.let { shown(it) } ?: return null
            return ReviewItem(change.assetId, path, "removed from this device", conflict)
        }
        val folder = config.folderFor(asset.path)
        // The name the file has here now; a rename shows in the detail.
        val path = row?.takeUnless { it.missing }?.let { shown(it) } ?: folder?.let { asset.path.removePrefix(it.prefix) } ?: asset.path
        val base = row?.base
        val detail = when {
            asset.deleted -> if (row == null) return null else "deleted"
            row == null || row.missing || base == null -> "new file (${asset.renditions.firstOrNull()?.format ?: "?"})"
            else -> differences(base, asset, folder).ifEmpty { listOf("updated") }.joinToString("; ")
        }
        return ReviewItem(change.assetId, path, detail, conflict)
    }

    private fun differences(old: AssetDto, new: AssetDto, folder: FolderConfig?): List<String> = buildList {
        val fields = (old.tags.keys + new.tags.keys).filter { old.tags[it] != new.tags[it] }
        fields.take(3).forEach { add("$it: ${old.tags[it].show()} → ${new.tags[it].show()}") }
        if (fields.size > 3) add("${fields.size - 3} more fields")
        if (LocalFiles.coverOf(old)?.blob != LocalFiles.coverOf(new)?.blob) add("cover art")
        val samePath = folder?.let { pathKey(it, old.path) == pathKey(it, new.path) } ?: (old.path == new.path)
        if (!samePath) add("moves to ${folder?.let { new.path.removePrefix(it.prefix) } ?: new.path}")
        val before = old.renditions.map { it.mediaHash }
        val after = new.renditions.map { it.mediaHash }
        when {
            before == after -> Unit
            after.containsAll(before) -> add("another format linked (this file stays as it is)")
            else -> add("audio replaced")
        }
    }

    private fun outgoingItem(row: Row, kind: LocalKind, conflict: Boolean): ReviewItem {
        val base = row.base
        val detail = when (kind) {
            LocalKind.NEW -> "new file"
            LocalKind.DELETED -> "deleted here"
            LocalKind.REPLACED -> "audio replaced"
            LocalKind.MOVED -> "moved from ${base?.path?.removePrefix(row.prefix)}"
            LocalKind.RETAGGED -> {
                val seen = row.seenTags.orEmpty()
                val fields = (seen.keys + row.tags.keys).filter { seen[it] != row.tags[it] }
                (fields.take(3).map { "$it: ${seen[it].show()} → ${row.tags[it].show()}" } +
                    listOfNotNull(if (fields.size > 3) "${fields.size - 3} more fields" else null, if (row.cover != row.seenCover) "cover art" else null))
                    .ifEmpty { listOf("tags") }.joinToString("; ")
            }
        }
        return ReviewItem(row.path, shown(row), detail, conflict)
    }

    private fun shown(row: Row): String {
        val folder = folderOf(row) ?: return row.path
        return folder.root.relativize(row.file).joinToString("/")
    }

    private fun kindOf(row: Row): LocalKind = when {
        row.missing -> LocalKind.DELETED
        row.assetId == null -> LocalKind.NEW
        row.media != row.seenMedia -> LocalKind.REPLACED
        row.tags != row.seenTags || row.cover != row.seenCover -> LocalKind.RETAGGED
        else -> LocalKind.MOVED
    }

    private enum class LocalKind(val title: String) {
        NEW("New files here"),
        RETAGGED("Retagged here"),
        REPLACED("Audio replaced here"),
        MOVED("Moved here"),
        DELETED("Deleted here"),
    }
}

private fun List<String>?.show() = this?.joinToString("; ") ?: "(none)"

private fun count(n: Int, noun: String) = "%,d %s".format(n, if (n == 1) noun else noun + "s")

private fun formatTime(millis: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

private fun digest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

/**
 * Whether syncs wait for approval, and how often to check meanwhile. Kept in the index, which
 * both clients can always write (the desktop service's config folder is read-only to it).
 */
class ReviewSettings(private val index: FileIndex) {
    var enabled: Boolean
        get() = index.state(ENABLED) != "off"
        set(value) = index.setState(ENABLED, if (value) "on" else "off")

    var checkMinutes: Int
        get() = index.state(MINUTES)?.toIntOrNull()?.takeIf { it in CHOICES } ?: DEFAULT_MINUTES
        set(value) = index.setState(MINUTES, value.coerceIn(CHOICES.first(), CHOICES.last()).toString())

    companion object {
        private const val ENABLED = "review"
        private const val MINUTES = "checkMinutes"
        const val DEFAULT_MINUTES = 60

        /** Android's shortest periodic job is 15 minutes. */
        val CHOICES = listOf(15, 30, 60, 120, 360, 720, 1440)

        fun describe(minutes: Int) = when {
            minutes < 60 -> "every $minutes minutes"
            minutes == 60 -> "every hour"
            minutes < 1440 -> "every ${minutes / 60} hours"
            else -> "once a day"
        }
    }
}
