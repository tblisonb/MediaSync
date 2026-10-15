package com.tlisonbee.mediasync.desktop

import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.attribute.BasicFileAttributes

/**
 * Watches the synced folders with inotify (Java's WatchService registers each folder on Linux) and
 * calls [onChange] for anything that happens in them. The caller debounces; the scan works out
 * what actually changed, so a missed or extra event costs nothing but a scan.
 */
class Watcher(private val roots: List<Path>, private val onChange: () -> Unit) : AutoCloseable {
    private val service = FileSystems.getDefault().newWatchService()
    private val keys = HashMap<WatchKey, Path>()

    /** Blocks, delivering events until [close] is called. */
    fun run() {
        roots.filter { Files.isDirectory(it) }.forEach(::registerTree)
        try {
            while (true) {
                val key = service.take()
                val dir = keys[key]
                var changed = false
                for (event in key.pollEvents()) {
                    changed = true
                    if (event.kind() == OVERFLOW || dir == null) continue
                    val name = event.context() as? Path ?: continue
                    val child = dir.resolve(name)
                    if (event.kind() == ENTRY_CREATE && Files.isDirectory(child)) registerTree(child)
                }
                if (!key.reset()) keys.remove(key)
                if (changed) onChange()
            }
        } catch (_: ClosedWatchServiceException) {
            // closed: done
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun registerTree(start: Path) {
        try {
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != start && LocalFiles.skipped(dir.fileName.toString())) return FileVisitResult.SKIP_SUBTREE
                        keys[dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)] = dir
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
                },
            )
        } catch (e: IOException) {
            System.err.println("Can't watch $start: ${e.message}; the periodic sync still covers it")
        }
    }

    override fun close() = service.close()
}
