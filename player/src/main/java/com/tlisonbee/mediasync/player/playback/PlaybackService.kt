package com.tlisonbee.mediasync.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
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
import com.tlisonbee.mediasync.player.lastfm.PlayTracker
import com.tlisonbee.mediasync.player.lastfm.Scrobble
import com.tlisonbee.mediasync.player.library.Track
import com.tlisonbee.mediasync.player.remote.RemoteMusic
import com.tlisonbee.mediasync.player.tidal.TidalDataSource
import com.tlisonbee.mediasync.player.tidal.TidalTracks
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

    /** For scrobbling: how long the current track has played. */
    private val tracker = PlayTracker(SystemClock::elapsedRealtime) { System.currentTimeMillis() / 1000 }
    private var scrobbleCheck: Job? = null

    /** The TIDAL track last prepared again after its stream expired. */
    private var retried: String? = null

    override fun onCreate() {
        super.onCreate()
        val settings = app.settings
        val gain = GainProcessor { item -> settings.gainMode.value.factor(MediaItems.gains(item)) }
        // Server tracks stream with this phone's MediaSync token, which goes only to that server.
        val remote = app.library.remote
        val http = DefaultHttpDataSource.Factory().setUserAgent(RemoteMusic.USER_AGENT).setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
        val tidal = app.tidal
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            val access = remote.access
            if (access != null && spec.uri.toString().startsWith(access.serverUrl + "/")) {
                spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${access.token}"))
            } else {
                spec
            }
        }
        // TIDAL tracks' manifests come from TIDAL when they're due (TidalDataSource).
        val sources = androidx.media3.datasource.DataSource.Factory { TidalDataSource(tidal, resolving.createDataSource()) }
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
        // Scrobbles played while offline go out when there's a connection again.
        if (app.lastFm.isLoggedIn) app.lastFm.flush()
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

    /** A track by its ID: the library's (the phone's, the server's), or a TIDAL track a screen showed. */
    private fun find(library: com.tlisonbee.mediasync.player.library.LibraryData, path: String): Track? =
        library.byPath[path] ?: if (TidalTracks.isTidal(path)) app.tidal.tracks.get(path) else null

    private fun playable(track: Track, queued: Boolean): MediaItem {
        val remote = app.library.remote
        if (track.isRemote && remote.access == null) remote.readAccess()
        return MediaItems.playable(track, app.library.covers, queued, track.stream?.let(remote::streamUri))
    }

    /** A server track's cover is fetched when it starts playing, for the notification and lock screen. */
    private fun fetchCover(index: Int) {
        val item = exo.getMediaItemAt(index)
        if (item.mediaMetadata.artworkUri != null) return
        val track = app.library.data.value?.let { find(it, item.mediaId) }?.takeIf { !it.isLocal } ?: return
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
        val kept = queue.paths.indices.filter { find(library, queue.paths[it]) != null }
        if (kept.isEmpty()) return@withContext null
        val items = kept.map { playable(find(library, queue.paths[it])!!, it in queue.queued) }
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
                        find(library, item.mediaId)?.let { playable(it, MediaItems.isQueued(item)) }
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

    /** What Last.fm is told for [item]: the first artist (featured ones are in the tags, not Last.fm's artist), the album, its artist. */
    private fun scrobbleInfo(item: MediaItem): Scrobble? {
        val track = app.library.data.value?.let { find(it, item.mediaId) }
        val meta = item.mediaMetadata
        val artist = track?.artists?.firstOrNull()?.takeIf { it.isNotBlank() } ?: meta.artist?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val title = track?.title?.takeIf { it.isNotBlank() } ?: meta.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return Scrobble(
            artist, title,
            album = track?.album ?: meta.albumTitle?.toString().orEmpty(),
            albumArtist = track?.albumArtist ?: meta.albumArtist?.toString().orEmpty(),
            trackNumber = track?.trackNumber,
        )
    }

    /** Brings the scrobbling up to date with the player; [restart] for a new track (or a repeat of it). */
    private fun trackScrobble(restart: Boolean) {
        val lastFm = app.lastFm
        if (!lastFm.isLoggedIn) return
        val item = exo.currentMediaItem
        if (restart || item?.mediaId != tracker.currentId) {
            tracker.start(item?.mediaId, item?.let(::scrobbleInfo), item?.mediaMetadata?.durationMs ?: 0)
        }
        tracker.duration(exo.duration.takeIf { it != C.TIME_UNSET } ?: 0)
        tracker.playing(exo.isPlaying)
        tracker.announce()?.let(lastFm::nowPlaying)
        tracker.due()?.let { s ->
            lastFm.scrobble(s)
            lastFm.stats.counted(s.artist, s.track)
        }
        scrobbleCheck?.cancel()
        tracker.msUntilDue()?.let { wait ->
            scrobbleCheck = scope.launch {
                delay(wait + 250)
                trackScrobble(restart = false)
            }
        }
    }

    private inner class Listener : Player.Listener {
        /** A new track started, as opposed to the same item being replaced (its cover fetched). */
        private var newTrack = false

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                trackScrobble(restart = newTrack)
                newTrack = false
            }
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
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) newTrack = true
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
            // A TIDAL track paused for long: its segments' signed addresses ran out. Preparing
            // again fetches a fresh manifest, once.
            val id = exo.currentMediaItem?.mediaId
            val expired = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode in setOf(401, 403, 410)
            if (id != null && TidalTracks.isTidal(id) && expired && id != retried) {
                retried = id
                exo.prepare()
                return
            }
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
