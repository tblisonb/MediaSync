package com.tlisonbee.mediasync.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * The order ExoPlayer plays in while shuffle is on. Unlike its default, items inserted into the
 * playlist play right after the item before them in the playlist, so **Play next** (inserted
 * after the current item) and **Add to queue** (after the last queued one) work the same shuffled
 * or not. A fresh order starts with a given item, so turning shuffle on mid-album plays everything
 * else after the current track, and none of it is skipped as "already played".
 */
@UnstableApi
class QueueShuffleOrder(private val order: IntArray) : ShuffleOrder {
    private val position = IntArray(order.size).also { pos -> order.forEachIndexed { i, index -> pos[index] = i } }

    /** The playlist indices in play order. */
    fun indices(): IntArray = order.copyOf()

    override fun getLength() = order.size

    override fun getNextIndex(index: Int) = position[index].let { if (it + 1 < order.size) order[it + 1] else -1 }

    override fun getPreviousIndex(index: Int) = position[index].let { if (it > 0) order[it - 1] else -1 }

    override fun getLastIndex() = if (order.isEmpty()) -1 else order.last()

    override fun getFirstIndex() = if (order.isEmpty()) -1 else order.first()

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val shifted = order.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        // After the item now before the insertion point, or first when there's none.
        val at = if (insertionIndex == 0) 0 else shifted.indexOf(insertionIndex - 1) + 1
        shifted.addAll(at, (insertionIndex until insertionIndex + insertionCount).toList())
        return QueueShuffleOrder(shifted.toIntArray())
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        return QueueShuffleOrder(
            order.filter { it < indexFrom || it >= indexToExclusive }.map { if (it >= indexToExclusive) it - count else it }.toIntArray(),
        )
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0))

    companion object {
        /** [length] items in a random order that starts with [first] (in that order), the rest shuffled. */
        fun startingWith(length: Int, first: List<Int>, random: Random = Random.Default): QueueShuffleOrder {
            val head = first.filter { it in 0 until length }.distinct()
            val taken = head.toSet()
            val rest = (0 until length).filter { it !in taken }.shuffled(random)
            return QueueShuffleOrder((head + rest).toIntArray())
        }

        /** An order kept from before, if it still fits [length] items. */
        fun restore(order: IntArray, length: Int): QueueShuffleOrder? =
            if (order.size == length && order.sorted() == (0 until length).toList()) QueueShuffleOrder(order) else null
    }
}
