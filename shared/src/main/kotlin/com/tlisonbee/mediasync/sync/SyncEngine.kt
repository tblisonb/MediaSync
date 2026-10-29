package com.tlisonbee.mediasync.sync

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceCleanup
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.api.PushResult
import com.tlisonbee.mediasync.api.RenditionDto
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.Picture
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

enum class SyncStage { SCANNING, PULLING, DOWNLOADING, UPLOADING }

/** Hooks for a client's UI: progress, and the files a sync wrote (Android tells MediaStore about those). */
interface SyncListener {
    /**
     * [done] and [total] count files; -1 means unchanged. [bytes] counts the stage's transfer so
     * far, out of [totalBytes] planned (-1: unchanged or unknown).
     */
    fun progress(stage: SyncStage, done: Int, total: Int, bytes: Long = 0, totalBytes: Long = -1) {}

    fun filesChanged(paths: List<Path>) {}

    companion object {
        val NONE = object : SyncListener {}
    }
}

/** What one sync did, for the log, the CLI and notifications. */
class SyncReport {
    var filesRead = 0
    var pushed = 0
    var rev: Long? = null
    var head = 0L
    var uploadedFiles = 0
    var uploadedBytes = 0L

    /** Files planned for upload that the server turned out to have: done without sending. */
    var skippedBytes = 0L
    var downloadedFiles = 0
    var downloadedBytes = 0L
    var retagged = 0
    var renamed = 0
    var deleted = 0

    /** Clutter deleted because the server asked (desktop.ini, Thumbs.db and the like). */
    var clutterDeleted = 0

    /** Local changes in receive-only folders, which are never pushed. */
    var notPushed = 0

    /** Some files were still being written; another sync should follow shortly. */
    var unsettled = false
    val warnings = mutableListOf<String>()

    fun warn(message: String) {
        warnings += message
    }

    fun summary(): String = buildList {
        if (pushed > 0) add("pushed $pushed${rev?.let { " as r$it" } ?: ""}")
        if (uploadedFiles > 0) add("uploaded $uploadedFiles (${bytes(uploadedBytes)})")
        if (downloadedFiles > 0) add("downloaded $downloadedFiles (${bytes(downloadedBytes)})")
        if (retagged > 0) add("retagged $retagged")
        if (renamed > 0) add("renamed $renamed")
        if (deleted > 0) add("deleted $deleted")
        if (clutterDeleted > 0) add("deleted $clutterDeleted leftover ${if (clutterDeleted == 1) "file" else "files"}")
        if (warnings.isNotEmpty()) add("${warnings.size} warning(s)")
    }.ifEmpty { listOf("nothing to do") }.joinToString(", ") + " (at r${maxOf(head, rev ?: 0)})"
}

/**
 * One sync cycle: scan the folders, pull what changed on the server, upload new media, push local
 * changes, and bring each file in line with the server's answer. The server does the merging; this
 * side only works out what changed locally and writes the results into files.
 */
