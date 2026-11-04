package com.tlisonbee.mediasync.player.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tlisonbee.mediasync.player.GainMode
import com.tlisonbee.mediasync.player.app
import com.tlisonbee.mediasync.player.library.Track
import com.tlisonbee.mediasync.player.playback.MediaItems
import com.tlisonbee.mediasync.player.playback.PlaybackService
import com.tlisonbee.mediasync.player.playback.Queue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the screens are. Home has the tabs; the others are pushed on top of it. */
sealed interface Route {
    data object Home : Route
    data class ArtistPage(val name: String) : Route
    data class AlbumPage(val key: String) : Route
    data class FolderPage(val path: String) : Route
    data class PlaylistPage(val path: String) : Route
    data object Search : Route
    data object Settings : Route
}

enum class HomeTab(val label: String) { ARTISTS("Artists"), ALBUMS("Albums"), SONGS("Songs"), FOLDERS("Folders"), PLAYLISTS("Playlists") }

/** One item of the queue, by its place in the player's playlist. */
class QueueEntry(val index: Int, val item: MediaItem) {
    val queued: Boolean get() = MediaItems.isQueued(item)
}

/** What the screens show of playback. */
data class PlaybackState(
    val connected: Boolean = false,
    val current: MediaItem? = null,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** What plays after the current track, in order. */
    val upNext: List<QueueEntry> = emptyList(),
)

@UnstableApi
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val library = application.app.library
    val settings = application.app.settings

    // Navigation, kept here so it outlives a rotation.
    val stack = mutableStateListOf<Route>(Route.Home)
    var tab by mutableStateOf(HomeTab.ARTISTS)
    var nowPlayingOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null

    init {
        controllerFuture.addListener({
            val c = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = update()
            })
            update()
        }, MoreExecutors.directExecutor())
    }

    private fun update() {
        val c = controller ?: return
        _playback.value = PlaybackState(
            connected = true,
            current = c.currentMediaItem,
            currentIndex = c.currentMediaItemIndex,
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it > 0 } ?: c.currentMediaItem?.mediaMetadata?.durationMs ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            upNext = Queue.upcoming(c).map { QueueEntry(it, c.getMediaItemAt(it)) },
        )
    }

    /** Where in the current track playback is; read while it's shown. */
    fun positionMs(): Long = controller?.currentPosition ?: 0

    fun navigate(route: Route) {
        nowPlayingOpen = false
        queueOpen = false
        if (stack.lastOrNull() != route) stack += route
    }

    fun back(): Boolean = when {
        queueOpen -> { queueOpen = false; true }
        nowPlayingOpen -> { nowPlayingOpen = false; true }
        stack.size > 1 -> { stack.removeAt(stack.lastIndex); true }
        else -> false
    }

    /** Plays [tracks] from [index], as a new queue; [shuffle] null keeps the shuffle setting. */
    fun play(tracks: List<Track>, index: Int = 0, shuffle: Boolean? = null) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (shuffle != null) c.shuffleModeEnabled = shuffle
        val start = if (shuffle == true && index == 0) tracks.indices.random() else index
        c.setMediaItems(tracks.map { MediaItems.request(it) }, start.coerceIn(tracks.indices), 0)
        c.prepare()
        c.play()
    }

    fun playNext(tracks: List<Track>) = insert(tracks) { c -> if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1 }

    fun addToQueue(tracks: List<Track>) = insert(tracks) { c -> Queue.insertionIndex(c) }

    private fun insert(tracks: List<Track>, at: (MediaController) -> Int) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val empty = c.mediaItemCount == 0
        c.addMediaItems(at(c), tracks.map { MediaItems.request(it, queued = true) })
        if (empty) c.prepare()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = controller?.seekToNext()

    fun previous() = controller?.seekToPrevious()

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Plays the queue's item at playlist [index]. */
    fun jumpTo(index: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun removeFromQueue(index: Int) = controller?.removeMediaItem(index)

    /** Moves an item of the queue to play right after the current one. */
    fun moveToNext(index: Int) {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (index == current) return
        val item = c.getMediaItemAt(index)
        c.removeMediaItem(index)
        // Inserted after the current item, which puts it next under shuffle too (QueueShuffleOrder),
        // and marked queued, so it stays ahead of the rest.
        c.addMediaItem((if (index < current) current - 1 else current) + 1, MediaItems.withQueued(item))
    }

    /** Clears what plays after the current track; when shuffled, what played before it too. */
    fun clearUpNext() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (current < 0) return
        c.removeMediaItems(current + 1, c.mediaItemCount)
        if (c.shuffleModeEnabled) c.removeMediaItems(0, current)
    }

    fun setGainMode(mode: GainMode) = settings.setGainMode(mode)

    fun setShowRemote(show: Boolean) {
        settings.setShowRemote(show)
        library.showRemote = show
    }

    fun rescan(full: Boolean) {
        viewModelScope.launch { library.scan(full) }
    }

    fun tracksOf(items: List<QueueEntry>) = items.mapNotNull { library.data.value?.byPath?.get(it.item.mediaId) }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
    }
}
