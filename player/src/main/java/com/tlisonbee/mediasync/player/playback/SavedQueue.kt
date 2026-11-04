package com.tlisonbee.mediasync.player.playback

import java.io.File

/**
 * The queue as it was, for picking up where playback left off: the tracks (rarely rewritten) in
 * one file, and where in them playback was (rewritten every few seconds) in another.
 */
class SavedQueue(private val dir: File) {
    data class Queue(val paths: List<String>, val queued: Set<Int>, val shuffleOrder: IntArray?)

    data class Position(val index: Int, val positionMs: Long, val repeatMode: Int, val shuffle: Boolean)

    private val queueFile get() = File(dir, "queue.txt")
    private val positionFile get() = File(dir, "position.txt")

    fun saveQueue(queue: Queue) = write(
        queueFile,
        buildString {
            append("1\n")
            append(queue.shuffleOrder?.joinToString(",").orEmpty()).append('\n')
            queue.paths.forEachIndexed { i, p -> append(if (i in queue.queued) "q " else "- ").append(p).append('\n') }
        },
    )

    fun savePosition(p: Position) = write(positionFile, "${p.index} ${p.positionMs} ${p.repeatMode} ${if (p.shuffle) 1 else 0}\n")

    fun loadQueue(): Queue? = runCatching {
        val lines = queueFile.readLines()
        if (lines.firstOrNull() != "1") return null
        val order = lines.getOrNull(1)?.takeIf { it.isNotBlank() }?.split(',')?.map { it.toInt() }?.toIntArray()
        val entries = lines.drop(2).filter { it.length > 2 }
        Queue(entries.map { it.substring(2) }, entries.indices.filterTo(HashSet()) { entries[it].startsWith("q ") }, order)
    }.getOrNull()

    fun loadPosition(): Position? = runCatching {
        val (index, position, repeat, shuffle) = positionFile.readText().trim().split(' ')
        Position(index.toInt(), position.toLong(), repeat.toInt(), shuffle == "1")
    }.getOrNull()

    private fun write(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, ".${file.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }
}