class SyncEngine(
    private val config: ClientConfig,
    private val api: ApiClient,
    private val index: FileIndex,
    /** Cache for cover art, which many tracks share. */
    private val picturesDir: Path,
    private val log: (String) -> Unit = ::println,
    private val clock: () -> Long = System::currentTimeMillis,
    private val listener: SyncListener = SyncListener.NONE,
    /** Overrides the check of whether a folder's file system ignores case; for tests. */
    private val ignoresCase: Boolean? = null,
) {
    private val knownMedia = HashSet<String>()
    private val caseless = HashMap<String, Boolean>()
    private val reviewer = ReviewBuilder(config, index, ::pathKey)
    private var policy = TagPolicy()
    private var cleanup = DeviceCleanup()

    /** Files this sync wrote, moved or deleted, both old and new paths. */
    private val touched = LinkedHashSet<Path>()

    fun sync(allowDeletes: Boolean = false): SyncReport {
        val report = SyncReport()
        touched.clear()
        try {
            refreshSettings()
            scan(report, allowDeletes)
            listener.progress(SyncStage.PULLING, 0, 0)
            pull(report, allowDeletes)
            push(report)
            clean(report)
            index.setState(LAST_SUCCESS, clock().toString())
        } finally {
            if (touched.isNotEmpty()) listener.filesChanged(touched.toList())
        }
        return report
    }

    // ---- Review: look first, then apply only what was approved ----

    /**
     * Scans the folders and stages what changed on the server, without writing a file or pushing
     * anything. Returns what waits for approval.
     */
    fun check(allowDeletes: Boolean = false, full: Boolean = false): Review {
        val report = SyncReport()
        refreshSettings()
        scan(report, allowDeletes)
        // From the start: everything the server holds is compared with the files again, and
        // whatever differs (a download that once failed, say) is staged.
        if (full) index.setState(SINCE, null)
        listener.progress(SyncStage.PULLING, 0, 0)
        fetch(report)
        index.setState(CHECK_WARNINGS, report.warnings.joinToString("\n").ifEmpty { null })
        index.setState(LAST_CHECK, clock().toString())
        return review()
    }

    /** What waits for approval, as of the last check; reads only the index. */
    fun review(): Review = reviewer.build(checkWarnings(index))

    /**
     * Applies what was approved: reverts on the server, pushes the accepted local changes, undoes
     * the rejected ones, then writes the accepted server changes into the files. Everything else
     * stays waiting.
     */
    fun apply(decisions: Decisions): SyncReport {
        val report = SyncReport()
        touched.clear()
        try {
            refreshSettings()
            scan(report, allowDeletes = false)
            for (rev in decisions.revert) {
                api.revert(RevertRequest(rev = rev))
                // The revert comes back as a revision of its own; the next fetch stages what it changes here.
                index.remote().filter { it.rev == rev && it.asset != null }.forEach { index.deleteRemote(it.assetId) }
            }
            push(report, only = decisions.acceptOutgoing)
            undo(decisions.undo, report)
            val removals = LinkedHashMap<String, Row>()
            applyStaged(decisions.acceptIncoming, report, removals)
            removals.values.filter { !it.changed }.forEach { removeLocal(it, report) }
            clean(report)
            fetch(report)
            index.setState(LAST_SUCCESS, clock().toString())
        } finally {
            if (touched.isNotEmpty()) listener.filesChanged(touched.toList())
        }
        return report
    }

    /** Puts files back as the server has them; a file the server never had stays off it. */
    private fun undo(paths: Set<String>, report: SyncReport) {
        for (path in paths) {
            val row = index.get(Path.of(path)) ?: continue
            val base = row.base
            try {
                when {
                    row.assetId == null -> index.put(row.copy(ignored = "kept off the server"))
                    base == null || base.deleted -> continue
                    row.missing -> {
                        index.delete(row.path)
                        materialize(folderOf(row), base, null, null, report)
                    }
                    else -> materialize(folderOf(row), base, row, null, report)
                }
            } catch (e: IOException) {
                report.warn("$path: ${e.message}")
            }
        }
    }

    /** Stages what changed on the server since the last look, writing no file. */
    private fun fetch(report: SyncReport) {
        val since = index.state(SINCE)?.toLong() ?: 0
        var after: String? = null
        var reset = false
        val listed = HashSet<String>()
        val revisions = HashMap<Long, RevisionInfo?>()
        do {
            val page = api.changes(since, after)
            reset = reset || page.reset
            for (asset in page.assets) {
                listed += asset.assetId
                stage(asset, revisions)
            }
            for (id in page.left) {
                if (index.byAsset(id).any { it.ignored == null }) index.putRemote(RemoteChange(id, page.head, null))
            }
            report.head = page.head
            after = page.next
        } while (page.more)
        if (reset) {
            index.all().filter { it.assetId != null && it.assetId !in listed && it.ignored == null && receives(it.prefix) }
                .forEach { index.putRemote(RemoteChange(it.assetId!!, report.head, null)) }
        }
        index.setState(SINCE, report.head.toString())
    }

    private fun stage(asset: AssetDto, revisions: MutableMap<Long, RevisionInfo?>) {
        val folder = config.folderFor(asset.path)
        val row = index.byAsset(asset.assetId).firstOrNull { it.ignored == null }
        if (folder == null || (row != null && row.base?.rev == asset.rev && !row.missing) || (row == null && asset.deleted)) {
            index.deleteRemote(asset.assetId)
            return
        }
        // Nothing on disk would change (a revert of something never applied here, a case-only
        // move where case doesn't count): only the version this file is based on moves on.
        val base = row?.base
        if (row != null && base != null && !row.missing && !asset.deleted && sameOnDisk(folder, base, asset)) {
            index.put(row.copy(base = asset))
            index.deleteRemote(asset.assetId)
            return
        }
        if (folder.mode == SyncMode.SEND_ONLY && !asset.deleted) return
        val info = revisions.getOrPut(asset.rev) {
            runCatching { api.revisions(before = asset.rev + 1, limit = 1).revisions.firstOrNull { it.rev == asset.rev } }.getOrNull()
                ?.let { RevisionInfo(it.rev, it.deviceName, it.createdAt, it.message) }
        }
        index.putRemote(RemoteChange(asset.assetId, asset.rev, asset, info))
    }

    private fun sameOnDisk(folder: FolderConfig, old: AssetDto, new: AssetDto) =
        old.tags == new.tags && old.pictures == new.pictures && old.deleted == new.deleted &&
            old.renditions.map { it.mediaHash } == new.renditions.map { it.mediaHash } &&
            pathKey(folder, old.path) == pathKey(folder, new.path)

    /**
     * Writes staged server changes into the files: [only] these assets, or all of them (a sync
     * without review). A file changed here too is merged by pushing it instead.
     */
    private fun applyStaged(only: Set<String>?, report: SyncReport, removals: MutableMap<String, Row>) {
        val changes = index.remote().filter { only == null || it.assetId in only }
        // What may be downloaded: assets this device has none of the media of.
        val planned = changes.sumOf { change ->
            val asset = change.asset ?: return@sumOf 0L
            val row = index.byAsset(change.assetId).firstOrNull { it.ignored == null }
            if (asset.deleted || (row != null && !row.missing && row.media in asset.renditions.map { it.mediaHash })) 0L
            else asset.renditions.firstOrNull()?.size ?: 0L
        }
        for ((i, change) in changes.withIndex()) {
            if (changes.size > 1) listener.progress(SyncStage.DOWNLOADING, i, changes.size, report.downloadedBytes, planned)
            val row = index.byAsset(change.assetId).firstOrNull { it.ignored == null }
            val asset = change.asset
            if (row != null && asset != null && (row.base?.rev ?: 0) >= change.rev && !row.missing) {
                index.deleteRemote(change.assetId) // already caught up, e.g. by pushing
                continue
            }
            if (row != null && row.changed && modeOf(row.prefix) != SyncMode.RECEIVE_ONLY) {
                if (only == null) index.deleteRemote(change.assetId) else report.warn("${row.path} changed here too; accept this device's change to merge the two")
                continue
            }
            try {
                if (asset == null) row?.let { removals[it.path] = it } else applyRemote(asset, report, removals)
                index.deleteRemote(change.assetId)
            } catch (e: IOException) {
                report.warn("${asset?.path ?: change.assetId}: ${e.message}")
            }
        }
    }

    private fun refreshSettings() {
        // Before anything else: a server older than this app needs stops here, with a message saying so.
        api.requireCompatibleServer()
        policy = api.policy()
        cleanup = api.cleanup()
    }

    // ---- Scan: what changed on disk ----

    private fun scan(report: SyncReport, allowDeletes: Boolean) {
        for (folder in config.folders) {
            val root = folder.root
            if (!Files.isDirectory(root)) {
                // Possibly an unmounted disk: never read that as "everything was deleted".
                report.warn("$root isn't there, so it was skipped")
                continue
            }
            val known = index.inFolder(folder.prefix).associateBy { pathKey(folder, it.path) }
            val seen = HashSet<String>()
            val fresh = mutableListOf<Row>()
            val files = walk(root, report)
            for ((i, file) in files.withIndex()) {
                if (i % 50 == 0) listener.progress(SyncStage.SCANNING, i, files.size)
                val path = file.toString()
                seen += pathKey(folder, path)
                val (size, mtime) = try {
                    LocalFiles.stat(file)
                } catch (e: IOException) {
                    continue
                }
                // Where case doesn't count, a folder keeps the case it was made with: "IRON MAIDEN/x"
                // may be on disk as "Iron Maiden/x". That is the same file, not a move.
                val row = known[pathKey(folder, path)]?.let { if (it.path == path) it else it.copy(path = path).also { moved -> index.replace(it.path, moved) } }
                // A track this client couldn't read (an ID3v1-only MP3, before that was fixed) is
                // read again each time, in case this version can.
                val unread = row?.kind == AssetKind.OTHER && LocalFiles.isTagged(row.extension)
                if (row != null && !row.missing && row.size == size && row.mtime == mtime && !unread) continue
                if (clock() - mtime / 1000 < SETTLE_MS) {
                    report.unsettled = true // still being written; it's picked up next time
                    continue
                }
                val state = try {
                    LocalFiles.read(file)
                } catch (e: IOException) {
                    report.warn("$file: ${e.message}")
                    continue
                }
                report.filesRead++
                if (report.filesRead % 500 == 0) log("Read ${report.filesRead} files…")
                val updated = (row ?: Row(path, folder.prefix, size, mtime, state.kind, state.media, state.tags, null, null, null)).copy(
                    size = size, mtime = mtime, kind = state.kind, media = state.media, tags = state.tags,
                    cover = state.coverHash, coverMime = state.cover?.mimeType, durationMs = state.durationMs, missing = false,
                    extras = hasExtras(state),
                )
                index.put(updated)
                if (row == null) fresh += updated
            }

            // Files that vanished. A vanished file whose media turned up elsewhere was moved.
            val gone = known.values.filter { pathKey(folder, it.path) !in seen && !it.missing }.toMutableList()
            for (row in fresh) {
                val moved = gone.firstOrNull { it.assetId != null && it.media == row.media } ?: continue
                gone -= moved
                val carried = row.copy(seenMedia = moved.seenMedia, seenTags = moved.seenTags, seenCover = moved.seenCover, assetId = moved.assetId, base = moved.base, ignored = moved.ignored)
                index.replace(moved.path, carried)
                moveBackIfMusic(folder, carried, report)
            }
            // Kept in the library when deleted here: a file that's gone is only forgotten.
            if (folder.keepsDeleted) {
                gone.forEach { index.delete(it.path) }
                continue
            }
            gone.filter { it.assetId == null || it.ignored != null }.forEach { index.delete(it.path) }
            val deletions = gone.filter { it.assetId != null && it.ignored == null }
            if (!allowDeletes && tooMany(deletions.size, known.size)) {
                report.warn(
                    "${deletions.size} files vanished from $root, so their deletion wasn't synced. " +
                        "If you meant it, run: mediasync-desktop sync --allow-deletes",
                )
            } else {
                deletions.forEach { index.put(it.copy(missing = true)) }
            }
        }
    }

    /**
     * Music paths follow the tags, so a track renamed by hand goes back where its tags put it. A
     * send-only folder keeps its own names (a phone's camera folder isn't filed by date).
     */
    private fun moveBackIfMusic(folder: FolderConfig, row: Row, report: SyncReport) {
        val base = row.base ?: return
        if (folder.mode == SyncMode.SEND_ONLY) return
        if (row.ignored != null || !RepoPaths.followsFields(base.kind, base.path, base.category)) return
        val expected = LocalFiles.localPath(folder, base.path, row.extension)
        if (expected == row.file || Files.exists(expected)) return
        LocalFiles.move(row.file, expected)
        touched.add(row.file)
        touched.add(expected)
        LocalFiles.pruneEmpty(row.file.parent, folder.root)
        val (size, mtime) = LocalFiles.stat(expected)
        index.replace(row.path, row.copy(path = expected.toString(), size = size, mtime = mtime))
        log("Moved ${row.path} back to $expected: music files are named by their tags")
        report.renamed++
    }

    /** Whether a file holds tags the policy doesn't keep, which the next write removes. */
    private fun hasExtras(state: FileState): Boolean {
        if (state.kind != AssetKind.AUDIO) return false
        val allowed = policy.fields[AssetKind.AUDIO].orEmpty()
        return state.hasExtras || state.tags.keys.any { it !in allowed } ||
            (state.cover != null && TagPolicy.FRONT_COVER !in policy.pictures[AssetKind.AUDIO].orEmpty())
    }

    /**
     * The files to sync under [root]. Clutter is deleted on the way when the server asks for that,
     * which is the one change even a check makes: those files are never synced, so there is
     * nothing to review.
     */
    private fun walk(root: Path, report: SyncReport): List<Path> {
        val files = mutableListOf<Path>()
        val clutter = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root && LocalFiles.skipped(dir.fileName.toString())) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    if (!LocalFiles.skipped(name)) files.add(file) else if (cleanup.deleteClutter && LocalFiles.isClutter(name)) clutter.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        // After the walk: deleting a folder's last file also removes the folder.
        var deleted = 0
        for (file in clutter) {
            try {
                LocalFiles.delete(file, root)
                touched.add(file)
                deleted++
            } catch (e: IOException) {
                report.warn("$file couldn't be deleted: ${e.message}")
            }
        }
        report.clutterDeleted += deleted
        if (deleted > 0) log("Deleted $deleted leftover files under $root (desktop.ini, Thumbs.db and the like)")
        return files
    }

    // ---- Pull: what changed on the server ----

    private fun pull(report: SyncReport, allowDeletes: Boolean) {
        val since = index.state(SINCE)?.toLong() ?: 0
        var after: String? = null
        var reset = false
        val listed = HashSet<String>()
        val removals = LinkedHashMap<String, Row>()
        // Whatever an earlier review left waiting goes first, like any other change.
        applyStaged(null, report, removals)
        do {
            val page = api.changes(since, after)
            reset = reset || page.reset
            for (asset in page.assets) {
                listed += asset.assetId
                try {
                    applyRemote(asset, report, removals)
                } catch (e: IOException) {
                    report.warn("${asset.path}: ${e.message}")
                    // `since` moves past it, so keep it staged: the next sync tries again.
                    index.putRemote(RemoteChange(asset.assetId, asset.rev, asset))
                }
            }
            for (id in page.left) index.byAsset(id).forEach { removals[it.path] = it }
            report.head = page.head
            after = page.next
        } while (page.more)

        if (reset) {
            // A full listing: anything held here that it doesn't mention left this device's scope.
            index.all().filter { it.assetId != null && it.assetId !in listed && it.ignored == null && receives(it.prefix) }
                .forEach { removals[it.path] = it }
        }
        // Rows with local changes are pushed first; the push result decides their fate.
        val doomed = removals.values.filter { !it.changed }
        if (!allowDeletes && tooMany(doomed.size, index.all().size)) {
            report.warn(
                "The server asks to delete ${doomed.size} local files; nothing was deleted. " +
                    "If that's expected, run: mediasync-desktop sync --allow-deletes",
            )
            return // leave `since` where it was, so the deletions come again
        }
        doomed.forEach { removeLocal(it, report) }
        index.setState(SINCE, report.head.toString())
    }

    private fun applyRemote(asset: AssetDto, report: SyncReport, removals: MutableMap<String, Row>) {
        val folder = config.folderFor(asset.path) ?: return
        val row = index.byAsset(asset.assetId).firstOrNull { it.ignored == null }
        if (row != null && row.base?.rev == asset.rev && !row.missing) return
        // A file with local changes keeps them; the push merges both sides on the server.
        if (row != null && row.changed && folder.mode != SyncMode.RECEIVE_ONLY) return
        if (asset.deleted) {
            if (row != null) removals[row.path] = row
            return
        }
        if (folder.mode == SyncMode.SEND_ONLY) return
        val onFile = if (row != null && !row.changed) row.base?.let { it.tags to LocalFiles.coverOf(it)?.blob } else null
        materialize(folder, asset, row, onFile, report)
    }

    /**
     * Brings the local file of [asset] in line with it: rewrite tags, rename, or download. [onFile]
     * is what the file's tags already represent (tags, cover blob); null means rewrite them.
     */
    private fun materialize(folder: FolderConfig, asset: AssetDto, row: Row?, onFile: Pair<Tags, String?>?, report: SyncReport) {
        // An unmounted drive leaves an empty mount point: never fill the disk underneath it.
        if (!Files.isDirectory(folder.root)) throw IOException("${folder.root} isn't there (a drive not mounted?); it's tried again later")
        val hashes = asset.renditions.map { it.mediaHash }
        if (row != null && row.media in hashes && Files.exists(row.file)) {
            if (LocalFiles.stat(row.file) != (row.size to row.mtime)) {
                report.warn("${row.path} changed while syncing; it's merged next time")
                return
            }
            var file = row.file
            val coverBlob = LocalFiles.coverOf(asset)?.blob
            val retag = asset.kind == AssetKind.AUDIO && row.kind == AssetKind.AUDIO &&
                (onFile == null || row.extras || LocalFiles.writable(onFile.first) != LocalFiles.writable(asset.tags) || onFile.second != coverBlob)
            if (retag) {
                LocalFiles.writeTags(file, asset.tags, cover(asset))
                touched.add(file)
                report.retagged++
            }
            val target = LocalFiles.localPath(folder, asset.path, row.extension)
            if (!samePath(folder, target, file)) {
                makeRoom(folder, target)
                LocalFiles.move(file, target)
                touched.add(file)
                touched.add(target)
                LocalFiles.pruneEmpty(file.parent, folder.root)
                report.renamed++
                file = target
            }
            val (size, mtime) = LocalFiles.stat(file)
            var updated = row.copy(path = file.toString(), size = size, mtime = mtime)
            if (retag) {
                val read = AudioTags.inspect(file)
                val front = read.tags.frontCover
                updated = updated.copy(
                    tags = read.tags.fields, cover = front?.let { sha256Hex(it.data) }, coverMime = front?.mimeType,
                    extras = read.hasExtras,
                )
            }
            index.replace(row.path, updated.synced(asset))
            return
        }

        // This device has none of the asset's renditions (or the media was replaced): download one.
        val rendition = pick(asset, folder, row) ?: return report.warn("${asset.path} has no renditions")
        val target = LocalFiles.localPath(folder, asset.path, rendition.format)
        if (row == null || !samePath(folder, target, row.file)) makeRoom(folder, target)
        if (Files.exists(target) && (row == null || !samePath(folder, target, row.file))) {
            report.warn("$target already exists and isn't MediaSync's, so ${asset.path} wasn't downloaded")
            return
        }
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(".mediasync-${rendition.blob.take(16)}.${rendition.format}")
        report.downloadedBytes += api.download(rendition.blob, tmp) { listener.progress(SyncStage.DOWNLOADING, -1, -1, report.downloadedBytes + it) }
        report.downloadedFiles++
        if (asset.kind == AssetKind.AUDIO && LocalFiles.isTagged(rendition.format)) LocalFiles.writeTags(tmp, asset.tags, cover(asset))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        touched.add(target)
        if (row != null && row.file != target) {
            LocalFiles.delete(row.file, folder.root)
            touched.add(row.file)
        }
        val state = LocalFiles.read(target)
        val (size, mtime) = LocalFiles.stat(target)
        val fresh = Row(
            target.toString(), folder.prefix, size, mtime, state.kind, state.media, state.tags,
            state.coverHash, state.cover?.mimeType, state.durationMs, extras = hasExtras(state),
        ).synced(asset)
        index.transaction {
            row?.let { index.delete(it.path) }
            index.put(fresh)
        }
    }

    /** The rendition to fetch: the format this device already uses, else its preference, else the original. */
    private fun pick(asset: AssetDto, folder: FolderConfig, row: Row?): RenditionDto? {
        val renditions = asset.renditions
        row?.extension?.lowercase()?.let { ext -> renditions.firstOrNull { it.format == ext }?.let { return it } }
        folder.prefer.forEach { format -> renditions.firstOrNull { it.format == format }?.let { return it } }
        return renditions.firstOrNull()
    }

    private fun removeLocal(row: Row, report: SyncReport) {
        val folder = config.folders.firstOrNull { it.prefix == row.prefix }
        if (Files.exists(row.file)) {
            if (LocalFiles.stat(row.file) != (row.size to row.mtime)) {
                report.warn("${row.path} was deleted on the server but changed here; it was kept")
                return
            }
            LocalFiles.delete(row.file, folder?.root ?: row.file.parent)
            touched.add(row.file)
        }
        index.delete(row.path)
        report.deleted++
    }

    // ---- Push: local changes up ----

    /** Pushes local changes: all of them, or [only] the files at those paths. */
    private fun push(report: SyncReport, only: Set<String>? = null) {
        val all = index.all()
        report.notPushed = all.count { it.changed && it.ignored == null && modeOf(it.prefix) == SyncMode.RECEIVE_ONLY }
        val pending = all.filter { it.ignored == null && reviewer.pending(it) && (only == null || it.path in only) }
        if (pending.isEmpty()) return
        log("Pushing ${pending.size} local change(s)…")
        val planned = pending.filter(::needsUpload).sumOf { it.size }
        var done = 0
        for (batch in pending.chunked(BATCH)) {
            val prepared = batch.mapIndexedNotNull { i, row ->
                listener.progress(SyncStage.UPLOADING, done + i, pending.size, report.uploadedBytes + report.skippedBytes, planned)
                try {
                    row to prepare(row, report)
                } catch (e: IOException) {
                    report.warn("${row.path}: ${e.message}")
                    null
                }
            }
            if (prepared.isEmpty()) continue
            val response = api.push(PushRequest(changes = prepared.map { it.second }))
            report.rev = response.rev
            report.pushed += prepared.size
            response.results.zip(prepared).forEach { (result, pair) ->
                try {
                    applyResult(pair.first, pair.second, result, report)
                } catch (e: IOException) {
                    report.warn("${pair.first.path}: ${e.message}")
                }
            }
            done += batch.size
            if (pending.size > BATCH) log("Pushed $done of ${pending.size} (uploaded ${bytes(report.uploadedBytes)})")
        }
    }

    /** The change to push for [row], after uploading whatever media and cover the server lacks. */
    private fun prepare(row: Row, report: SyncReport): PushChange {
        val folder = folderOf(row)
        val base = row.base
        if (row.missing) {
            return PushChange(row.assetId, base!!.rev, base.path, base.kind, deleted = true)
        }
        if (LocalFiles.stat(row.file) != (row.size to row.mtime)) throw IOException("changed while syncing; it's pushed next time")
        uploadMedia(row, report)

        val coverChanged = row.assetId == null || row.cover != row.seenCover
        val cover = row.cover?.let { PictureRef(TagPolicy.FRONT_COVER, it, row.coverMime ?: "image/jpeg") }
        if (cover != null && coverChanged) uploadCover(row, cover)

        if (base == null) {
            val raw = if (row.kind == AssetKind.AUDIO) runCatching { AudioTags.inspect(row.file).raw }.getOrNull() else null
            return PushChange(
                path = LocalFiles.repoPath(folder, row.file),
                kind = row.kind,
                mediaHash = row.media,
                durationMs = row.durationMs,
                tags = row.tags,
                pictures = listOfNotNull(cover),
                rawTags = raw,
                sourcePath = row.path,
            )
        }
        // Only the fields edited here count: the rest stays as the server had it, so a format that
        // can't hold a value exactly (a full date in an old ID3 tag) never overwrites it.
        val seen = row.seenTags.orEmpty()
        val tags = base.tags.toMutableMap()
        for (field in seen.keys + row.tags.keys) {
            if (seen[field] == row.tags[field]) continue
            val value = row.tags[field]
            if (value == null) tags.remove(field) else tags[field] = value
        }
        val pictures = if (coverChanged) base.pictures.filter { it.type != TagPolicy.FRONT_COVER } + listOfNotNull(cover) else base.pictures
        val music = RepoPaths.followsFields(base.kind, base.path, base.category)
        // A plain file on the server that this device reads as a track: saying so makes it one.
        val kind = if (base.kind == AssetKind.OTHER && row.kind == AssetKind.AUDIO) AssetKind.AUDIO else base.kind
        return PushChange(
            assetId = row.assetId,
            baseRev = base.rev,
            path = if (music) base.path else LocalFiles.repoPath(folder, row.file),
            kind = kind,
            mediaHash = row.media,
            baseMediaHash = row.seenMedia,
            durationMs = row.durationMs,
            tags = tags,
            pictures = pictures,
        )
    }

    /** A new or replaced file: its media may have to be sent. */
    private fun needsUpload(row: Row) = !row.missing && (row.assetId == null || row.media != row.seenMedia)

    private fun uploadMedia(row: Row, report: SyncReport) {
        if (row.media in knownMedia) {
            if (needsUpload(row)) report.skippedBytes += row.size
            return
        }
        if (api.media(row.media) == null) {
            val before = report.uploadedBytes
            val hash = sha256Hex(row.file)
            val result = api.upload(row.file, hash, row.file.fileName.toString()) {
                report.uploadedBytes += it
                listener.progress(SyncStage.UPLOADING, -1, -1, report.uploadedBytes + report.skippedBytes)
            }
            report.uploadedFiles++
            // A resumed upload sends only the rest; the part sent before counts as done.
            val sent = report.uploadedBytes - before
            if (sent < row.size) report.skippedBytes += row.size - sent
            val served = result.media?.mediaHash
            if (served != row.media) throw IOException("the server read this file's media as $served, not ${row.media}")
        }
        knownMedia += row.media
    }

    private fun uploadCover(row: Row, cover: PictureRef) {
        if (api.hasBlob(cover.blob)) return
        val bytes = AudioTags.read(row.file).frontCover?.data ?: throw IOException("its cover art disappeared")
        if (sha256Hex(bytes) != cover.blob) throw IOException("its cover art changed while syncing")
        api.uploadBytes(bytes, "cover." + if (cover.mime == "image/png") "png" else "jpg")
        cachePicture(cover.blob, bytes)
    }

    /** Brings the file in line with the server's answer to its push. */
    private fun applyResult(row: Row, change: PushChange, result: PushResult, report: SyncReport) {
        val asset = result.asset
        val folder = folderOf(row)
        if (row.missing) {
            index.delete(row.path)
            // Deleted here but edited elsewhere: the edit won, so the file comes back.
            if (!asset.deleted && folder.mode != SyncMode.SEND_ONLY && receives(row.prefix, asset)) materialize(folder, asset, null, null, report)
            return
        }
        val others = index.byAsset(asset.assetId).filter { it.path != row.path && it.ignored == null && !it.missing }
        if (others.isNotEmpty()) {
            // A second local copy of something already synced from here: leave it be.
            report.warn("${row.path} has the ${Row.sameAs(row.kind)}${others.first().path}; it's left alone and not synced")
            index.put(row.synced(asset).copy(ignored = Row.sameAs(row.kind) + others.first().path))
            return
        }
        // A track this device reads but the server still holds as a plain file (a server from
        // before it could make one a track): not synced yet, so it's offered again next time.
        if (row.kind == AssetKind.AUDIO && asset.kind == AssetKind.OTHER && !asset.deleted) {
            index.put(row.copy(assetId = asset.assetId, base = asset))
            return
        }
        if (folder.mode == SyncMode.SEND_ONLY) {
            index.put(row.synced(asset))
            return
        }
        if (asset.deleted || !receives(row.prefix, asset)) {
            removeLocal(row.synced(asset), report)
            return
        }
        materialize(folder, asset, row, change.tags to change.pictures.firstOrNull { it.type == TagPolicy.FRONT_COVER }?.blob, report)
    }

    /** Removes tags outside the policy from files that are otherwise in sync, such as a comment some app added. */
    private fun clean(report: SyncReport) {
        for (row in index.all()) {
            if (!row.extras || row.changed || row.ignored != null || row.missing) continue
            val folder = config.folders.firstOrNull { it.prefix == row.prefix } ?: continue
            if (folder.mode == SyncMode.SEND_ONLY) continue
            try {
                materialize(folder, row.base ?: continue, row, null, report)
            } catch (e: IOException) {
                report.warn("${row.path}: ${e.message}")
            }
        }
    }

    // ---- Helpers ----

    /**
     * Whether [folder]'s file system ignores case, as Android's shared storage does: the folder is
     * also reachable under its name with the case swapped. Paths there that differ only in case are
     * one file, and the server never holds two such paths at once.
     */
    private fun ignoresCase(folder: FolderConfig): Boolean = ignoresCase ?: caseless.getOrPut(folder.prefix) {
        val root = folder.root
        val name = root.fileName?.toString() ?: return@getOrPut false
        val swapped = name.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
        val other = root.resolveSibling(swapped)
        swapped != name && Files.exists(other) && runCatching { Files.isSameFile(root, other) }.getOrDefault(false)
    }

    private fun pathKey(folder: FolderConfig, path: String) = if (ignoresCase(folder)) path.lowercase() else path

    private fun samePath(folder: FolderConfig, a: Path, b: Path) = pathKey(folder, a.toString()) == pathKey(folder, b.toString())

    /**
     * Clears [target] for a file the server puts there when what's in the way is a second copy this
     * device keeps off the server (disc 2's folder.jpg, the same as disc 1's): the server never knew
     * of it, and the file it copies stays. Anything else in the way is left for the caller to refuse.
     */
    private fun makeRoom(folder: FolderConfig, target: Path) {
        if (!Files.exists(target)) return
        val copy =index.inFolder(folder.prefix).firstOrNull { samePath(folder, it.file, target) } ?: return
        if (copy.ignoredReason?.startsWith(Row.sameAs(copy.kind)) != true || copy.assetId == null) return
        val original = index.byAsset(copy.assetId).any {
            it.path != copy.path && it.ignored == null && !it.missing && it.media == copy.media && Files.exists(it.file)
        }
        if (!original || LocalFiles.stat(copy.file) != (copy.size to copy.mtime)) return
        Files.delete(copy.file)
        index.delete(copy.path)
        touched.add(copy.file)
        log("Deleted ${copy.path}, a copy of a file kept elsewhere, to make room for what the server puts there")
    }

    private fun cover(asset: AssetDto): Picture? {
        val ref = LocalFiles.coverOf(asset) ?: return null
        return Picture(ref.mime, pictureBytes(ref.blob))
    }

    private fun pictureBytes(hash: String): ByteArray {
        val cached = picturesDir.resolve(hash)
        if (Files.exists(cached)) return Files.readAllBytes(cached)
        return api.downloadBytes(hash).also { cachePicture(hash, it) }
    }

    private fun cachePicture(hash: String, bytes: ByteArray) {
        Files.createDirectories(picturesDir)
        val tmp = Files.createTempFile(picturesDir, hash, ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, picturesDir.resolve(hash), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun folderOf(row: Row): FolderConfig =
        config.folders.firstOrNull { it.prefix == row.prefix } ?: throw IOException("no synced folder for ${row.prefix}")

    private fun modeOf(prefix: String) = config.folders.firstOrNull { it.prefix == prefix }?.mode

    private fun receives(prefix: String, asset: AssetDto? = null): Boolean {
        val folder = config.folders.firstOrNull { it.prefix == prefix } ?: return false
        if (folder.mode == SyncMode.SEND_ONLY) return false
        return asset == null || folder.rule().covers(asset.path, asset.kind)
    }

    companion object {
        const val SINCE = "since"
        const val LAST_SUCCESS = "lastSuccessAt"
        const val LAST_CHECK = "lastCheckAt"
        private const val CHECK_WARNINGS = "checkWarnings"

        private fun checkWarnings(index: FileIndex) = index.state(CHECK_WARNINGS)?.lines()?.filter { it.isNotBlank() }.orEmpty()

        /** What waits for approval, from the index alone: for a screen that has no engine. */
        fun storedReview(config: ClientConfig, index: FileIndex, ignoresCase: Boolean): Review =
            ReviewBuilder(config, index) { _, path -> if (ignoresCase) path.lowercase() else path }.build(checkWarnings(index))

        /** A file is recorded once it has gone this long without being written to. */
        const val SETTLE_MS = 10_000L
        const val BATCH = 200

        /** More deletions than this at once need --allow-deletes: an unmounted disk mustn't empty the library. */
        fun tooMany(count: Int, total: Int) = count > 20 && count * 10 > total
    }
}

fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GiB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MiB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KiB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}
