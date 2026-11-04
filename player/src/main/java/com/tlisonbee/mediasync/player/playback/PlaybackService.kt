package com.tlisonbee.mediasync.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tlisonbee.mediasync.player.library.Track
import com.tlisonbee.mediasync.player.remote.RemoteMusic
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.tlisonbee.mediasync.player.PlayerActivity
import com.tlisonbee.mediasync.player.R
import com.tlisonbee.mediasync.player.app
import com.tlisonbee.mediasync.player.audio.GainProcessor
import com.tlisonbee.mediasync.player.audio.PlayerRenderers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plays the queue, in the background, with the media notification, the lock screen and Bluetooth
 * controls (all from Media3's session). Keeps the queue on disk, so playback picks up where it
 * left off, also from a headset's play button after the app was closed.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var exo: ExoPlayer
    private lateinit var player: QueuePlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saved by lazy { SavedQueue(File(filesDir, "queue")) }
    private var positionSaver: Job? = null
    private var errorsInARow = 0

    override fun onCreate() {
        super.onCreate()
        val settings = app.settings
        val gain = GainProcessor { item -> settings.gainMode.value.factor(MediaItems.gains(item)) }
        // Server tracks stream with this phone's MediaSync token, which goes only to that server.
        val remote = app.library.remote
        val http = DefaultHttpDataSource.Factory().setUserAgent(RemoteMusic.USER_AGENT).setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
        val sources = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            val access = remote.access
            if (access != null && spec.uri.toString().startsWith(access.serverUrl + "/")) {
                spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${access.token}"))
            } else {
                spec
            }
        }
        exo = ExoPlayer.Builder(this, PlayerRenderers(this, listOf(gain)))
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = QueuePlayer(exo)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_NOW_PLAYING, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setCallback(Callback()).setSessionActivity(open).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })
        exo.addListener(Listener())
        scope.launch {
            val restored = load() ?: return@launch
            // Something may have been played already, from a screen or a headset.
            if (exo.mediaItemCount == 0) apply(restored)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePositionNow()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun playable(track: Track, queued: Boolean): MediaItem {
        val remote = app.library.remote
        if (track.isRemote && remote.access == null) remote.readAccess()
        return MediaItems.playable(track, app.library.covers, queued, track.stream?.let(remote::streamUri))
    }

    /** A server track's cover is fetched when it starts playing, for the notification and lock screen. */
    private fun fetchCover(index: Int) {
        val item = exo.getMediaItemAt(index)
        if (item.mediaMetadata.artworkUri != null) return
        val track = app.library.data.value?.byPath?.get(item.mediaId)?.takeIf { it.isRemote } ?: return
        val key = track.cover ?: return
        scope.launch {
            withContext(Dispatchers.IO) { app.library.covers.load(key, 64) } ?: return@launch
            val now = (0 until exo.mediaItemCount).firstOrNull { exo.getMediaItemAt(it) === item } ?: return@launch
            exo.replaceMediaItem(now, playable(track, MediaItems.isQueued(item)))
        }
    }

    private class Restored(val items: List<MediaItem>, val index: Int, val positionMs: Long, val repeatMode: Int, val shuffle: Boolean, val order: IntArray?)

    /** The saved queue, with the tracks that are still in the library. */
    private suspend fun load(): Restored? = withContext(Dispatchers.IO) {
        val queue = saved.loadQueue() ?: return@withContext null
        val position = saved.loadPosition()
        val library = app.library.loaded()
        val kept = queue.paths.indices.filter { library.byPath[queue.paths[it]] != null }
        if (kept.isEmpty()) return@withContext null
        val items = kept.map { playable(library.byPath.getValue(queue.paths[it]), it in queue.queued) }
        val index = position?.index?.let { kept.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        // The order only fits if nothing was dropped.
        val order = queue.shuffleOrder?.takeIf { kept.size == queue.paths.size }
        val positionMs = if (position != null && position.index in kept) position.positionMs else 0
        Restored(items, index, positionMs, position?.repeatMode ?: Player.REPEAT_MODE_OFF, position?.shuffle == true, order)
    }

    private fun apply(r: Restored) {
        exo.setMediaItems(r.items, r.index, r.positionMs)
        exo.repeatMode = r.repeatMode
        if (r.shuffle) player.restoreShuffle(r.order)
        exo.prepare()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                val library = app.library.loaded()
                future.set(
                    mediaItems.mapNotNullTo(mutableListOf()) { item ->
                        library.byPath[item.mediaId]?.let { playable(it, MediaItems.isQueued(item)) }
                    },
                )
            }
            return future
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val r = load()
                if (r == null) {
                    future.setException(IllegalStateException("nothing to resume"))
                } else {
                    exo.repeatMode = r.repeatMode
                    // Set before the items arrive, so they're shuffled from the current one (the saved order is lost).
                    exo.shuffleModeEnabled = r.shuffle
                    future.set(MediaSession.MediaItemsWithStartPosition(r.items, r.index, r.positionMs))
                }
            }
            return future
        }
    }

    private inner class Listener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) saveQueue()
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) {
                savePosition()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (exo.currentMediaItemIndex >= 0) fetchCover(exo.currentMediaItemIndex)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            positionSaver?.cancel()
            if (isPlaying) {
                errorsInARow = 0
                positionSaver = scope.launch {
                    while (isActive) {
                        delay(10_000)
                        savePosition()
                    }
                }
            }
        }

        /** A file that won't play (gone, or damaged) is skipped, as long as others still play. */
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "can't play ${exo.currentMediaItem?.mediaId}", error)
            if (++errorsInARow <= 10 && exo.hasNextMediaItem()) {
                exo.seekToNextMediaItem()
                exo.prepare()
            }
        }
    }

    private var queueSave: Job? = null

    private fun saveQueue() {
        // Taken now, on the player's thread; written a moment later, once a burst of changes is over.
        queueSave?.cancel()
        queueSave = scope.launch {
            delay(500)
            val items = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it) }
            val queue = SavedQueue.Queue(items.map { it.mediaId }, items.indices.filterTo(HashSet()) { MediaItems.isQueued(items[it]) }, player.shuffleIndices())
            withContext(Dispatchers.IO) { saved.saveQueue(queue) }
            savePosition()
        }
    }

    private fun position() = SavedQueue.Position(exo.currentMediaItemIndex, exo.currentPosition, exo.repeatMode, exo.shuffleModeEnabled)

    private fun savePosition() {
        if (exo.mediaItemCount == 0) return
        val p = position()
        scope.launch(Dispatchers.IO) { saved.savePosition(p) }
    }

    private fun savePositionNow() {
        if (::exo.isInitialized && exo.mediaItemCount > 0) runCatching { saved.savePosition(position()) }
    }

    companion object {
        private const val TAG = "PlayerService"
    }
}
