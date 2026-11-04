package com.tlisonbee.mediasync.player.tidal

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/** Whether each TIDAL login is there. */
data class TidalState(val account: Boolean = false, val playback: Boolean = false, val country: String? = null)

/**
 * TIDAL in the player: the two logins, the catalog, the tracks seen, and the streams. Playback
 * uses the private v1 API (the account's developer app gets only 30-second previews): a track's
 * `playbackinfo` gives a DASH manifest of FLAC segments, which ExoPlayer plays as it is.
 */
class Tidal(dir: File) {
    val account = TidalSession(File(dir, "account.json"), null)
    val playback = TidalSession(File(dir, "session.json"), TidalLogin.PLAYBACK_SCOPES)
    val catalog = TidalCatalog(account)
    val tracks = TidalTracks(File(dir, "tracks.json"))
    val cache = TidalCache(File(dir, "cache"), account)

    private val _state = MutableStateFlow(current())
    val state: StateFlow<TidalState> = _state.asStateFlow()

    private fun current() = TidalState(account.isLoggedIn, playback.isLoggedIn, account.countryCode.takeIf { account.isLoggedIn })

    /** After a login or logout. */
    fun changed() {
        _state.value = current()
    }

    /** The DASH manifest for a track, from v1. Blocking. */
    fun manifest(trackId: String): ByteArray {
        if (!playback.isLoggedIn) throw IOException("Log in to TIDAL for playback in Settings to play TIDAL tracks")
        val query = TidalHttp.encodeForm(
            mapOf(
                "audioquality" to "HI_RES_LOSSLESS", "playbackmode" to "STREAM", "assetpresentation" to "FULL",
                "countryCode" to playback.countryCode,
            ),
        )
        val result = TidalHttp.get(
            "https://api.tidal.com/v1/tracks/${TidalHttp.enc(trackId)}/playbackinfo?$query",
            mapOf("Authorization" to "Bearer ${playback.accessToken()}"),
        )
        if (!result.ok) throw IOException("TIDAL wouldn't stream track $trackId (HTTP ${result.status})")
        val info = Json.parseToJsonElement(result.text) as JsonObject
        if (info.string("assetPresentation") != "FULL") throw IOException("TIDAL offers only a preview of track $trackId")
        val manifest = Base64.decode(info.string("manifest") ?: "", Base64.DEFAULT)
        return when (val mime = info.string("manifestMimeType")) {
            "application/dash+xml" -> manifest
            else -> throw IOException("TIDAL sent a manifest the player can't read yet ($mime)")
        }
    }

    companion object {
        /** A TIDAL track's address for the player: its manifest comes from [TidalDataSource]. */
        fun uri(trackId: String) = "tidal://track/$trackId"
    }
}

/**
 * Answers `tidal://track/<id>` with the track's DASH manifest, fetched when the player gets to it
 * (ExoPlayer prepares playlist items lazily), so a long queue of TIDAL tracks doesn't ask for them
 * all at once, and the segments' signed addresses are fresh. Everything else goes to [other].
 */
@UnstableApi
class TidalDataSource(private val tidal: Tidal, private val other: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "tidal") {
            val id = dataSpec.uri.lastPathSegment ?: throw IOException("no track in ${dataSpec.uri}")
            ByteArrayDataSource(tidal.manifest(id))
        } else {
            other
        }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): android.net.Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun addTransferListener(transferListener: TransferListener) = other.addTransferListener(transferListener)

    override fun close() {
        current?.close()
        current = null
    }
}
