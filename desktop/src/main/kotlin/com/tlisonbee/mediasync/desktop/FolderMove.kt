package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.sync.FileIndex
import com.tlisonbee.mediasync.sync.FolderConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Moves a synced folder somewhere else (another drive, say) with its rows in the index, so its
 * files read as the same ones there: nothing is uploaded again, and nothing reads as deleted.
 *
 * Each file's row follows it as soon as it has moved, so a move that stops halfway (a full disk)
 * leaves nothing inconsistent: running it again moves the rest. Files already moved by hand count
 * as moved.
 */
class FolderMove(private val folder: FolderConfig, to: Path) {
    private val from: Path = folder.root
    val to: Path = to.toAbsolutePath().normalize()

    /** What is still in the old folder, to move. */
    val files: List<Path> = if (Files.isDirectory(from)) {
        Files.walk(from).use { s -> s.filter { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.sorted().toList() }
    } else {
        emptyList()
    }

    val bytes: Long = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }

    /** Why the move can't be made, or null if it can. */
    fun problem(index: FileIndex): String? {
        val inTheWay = files.filter { Files.exists(target(it), LinkOption.NOFOLLOW_LINKS) }
        if (inTheWay.isNotEmpty()) {
            return "${inTheWay.size} of the files would replace ones already in $to, e.g. ${to.relativize(target(inTheWay.first()))}."
        }
        if (files.isEmpty()) {
            // Moved by hand already, or a drive that isn't mounted: only the first is a move.
            val kept = index.inFolder(folder.prefix).filter { !it.missing && Path.of(it.path).startsWith(from) }
            if (kept.isNotEmpty() && kept.none { Files.exists(target(Path.of(it.path))) }) {
                return "$from holds none of its files and $to doesn't either; is a drive not mounted?"
            }
            return null
        }
        val store = Files.getFileStore(existing(to))
        if (store != Files.getFileStore(from) && store.usableSpace < bytes) {
            return "$to has ${size(store.usableSpace)} free, but the files take ${size(bytes)}."
        }
        return null
    }

    /** Moves the files, then the rows of any that were already gone; [progress] after each file. */
    fun run(index: FileIndex, progress: (files: Int, bytes: Long) -> Unit = { _, _ -> }): FolderConfig {
        var moved = 0L
        for ((i, file) in files.withIndex()) {
            val target = target(file)
            val size = Files.size(file)
            val mtime = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
            Files.createDirectories(target.parent)
            Files.move(file, target)
            // A move between drives is a copy; keep the time the index knows the file by.
            if (!Files.isSymbolicLink(target)) Files.setLastModifiedTime(target, mtime)
            index.get(file)?.let { index.replace(it.path, it.copy(path = target.toString())) }
            moved += size
            progress(i + 1, moved)
        }
        index.transaction {
            for (row in index.inFolder(folder.prefix)) {
                val path = Path.of(row.path)
                if (path.startsWith(from)) index.replace(row.path, row.copy(path = target(path).toString()))
            }
            // "same picture as /old/folder/x", in any folder.
            for (row in index.all()) {
                val reason = row.ignored ?: continue
                if ("$from/" in reason) index.put(row.copy(ignored = reason.replace("$from/", "$to/")))
            }
        }
        removeEmptyFolders()
        return folder.copy(folder = to.toString())
    }

    private fun target(file: Path): Path = to.resolve(from.relativize(file).toString())

    private fun removeEmptyFolders() {
        if (!Files.isDirectory(from)) return
        Files.walk(from).use { s -> s.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.sorted(Comparator.reverseOrder()).toList() }
            .forEach { dir -> runCatching { Files.delete(dir) }.onFailure { if (it !is IOException) throw it } }
    }

    companion object {
        private fun existing(path: Path): Path = generateSequence(path) { it.parent }.first { Files.exists(it) }

        fun size(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
            else -> "%.0f KB".format(bytes / 1024.0)
        }
    }
}
