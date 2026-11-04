package com.tlisonbee.mediasync.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.tlisonbee.mediasync.player.tidal.Tidal
import com.tlisonbee.mediasync.player.tidal.TidalTracks
import com.tlisonbee.mediasync.player.library.Covers
import com.tlisonbee.mediasync.player.library.Gains
import com.tlisonbee.mediasync.player.library.Track
import java.io.File

/**
 * Tracks as Media3 items. The media ID is the file's path; screens send items with only that
 * (and [EXTRA_QUEUED]), and the service fills in the rest from the library.
 */
object MediaItems {
    /** Marks an item added with Play next or Add to queue, rather than from the album or list played. */
    const val EXTRA_QUEUED = "com.tlisonbee.mediasync.player.QUEUED"
    private const val TRACK_GAIN = "com.tlisonbee.mediasync.player.TRACK_GAIN"
    private const val TRACK_PEAK = "com.tlisonbee.mediasync.player.TRACK_PEAK"
    private const val ALBUM_GAIN = "com.tlisonbee.mediasync.player.ALBUM_GAIN"
    private const val ALBUM_PEAK = "com.tlisonbee.mediasync.player.ALBUM_PEAK"

    /** What a screen sends: the track's path, and whether it's queued. */
    fun request(track: Track, queued: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId(track.path)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setExtras(bundleOf(EXTRA_QUEUED to queued)).build())
        .build()

    /** The item the player plays: the file, or for a server track [streamUri]. */
    fun playable(track: Track, covers: Covers, queued: Boolean, streamUri: Uri? = null): MediaItem {
        val extras = Bundle().apply {
            putBoolean(EXTRA_QUEUED, queued)
            track.gain.trackGain?.let { putFloat(TRACK_GAIN, it) }
            track.gain.trackPeak?.let { putFloat(TRACK_PEAK, it) }
            track.gain.albumGain?.let { putFloat(ALBUM_GAIN, it) }
            track.gain.albumPeak?.let { putFloat(ALBUM_PEAK, it) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.albumOrUnknown)
            .setAlbumArtist(track.albumArtistOrArtist)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .setGenre(track.genre.ifEmpty { null })
            .setRecordingYear(track.year.toIntOrNull())
            .setDurationMs(track.durationMs)
            // A server track's cover is only there once something fetched it (PlaybackService does).
            .setArtworkUri(track.cover?.let { covers.file(it) }?.takeIf { track.isLocal || it.isFile }?.let { Uri.fromFile(it) })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        val builder = MediaItem.Builder().setMediaId(track.path).setMediaMetadata(metadata)
        when {
            // A DASH manifest TidalDataSource fetches when the player gets to the track.
            track.isTidal -> builder.setUri(Tidal.uri(TidalTracks.idOf(track.path))).setMimeType(MimeTypes.APPLICATION_MPD)
            track.isRemote -> builder.setUri(streamUri)
            else -> builder.setUri(Uri.fromFile(File(track.path)))
        }
        return builder.build()
    }

    /** A screen's request for an item already in the queue, marked queued. */
    fun withQueued(item: MediaItem): MediaItem = MediaItem.Builder()
        .setMediaId(item.mediaId)
        .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(bundleOf(EXTRA_QUEUED to true)).build())
        .build()

    fun isQueued(item: MediaItem) = item.mediaMetadata.extras?.getBoolean(EXTRA_QUEUED) == true

    fun gains(item: MediaItem): Gains {
        val e = item.mediaMetadata.extras ?: return Gains.NONE
        fun f(key: String) = if (e.containsKey(key)) e.getFloat(key) else null
        return Gains(f(TRACK_GAIN), f(TRACK_PEAK), f(ALBUM_GAIN), f(ALBUM_PEAK))
    }
}
