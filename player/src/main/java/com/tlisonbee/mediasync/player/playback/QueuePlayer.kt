package com.tlisonbee.mediasync.player.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The player the media session controls: ExoPlayer, with shuffle kept in [QueueShuffleOrder].
 * Turning shuffle on starts the new order with the current track and anything queued after it;
 * playing a new list while shuffled shuffles it from the track chosen.
 */
@UnstableApi
class QueuePlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
    init {
        exo.setShuffleOrder(QueueShuffleOrder(IntArray(0)))
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled == exo.shuffleModeEnabled) return
        if (shuffleModeEnabled) {
            reshuffle()
        } else {
            // Queued tracks keep playing next in the playlist's own order.
            val queued = Queue.upcomingQueued(exo).map { exo.getMediaItemAt(it) }
            queued.forEachIndexed { k, item ->
                val items = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it) }
                val from = items.indexOfFirst { it === item }.takeIf { it >= 0 } ?: items.indexOf(item)
                val current = exo.currentMediaItemIndex
                // Where it ends up, counted after it's taken out of its old place.
                val to = if (from < current) current + k else current + 1 + k
                if (from >= 0 && from != to) exo.moveMediaItem(from, to)
            }
        }
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    /** A new random order, starting with the current item and the ones queued after it. */
    private fun reshuffle() {
        if (exo.mediaItemCount == 0) return
        val current = exo.currentMediaItemIndex
        val queued = generateSequence(current + 1) { it + 1 }.takeWhile { it < exo.mediaItemCount && MediaItems.isQueued(exo.getMediaItemAt(it)) }
        exo.setShuffleOrder(QueueShuffleOrder.startingWith(exo.mediaItemCount, listOf(current) + queued))
    }

    /** Puts a saved order back, or makes a new one when it no longer fits. */
    fun restoreShuffle(order: IntArray?) {
        val restored = order?.let { QueueShuffleOrder.restore(it, exo.mediaItemCount) }
        if (restored != null) exo.setShuffleOrder(restored) else reshuffle()
        exo.shuffleModeEnabled = true
    }

    /** The shuffle order in use, for saving it. */
    fun shuffleIndices(): IntArray? = if (exo.shuffleModeEnabled) Queue.playOrder(exo).toIntArray() else null

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        super.setMediaItems(mediaItems)
        if (exo.shuffleModeEnabled) reshuffle()
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        super.setMediaItems(mediaItems, resetPosition)
        if (exo.shuffleModeEnabled) reshuffle()
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
        if (exo.shuffleModeEnabled) reshuffle()
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        super.setMediaItem(mediaItem)
        if (exo.shuffleModeEnabled) reshuffle()
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        super.setMediaItem(mediaItem, resetPosition)
        if (exo.shuffleModeEnabled) reshuffle()
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        super.setMediaItem(mediaItem, startPositionMs)
        if (exo.shuffleModeEnabled) reshuffle()
    }
}

/** The queue as any [Player] sees it (the service's, or a screen's controller). */
object Queue {
    /** The playlist indices in the order they play, from the first. */
    fun playOrder(player: Player): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = player.shuffleModeEnabled
        return buildList {
            var i = timeline.getFirstWindowIndex(shuffle)
            while (i != C.INDEX_UNSET) {
                add(i)
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
            }
        }
    }

    /** What plays after the current item, in order. */
    fun upcoming(player: Player): List<Int> {
        val order = playOrder(player)
        val from = order.indexOf(player.currentMediaItemIndex)
        return if (from < 0) emptyList() else order.drop(from + 1)
    }

    /** The queued items right after the current one, in the order they play. */
    fun upcomingQueued(player: Player): List<Int> = upcoming(player).takeWhile { MediaItems.isQueued(player.getMediaItemAt(it)) }

    /** Where Add to queue inserts: after the current item and whatever is already queued after it. */
    fun insertionIndex(player: Player): Int {
        if (player.mediaItemCount == 0) return 0
        return (upcomingQueued(player).lastOrNull() ?: player.currentMediaItemIndex) + 1
    }
}
