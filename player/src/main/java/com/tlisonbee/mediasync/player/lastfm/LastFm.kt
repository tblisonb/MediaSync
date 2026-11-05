package com.tlisonbee.mediasync.player.lastfm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** One listen, as Last.fm takes it. [timestamp] is when it started playing, in seconds since 1970 (UTC). */
@Serializable
data class Scrobble(
    val artist: String,
    val track: String,
    val album: String = "",
    val albumArtist: String = "",
    val durationSec: Int? = null,
    val trackNumber: Int? = null,
    val timestamp: Long = 0,
) {
    /** The parameters for track.scrobble's entry [i], or (without [i]) for track.updateNowPlaying. */
    fun params(i: Int? = null): Map<String, String> {
        fun k(name: String) = if (i == null) name else "$name[$i]"
        return buildMap {
            put(k("artist"), artist)
            put(k("track"), track)
            if (album.isNotEmpty()) put(k("album"), album)
            if (albumArtist.isNotEmpty() && albumArtist != artist) put(k("albumArtist"), albumArtist)
            durationSec?.let { put(k("duration"), it.toString()) }
            trackNumber?.let { put(k("trackNumber"), it.toString()) }
            if (i != null) put(k("timestamp"), timestamp.toString())
        }
    }
}

@Serializable
private data class Saved(val apiKey: String = "", val secret: String = "", val sessionKey: String = "", val user: String = "", val queue: List<Scrobble> = emptyList())

/** What Settings shows. */
data class LastFmState(val apiKey: String = "", val user: String? = null, val pending: Int = 0, val error: String? = null)

/**
 * Last.fm: the login (your own API account's key and secret, and the session key Last.fm gives for
 * it, which doesn't expire), scrobbles waiting to be sent (kept on disk, so a listen offline is
 * sent later), and reading your stats. In a file only this app can read.
 */
class LastFm(private val dir: File, private val scope: CoroutineScope) {
    private val file = File(dir, "lastfm.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var saved: Saved = runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrNull() ?: Saved()
    private val sending = Mutex()
    private var retry: Job? = null

    private val _state = MutableStateFlow(state())
    val state: StateFlow<LastFmState> = _state.asStateFlow()
    private var lastError: String? = null

    private fun state() = synchronized(this) { LastFmState(saved.apiKey, saved.user.takeIf { saved.sessionKey.isNotEmpty() }, saved.queue.size, lastError) }

    private fun changed() {
        _state.value = state()
    }

    @Synchronized
    private fun save() {
        dir.mkdirs()
        val tmp = File(dir, "lastfm.json.tmp")
        tmp.writeText(json.encodeToString(saved))
        tmp.renameTo(file)
    }

    val isLoggedIn: Boolean get() = synchronized(this) { saved.sessionKey.isNotEmpty() }

    val user: String? get() = state.value.user

    /** For reading: only the API key is needed. */
    fun api(): LastFmApi? = synchronized(this) { saved.apiKey.takeIf { it.isNotEmpty() }?.let { LastFmApi(it, saved.secret) } }

    // The login: a token, approved by you on Last.fm's page, then swapped for a session key.

    class Login(val api: LastFmApi, val token: String) {
        val authorizeUrl: String get() = LastFmApi.authorizeUrl(api.apiKey, token)
    }

    /** Starts a login with your API account's key and secret (blocking). */
    fun startLogin(apiKey: String, secret: String): Login {
        val api = LastFmApi(apiKey.trim(), secret.trim())
        val token = api.call("auth.getToken", emptyMap(), signed = true).string("token") ?: throw IOException("Last.fm sent no token")
        return Login(api, token)
    }

    /** The session, once you've approved the token; null while you haven't yet (blocking). */
    fun finishLogin(login: Login, secret: String): String? {
        val doc = try {
            login.api.call("auth.getSession", mapOf("token" to login.token), signed = true)
        } catch (e: LastFmException) {
            if (e.code == 14) return null // not approved yet
            throw e
        }
        val session = doc.obj("session") ?: throw IOException("Last.fm sent no session")
        val key = session.string("key") ?: throw IOException("Last.fm sent no session key")
        val name = session.string("name").orEmpty()
        synchronized(this) {
            saved = saved.copy(apiKey = login.api.apiKey, secret = secret.trim(), sessionKey = key, user = name)
            lastError = null
            save()
        }
        changed()
        flush()
        return name
    }

    fun logOut() {
        synchronized(this) {
            saved = saved.copy(sessionKey = "", user = "")
            lastError = null
            save()
        }
        changed()
    }

    // Scrobbling.

    /** Tells Last.fm what's playing now (shown on your profile until it ends); best effort. */
    fun nowPlaying(s: Scrobble) {
        val (api, sk) = synchronized(this) { (api() ?: return) to saved.sessionKey.ifEmpty { return } }
        scope.launch(Dispatchers.IO) {
            runCatching { api.call("track.updateNowPlaying", s.params() + ("sk" to sk), signed = true, post = true) }
        }
    }

    /** Keeps [s] to send, and sends what's waiting. */
    fun scrobble(s: Scrobble) {
        synchronized(this) {
            // Last.fm ignores scrobbles older than two weeks; the queue doesn't grow without end.
            val cutoff = s.timestamp - 14 * 24 * 3600
            saved = saved.copy(queue = (saved.queue + s).filter { it.timestamp > cutoff }.takeLast(MAX_QUEUE))
            save()
        }
        changed()
        flush()
    }

    /** Sends what's waiting, 50 at a time; on a network or temporary error, tries again later. */
    fun flush() {
        scope.launch(Dispatchers.IO) {
            sending.withLock {
                while (true) {
                    val (batch, api, sk) = synchronized(this@LastFm) {
                        Triple(saved.queue.take(BATCH), api(), saved.sessionKey)
                    }
                    if (batch.isEmpty() || api == null || sk.isEmpty()) break
                    try {
                        val params = HashMap<String, String>()
                        batch.forEachIndexed { i, s -> params += s.params(i) }
                        params["sk"] = sk
                        api.call("track.scrobble", params, signed = true, post = true)
                        // Accepted or ignored (Last.fm's call, e.g. for a too-old timestamp): either way it's done.
                        synchronized(this@LastFm) {
                            saved = saved.copy(queue = saved.queue.drop(batch.size))
                            lastError = null
                            save()
                        }
                        changed()
                    } catch (e: LastFmException) {
                        if (e.loggedOut) {
                            synchronized(this@LastFm) { saved = saved.copy(sessionKey = ""); save() }
                        }
                        fail("Last.fm: ${e.message}")
                        if (e.temporary) later()
                        break
                    } catch (e: IOException) {
                        fail(e.message ?: "Couldn't reach Last.fm")
                        later()
                        break
                    }
                }
            }
        }
    }

    private fun fail(message: String) {
        synchronized(this) { lastError = message }
        changed()
    }

    private fun later() {
        retry?.cancel()
        retry = scope.launch {
            delay(RETRY_MS)
            flush()
        }
    }

    // Stats.

    val stats by lazy { LastFmStats(this) }

    companion object {
        const val BATCH = 50
        const val MAX_QUEUE = 5000
        const val RETRY_MS = 5 * 60_000L

        /** Last.fm's minimum: tracks shorter than this aren't scrobbled. */
        const val MIN_DURATION_MS = 30_000L

        /** A track counts once it's played for half its length or four minutes, whichever comes first. */
        fun thresholdMs(durationMs: Long): Long = minOf(durationMs / 2, 4 * 60_000L)
    }
}
