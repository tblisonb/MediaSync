package com.tlisonbee.mediasync.player.lastfm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayTrackerTest {
    private var now = 0L
    private val tracker = PlayTracker({ now }, { 1_700_000_000L + now / 1000 })
    private val song = Scrobble("Radiohead", "Creep", "Pablo Honey")

    @Test
    fun scrobblesAtHalfTheLengthOfPlayingNotOfSeeking() {
        tracker.start("a", song, 240_000)
        tracker.playing(true)
        assertEquals(song, tracker.announce())
        assertNull(tracker.announce())
        assertEquals(120_000L, tracker.msUntilDue())
        now += 60_000
        tracker.playing(false) // paused for a long time: it doesn't count
        now += 600_000
        assertNull(tracker.due())
        tracker.playing(true)
        now += 59_000
        assertNull(tracker.due())
        assertEquals(1_000L, tracker.msUntilDue())
        now += 1_000
        val s = tracker.due()!!
        assertEquals(1_700_000_000L, s.timestamp) // when it started
        assertEquals(240, s.durationSec)
        assertNull(tracker.due()) // once
        assertNull(tracker.msUntilDue())
    }

    @Test
    fun longTracksAfterFourMinutesAndShortOnesNever() {
        tracker.start("long", song, 20 * 60_000)
        tracker.playing(true)
        assertEquals(4 * 60_000L, tracker.msUntilDue())
        tracker.start("short", song, 25_000)
        tracker.playing(true)
        now += 25_000
        assertNull(tracker.due())
        assertNull(tracker.msUntilDue())
    }

    @Test
    fun aRepeatIsANewListen() {
        tracker.start("a", song, 60_000)
        tracker.playing(true)
        now += 30_000
        assertEquals(song.copy(timestamp = 1_700_000_000L, durationSec = 60), tracker.due())
        now += 30_000
        tracker.start("a", song, 60_000)
        tracker.playing(true)
        now += 30_000
        assertEquals(1_700_000_060L, tracker.due()!!.timestamp)
    }
}

class LastFmApiTest {
    @Test
    fun signature() {
        // Sorted by name, run together, the secret after, MD5'd; format isn't signed. From Python's hashlib.
        assertEquals(
            "37d55b7ad890b24ee678f74fac66d197",
            LastFmApi.signature(mapOf("token" to "T", "method" to "auth.getSession", "api_key" to "K", "format" to "json"), "secret"),
        )
    }

    @Test
    fun batchParameters() {
        val s = Scrobble("Carpenter Brut", "Cherub Rock", "Cherub Rock", albumArtist = "Carpenter Brut", durationSec = 295, timestamp = 42)
        assertEquals(
            mapOf("artist[3]" to "Carpenter Brut", "track[3]" to "Cherub Rock", "album[3]" to "Cherub Rock", "duration[3]" to "295", "timestamp[3]" to "42"),
            s.params(3),
        )
        assertNull(s.params()["timestamp"])
    }

    @Test
    fun recentTracksAsLastFmSendsThem() {
        val doc = Json.parseToJsonElement(
            """{"recenttracks":{"track":[
              {"artist":{"#text":"STRFKR"},"name":"Isabella of Castile","album":{"#text":"Starfucker"},"@attr":{"nowplaying":"true"},
               "image":[{"size":"small","#text":"https://lastfm.freetls.fastly.net/i/u/34s/abc.jpg"},{"size":"extralarge","#text":"https://lastfm.freetls.fastly.net/i/u/300x300/abc.jpg"}]},
              {"artist":{"#text":"Ween"},"name":"The Wolfman","album":{"#text":""},"date":{"uts":"1762200000","#text":"03 Nov 2025"},
               "image":[{"size":"small","#text":"https://lastfm.freetls.fastly.net/i/u/34s/2a96cbd8b46e442fc41c2b86b821562f.png"}]}
            ],"@attr":{"page":"1","totalPages":"812"}}}""",
        ).jsonObject
        val tracks = doc.obj("recenttracks")!!["track"].list().map { LastFmStats.parseRecent(it) }
        assertEquals(LastFmEntry("Isabella of Castile", "STRFKR", "Starfucker", image = "https://lastfm.freetls.fastly.net/i/u/300x300/abc.jpg", nowPlaying = true), tracks[0])
        assertEquals(LastFmEntry("The Wolfman", "Ween", "", time = 1762200000), tracks[1]) // the grey star is no image
        // One entry comes as an object, not a list.
        val one = Json.parseToJsonElement("""{"artist":{"name":"Ween","playcount":"812"}}""").jsonObject
        assertEquals(listOf(LastFmEntry("Ween", plays = 812)), one["artist"].list().map { LastFmStats.parseTop(it, LastFmStats.Kind.ARTISTS) })
    }
}
