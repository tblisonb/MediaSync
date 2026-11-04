package com.tlisonbee.mediasync.player.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.MusicListing
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.player.BuildConfig
import com.tlisonbee.mediasync.player.audio.ReplayGain
import com.tlisonbee.mediasync.player.library.Gains
import com.tlisonbee.mediasync.player.library.Stream
import com.tlisonbee.mediasync.player.library.Track
import com.tlisonbee.mediasync.tags.TagField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** MediaSync's pairing, lent to the player by MediaSync's PlayerAccessProvider. */
data class Access(val serverUrl: String, val token: String, val musicFolder: String?, val deviceName: String?)

/** Where the server's music stands, for Settings. */
data class RemoteState(
    /** MediaSync is installed and paired. */
    val paired: Boolean = false,
    val server: String? = null,
    /** Tracks on the server, and how many of them aren't on the phone. */
    val total: Int = 0,
    val notOnPhone: Int = 0,
    val refreshing: Boolean = false,
    val lastError: String? = null,
    val lastRefresh: Long = 0,
)

/**
 * The whole music library on the MediaSync server, for playing what the phone doesn't hold. The
 * listing (GET /api/v1/music) is kept on disk, so it's there offline too; files stream from the
 * server's blobs with the phone's own token, over Tailscale.
 */
class RemoteMusic(private val context: Context, private val dir: File) {
    @Volatile
    var access: Access? = null
        private set

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    /** Every track on the server, as the player's tracks (with [Track.stream]). */
    @Volatile
    var tracks: List<Track> = emptyList()
        private set

    private var rev: Long? = null
    private val lock = Mutex()
    private val cache get() = File(dir, "music.json")

    /** Reads MediaSync's pairing; null when MediaSync isn't installed, isn't paired, or is too old to share it. */
    fun readAccess(): Access? = runCatching {
        context.contentResolver.query(Uri.parse("content://$AUTHORITY"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun col(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getString(it) }
            val url = col("server_url") ?: return@use null
            val token = col("token") ?: return@use null
            Access(url.trimEnd('/'), token, col("music_folder"), col("device_name"))
        }
    }.getOrNull().also { access = it }

    /** The listing as last fetched, from disk. */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (tracks.isNotEmpty()) return@withLock
            val a = access ?: readAccess()
            val listing = runCatching { ApiJson.decodeFromString(MusicListing.serializer(), cache.readText()) }.getOrNull() ?: return@withLock
            rev = listing.rev
            tracks = listing.tracks.mapNotNull { toTrack(it, a?.musicFolder) }
            _state.value = _state.value.copy(paired = a != null, server = a?.serverUrl, total = tracks.size)
        }
    }

    /** Fetches the listing again if the server has anything new; true when it changed. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val a = readAccess()
            if (a == null) {
                _state.value = RemoteState(paired = false)
                return@withLock false
            }
            _state.value = _state.value.copy(paired = true, server = a.serverUrl, refreshing = true)
            try {
                val text = get(a, "/api/v1/music" + (rev?.let { "?since=$it" } ?: "")).decodeToString()
                val listing = ApiJson.decodeFromString(MusicListing.serializer(), text)
                if (listing.unchanged) {
                    _state.value = _state.value.copy(refreshing = false, lastError = null, lastRefresh = System.currentTimeMillis())
                    return@withLock false
                }
                dir.mkdirs()
                File(dir, "music.json.tmp").apply { writeText(text) }.renameTo(cache)
                rev = listing.rev
                tracks = listing.tracks.mapNotNull { toTrack(it, a.musicFolder) }
                _state.value = _state.value.copy(total = tracks.size, refreshing = false, lastError = null, lastRefresh = System.currentTimeMillis())
                true
            } catch (e: Exception) {
                Log.w(TAG, "couldn't list the server's music", e)
                val why = when {
                    e is HttpStatus && e.code == 404 -> "The server is too old to stream from (it needs MediaSync server 1.5.0)."
                    e is HttpStatus && e.code == 401 -> "The server doesn't accept this phone's pairing any more."
                    else -> "Couldn't reach the server: ${e.message ?: e.javaClass.simpleName}"
                }
                _state.value = _state.value.copy(refreshing = false, lastError = why)
                false
            }
        }
    }

    /** Records how many of the server's tracks the phone lacks, once the library has compared them. */
    fun counted(notOnPhone: Int) {
        _state.value = _state.value.copy(notOnPhone = notOnPhone)
    }

    /** Where a track streams from. */
    fun streamUri(stream: Stream): Uri? = access?.let { Uri.parse("${it.serverUrl}/api/v1/blobs/${stream.blob}") }

    /** A blob's bytes (a cover), or null. */
    fun download(blob: String): ByteArray? = runCatching {
        val a = access ?: readAccess() ?: return null
        get(a, "/api/v1/blobs/$blob")
    }.getOrNull()

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    private fun get(a: Access, path: String): ByteArray {
        val connection = URL(a.serverUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer ${a.token}")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) throw HttpStatus(connection.responseCode)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "PlayerRemote"
        const val AUTHORITY = "com.tlisonbee.mediasync.player-access"

        /** Not MediaSync-<kind>/<version>: the server records those as the phone's app version. */
        val USER_AGENT = "MediaSyncPlayer/${BuildConfig.VERSION_NAME}"

        /** A server track as the player's, or null for one with nothing to play. */
        fun toTrack(dto: AssetDto, musicFolder: String?): Track? {
            if (dto.deleted) return null
            val rendition = dto.renditions.firstOrNull() ?: return null
            val tags = dto.tags
            fun first(field: String) = tags[field]?.firstOrNull { it.isNotBlank() }
            fun gain(field: String) = first(field)?.let(ReplayGain::parseDb)
            fun peak(field: String) = first(field)?.let(ReplayGain::parsePeak)
            val relative = dto.path.removePrefix(RepoPaths.MUSIC)
            return Track(
                path = REMOTE_PREFIX + dto.assetId,
                size = rendition.size,
                mtime = 0,
                title = first(TagField.TITLE) ?: RepoPaths.name(dto.path),
                artists = tags[TagField.ARTIST].orEmpty().filter { it.isNotBlank() },
                album = first(TagField.ALBUM).orEmpty(),
                albumArtist = first(TagField.ALBUM_ARTIST).orEmpty(),
                date = first(TagField.DATE).orEmpty(),
                genre = tags[TagField.GENRE].orEmpty().joinToString(", "),
                trackNumber = Track.number(first(TagField.TRACK_NUMBER)),
                discNumber = Track.number(first(TagField.DISC_NUMBER)),
                durationMs = rendition.durationMs,
                cover = dto.pictures.firstOrNull { it.type == TagPolicy.FRONT_COVER }?.blob?.let { REMOTE_COVER + it },
                gain = Gains(
                    gain(TagField.REPLAYGAIN_TRACK_GAIN), peak(TagField.REPLAYGAIN_TRACK_PEAK),
                    gain(TagField.REPLAYGAIN_ALBUM_GAIN), peak(TagField.REPLAYGAIN_ALBUM_PEAK),
                ),
                stream = Stream(rendition.blob, rendition.format, musicFolder?.let { "${it.trimEnd('/')}/$relative.${rendition.format}" }),
            )
        }

        /** Server tracks' IDs: not a file path, so they're never in the Folders tab. */
        const val REMOTE_PREFIX = "mediasync:"

        /** Cover keys of pictures still on the server: fetched the first time they're shown. */
        const val REMOTE_COVER = "r"
    }
}
