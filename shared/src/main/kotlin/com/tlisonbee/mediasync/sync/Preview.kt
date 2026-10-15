package com.tlisonbee.mediasync.sync

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.tags.AudioTags
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlin.io.path.extension

/** What the first sync will do to one folder. */
data class FolderPreview(
    val folder: String,
    val prefix: String,
    val found: Boolean,
    val tracks: Int = 0,
    val moves: Int = 0,
    /** A few (from, to) renames, relative to the folder. */
    val examples: List<Pair<String, String>> = emptyList(),
    val renditions: Int = 0,
    val collisions: Int = 0,
    val junk: Int = 0,
    /** Files that sync as they are, by extension. */
    val others: Map<String, Int> = emptyMap(),
    val unreadable: Int = 0,
) {
    /** The preview in sentences, as the desktop CLI prints it and the Android app shows it. */
    fun describe(): List<String> {
        if (!found) return listOf("$folder: not found")
        return buildList {
            add("${count(tracks, "track")} named by their tags; $moves of them move.")
            if (renditions > 0) add("${count(renditions, "track")} look like another track in a different format. They get \" (2)\" until you link them as renditions in the resolve queue.")
            if (collisions > 0) add("${count(collisions, "track")} would share a path with another, so they get \" (2)\" and a resolve-queue entry.")
            if (junk > 0) add("${count(junk, "track")} carry tags outside the tag policy (comments, encoder notes…). They're removed from the files; the server keeps the originals.")
            if (others.isNotEmpty()) {
                add("${count(others.values.sum(), "other file")} sync as they are, where they are: " +
                    others.entries.sortedByDescending { it.value }.joinToString { "${it.value} ${it.key}" })
            }
            if (unreadable > 0) add("${count(unreadable, "track")} have tags MediaSync can't read; they sync without tag management.")
        }
    }

    private fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"
}

class PreviewResult(val folders: List<FolderPreview>, /** One tab-separated line per file: action, from, to, note. */ val lines: List<String>)

/**
 * What the first sync will do to the files in each folder, without touching them. It reads tags
 * only (no hashing), so it is quick even for a large library.
 */
object Preview {
    fun compute(config: ClientConfig, policy: TagPolicy, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): PreviewResult {
        val lines = mutableListOf("action\tfrom\tto\tnote")
        val folders = config.folders.map { folder -> folder(folder, policy, lines, progress) }
        return PreviewResult(folders, lines)
    }

    private fun folder(folder: FolderConfig, policy: TagPolicy, lines: MutableList<String>, progress: (Int, Int) -> Unit): FolderPreview {
        val root = folder.root
        if (!Files.isDirectory(root)) return FolderPreview(folder.folder, folder.prefix, found = false)
        val tracks = mutableListOf<Pair<Path, Map<String, List<String>>>>()
        val others = mutableMapOf<String, Int>()
        var junk = 0
        var unreadable = 0
        val files = files(root)
        for ((i, file) in files.withIndex()) {
            if (i % 50 == 0) progress(i, files.size)
            val ext = file.extension.lowercase()
            if (!LocalFiles.isTagged(ext)) {
                others.merge(ext.ifEmpty { "(none)" }, 1, Int::plus)
                lines += "keep\t${root.relativize(file)}\t\tsyncs as a plain file"
                continue
            }
            val inspection = try {
                AudioTags.inspect(file)
            } catch (e: Exception) {
                unreadable++
                others.merge("$ext with unreadable tags", 1, Int::plus)
                lines += "keep\t${root.relativize(file)}\t\ttags unreadable: ${e.message}"
                continue
            }
            val allowed = policy.fields[AssetKind.AUDIO].orEmpty()
            if (inspection.hasExtras || inspection.tags.fields.keys.any { it !in allowed }) junk++
            tracks += file to policy.filter(AssetKind.AUDIO, inspection.tags.fields)
        }

        // Where each track will live, with the album-wide disc prefix and collisions as the server decides them.
        val multiDisc = tracks.groupBy { RepoPaths.musicAlbumFolder(it.second) }
            .mapValues { (_, members) -> members.any { RepoPaths.saysMultiDisc(it.second) } }
        val taken = HashMap<String, String>()
        var moves = 0
        var collisions = 0
        var renditions = 0
        val examples = mutableListOf<Pair<String, String>>()
        for ((file, tags) in tracks.sortedBy { it.first }) {
            val original = file.fileName.toString().substringBeforeLast('.')
            var path = RepoPaths.music(tags, original, multiDisc.getValue(RepoPaths.musicAlbumFolder(tags)))
            if (folder.prefix != RepoPaths.MUSIC) path = LocalFiles.repoPath(folder, file)
            val ext = file.extension.lowercase()
            // The same tags in another format is likely the same recording: the server asks whether to link them.
            val sameTrack = taken[path.lowercase(Locale.ROOT)]?.let { it != ext } == true
            var candidate = path
            var n = 2
            while (taken.putIfAbsent(candidate.lowercase(Locale.ROOT), ext) != null) candidate = "$path (${n++})"
            if (candidate != path) if (sameTrack) renditions++ else collisions++
            val target = LocalFiles.localPath(folder, candidate, file.extension)
            if (target != file) {
                moves++
                if (examples.size < 8) examples += root.relativize(file).toString() to root.relativize(target).toString()
                lines += "move\t${root.relativize(file)}\t${root.relativize(target)}\t${if (candidate != path) "gets a suffix" else ""}"
            } else {
                lines += "keep\t${root.relativize(file)}\t\t"
            }
        }
        return FolderPreview(folder.folder, folder.prefix, true, tracks.size, moves, examples, renditions, collisions, junk, others, unreadable)
    }

    private fun files(root: Path): List<Path> {
        val files = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                    if (dir != root && LocalFiles.skipped(dir.fileName.toString())) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && !LocalFiles.skipped(file.fileName.toString())) files.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            },
        )
        return files
    }
}
