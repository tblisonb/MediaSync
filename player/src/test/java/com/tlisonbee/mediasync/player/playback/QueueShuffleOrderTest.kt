package com.tlisonbee.mediasync.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

@UnstableApi
class QueueShuffleOrderTest {
    private fun ShuffleOrder.play(): List<Int> = buildList {
        var i = firstIndex
        while (i != -1) {
            add(i)
            i = getNextIndex(i)
        }
    }

    @Test
    fun aNewOrderStartsWithTheCurrentItemAndKeepsEveryItem() {
        val order = QueueShuffleOrder.startingWith(10, listOf(4, 5), Random(1))
        val play = order.play()
        assertEquals(listOf(4, 5), play.take(2))
        assertEquals((0 until 10).toList(), play.sorted())
        assertEquals(play.last(), order.lastIndex)
        assertEquals(play[1], order.getNextIndex(4))
        assertEquals(4, order.getPreviousIndex(5))
        assertEquals(-1, order.getPreviousIndex(4))
    }

    @Test
    fun insertedItemsPlayAfterTheItemBeforeThem() {
        // Playlist 0..4, playing in the order 2 0 4 1 3; item 2 is current.
        val order = QueueShuffleOrder(intArrayOf(2, 0, 4, 1, 3))
        // Play next: two items inserted after the current one (playlist index 3).
        val next = order.cloneAndInsert(3, 2)
        assertEquals(listOf(2, 3, 4, 0, 6, 1, 5), next.play())
        // Inserting at the start plays first.
        assertEquals(listOf(0, 3, 1, 5, 2, 4), order.cloneAndInsert(0, 1).play())
    }

    @Test
    fun removingKeepsTheOrderOfTheRest() {
        val order = QueueShuffleOrder(intArrayOf(2, 0, 4, 1, 3))
        assertEquals(listOf(0, 2, 1), order.cloneAndRemove(1, 3).play())
        assertEquals(emptyList<Int>(), order.cloneAndClear().play())
    }

    @Test
    fun aSavedOrderOnlyComesBackIfItStillFits() {
        assertEquals(listOf(1, 0, 2), QueueShuffleOrder.restore(intArrayOf(1, 0, 2), 3)!!.play())
        assertNull(QueueShuffleOrder.restore(intArrayOf(1, 0, 2), 4))
        assertNull(QueueShuffleOrder.restore(intArrayOf(1, 1, 2), 3))
    }
}
