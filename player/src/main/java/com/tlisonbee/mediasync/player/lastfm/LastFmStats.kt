package com.tlisonbee.mediasync.player.lastfm

import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The periods Last.fm's top lists cover. */
enum class LastFmPeriod(val label: String, val param: String) {
    WEEK("Last 7 days", "7day"),
    MONTH("Last 30 days", "1month"),
    QUARTER("Last 90 days", "3month"),
    HALF("Last 180 days", "6month"),
    YEAR("Last 365 days", "12month"),
    ALL("All time", "overall"),
}

/** An entry of a stats list: a track, album or artist. */
data class LastFmEntry(
    val name: String,
    /** The artist, for a track or album. */
    val artist: String = "",
    /** For a recent track. */
    val album: String = "",
    val plays: Int? = null,
    /** Last.fm's image, if it has a real one. */
    val image: String? = null,
    /** For a recent track: when it was scrobbled (seconds), or null while it's playing now. */
    val time: Long? = null,
    val nowPlaying: Boolean = false,
)

class LastFmProfile(val user: String, val scrobbles: Long, val since: Long?, val artists: Long?, val tracks: Long?)

class LastFmPage(val entries: List<LastFmEntry>, val page: Int, val totalPages: Int)

/** Reading your Last.fm stats. Blocking; call it off the main thread. */
class LastFmStats(private val lastFm: LastFm) {
    private fun api() = lastFm.api() ?: throw IOException("Log in to Last.fm in Settings first.")
    private fun user() = lastFm.user ?: throw IOException("Log in to Last.fm in Settings first.")

    fun profile(): LastFmProfile {
        val u = api().call("user.getInfo", mapOf("user" to user())).obj("user") ?: throw IOException("Last.fm sent no profile")
        return LastFmProfile(
            u.string("name") ?: user(),
            u.string("playcount")?.toLongOrNull() ?: 0,
            u.obj("registered")?.let { it.string("unixtime") ?: it.string("#text") }?.toLongOrNull(),
            u.string("artist_count")?.toLongOrNull(),
            u.string("track_count")?.toLongOrNull(),
        )
    }

    fun recent(page: Int = 1): LastFmPage {
        val doc = api().call("user.getRecentTracks", mapOf("user" to user(), "limit" to "50", "page" to page.toString())).obj("recenttracks")
        return LastFmPage(doc?.get("track").list().map { parseRecent(it) }, page, pages(doc))
    }

    fun top(kind: Kind, period: LastFmPeriod, page: Int = 1): LastFmPage {
        val (method, root, item) = when (kind) {
            Kind.ARTISTS -> Triple("user.getTopArtists", "topartists", "artist")
            Kind.ALBUMS -> Triple("user.getTopAlbums", "topalbums", "album")
            Kind.TRACKS -> Triple("user.getTopTracks", "toptracks", "track")
        }
        val doc = api().call(method, mapOf("user" to user(), "period" to period.param, "limit" to "50", "page" to page.toString())).obj(root)
        return LastFmPage(doc?.get(item).list().map { parseTop(it, kind) }, page, pages(doc))
    }

    enum class Kind { ARTISTS, ALBUMS, TRACKS }

    private val playCounts = ConcurrentHashMap<String, Int>()

    private fun countKey(artist: String, track: String) = artist.lowercase() + "\u0000" + track.lowercase()

    /** How many times you've scrobbled a track (cached for the session); null if Last.fm doesn't know it. */
    fun plays(artist: String, track: String): Int? {
        val key = countKey(artist, track)
        playCounts[key]?.let { return it }
        val t = try {
            api().call("track.getInfo", mapOf("artist" to artist, "track" to track, "username" to user(), "autocorrect" to "1")).obj("track")
        } catch (e: LastFmException) {
            if (e.code == 6) return null // not on Last.fm
            throw e
        }
        val n = t?.string("userplaycount")?.toIntOrNull() ?: return null
        playCounts[key] = n
        return n
    }

    /** Counts a scrobble just sent in the cached count, without asking Last.fm again. */
    fun counted(artist: String, track: String) {
        playCounts.computeIfPresent(countKey(artist, track)) { _, n -> n + 1 }
    }

    fun cachedPlays(artist: String, track: String): Int? = playCounts[countKey(artist, track)]

    companion object {
        /** The grey star Last.fm sends for artists (and many tracks) since it stopped serving their images. */
        private const val PLACEHOLDER = "2a96cbd8b46e442fc41c2b86b821562f"

        private fun pages(doc: JsonObject?) = doc?.obj("@attr")?.string("totalPages")?.toIntOrNull() ?: 1

        internal fun image(o: JsonObject): String? =
            o["image"].list().lastOrNull { !it.string("#text").isNullOrEmpty() }?.string("#text")?.takeIf { PLACEHOLDER !in it }

        internal fun parseRecent(t: JsonObject) = LastFmEntry(
            name = t.string("name").orEmpty(),
            artist = t.string("artist").orEmpty(),
            album = t.string("album").orEmpty(),
            image = image(t),
            time = t.obj("date")?.string("uts")?.toLongOrNull(),
            nowPlaying = t.obj("@attr")?.string("nowplaying") == "true",
        )

        internal fun parseTop(o: JsonObject, kind: Kind) = LastFmEntry(
            name = o.string("name").orEmpty(),
            artist = if (kind == Kind.ARTISTS) "" else (o.obj("artist")?.string("name") ?: o.string("artist")).orEmpty(),
            plays = o.string("playcount")?.toIntOrNull(),
            image = if (kind == Kind.ARTISTS) null else image(o),
        )
    }
}

/** Last.fm's images as covers: keys `l<md5 of the address>`, the address remembered while the app runs. */
object LastFmImages {
    const val PREFIX = "l"
    private val urls = ConcurrentHashMap<String, String>()

    fun key(url: String): String {
        val k = PREFIX + java.security.MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        urls[k] = url
        return k
    }

    fun url(key: String): String? = urls[key]
}
