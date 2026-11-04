package com.tlisonbee.mediasync.player.library

import com.tlisonbee.mediasync.player.GainMode
import com.tlisonbee.mediasync.tags.AudioTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.math.abs

fun track(
    path: String,
    title: String,
    artist: String,
    album: String = "",
    albumArtist: String = "",
    no: Int? = null,
    disc: Int? = null,
    date: String = "",
    artists: List<String> = listOf(artist),
) = Track(path, 1, 1, title, artists.filter { it.isNotEmpty() }, album, albumArtist, date, "", no, disc, 200_000, null)

class LibraryDataTest {
    private val m = "/sdcard/Music"
    private val data = LibraryData(
        listOf(
            track("$m/The Beatles/Abbey Road/02 Something.mp3", "Something", "The Beatles", "Abbey Road", "The Beatles", 2, date = "1969-09-26"),
            track("$m/The Beatles/Abbey Road/01 Come Together.mp3", "Come Together", "The Beatles", "Abbey Road", "The Beatles", 1, date = "1969"),
            track("$m/The Beatles/Help!/01 Help!.mp3", "Help!", "The Beatles", "Help!", "The Beatles", 1, date = "1965"),
            track("$m/Boston/Boston/1-02 Peace of Mind.mp3", "Peace of Mind", "Boston", "Boston", "", 2, 1),
            track("$m/Boston/Boston/2-01 More.mp3", "More", "Boston", "Boston", "", 1, 2),
            track("$m/Various Artists/Hits/01 Hit.mp3", "Hit", "", "Hits", "Various Artists", 1, artists = listOf("Björk", "Boston")),
            track("$m/loose.mp3", "loose", ""),
        ),
    )

    @Test
    fun albumsAreGroupedByAlbumArtistAndSortedByTrack() {
        val abbey = data.albums.single { it.title == "Abbey Road" }
        assertEquals(listOf("Come Together", "Something"), abbey.tracks.map { it.title })
        assertEquals("1969", abbey.year)
        val boston = data.albums.single { it.title == "Boston" }
        assertEquals(listOf("Peace of Mind", "More"), boston.tracks.map { it.title })
        assertEquals(2, boston.discs)
        assertEquals(Track.UNKNOWN_ALBUM, data.albums.single { it.tracks.any { t -> t.title == "loose" } }.title)
    }

    @Test
    fun artistsIgnoreTheAndListGuestTracks() {
        assertEquals(listOf("The Beatles", "Björk", "Boston", "Unknown artist", "Various Artists"), data.artists.map { it.name })
        val beatles = data.artist("the beatles")!!
        assertEquals(listOf("Help!", "Abbey Road"), beatles.albums.map { it.title })
        val boston = data.artist("Boston")!!
        assertEquals(listOf("Hit"), boston.appearsOn.map { it.title })
        assertEquals(3, boston.trackCount)
        assertEquals(listOf("Hit"), data.artist("bjork")!!.appearsOn.map { it.title })
    }

    @Test
    fun foldersListSubfoldersAndTracks() {
        val root = data.folder(m)
        assertEquals(listOf("Boston", "The Beatles", "Various Artists"), root.folders.map { it.name })
        assertEquals(3, root.folders[1].trackCount)
        assertEquals(listOf("loose"), root.tracks.map { it.title })
        assertEquals(listOf("Abbey Road", "Help!"), data.folder("$m/The Beatles").folders.map { it.name })
    }

    @Test
    fun searchMatchesEveryWordAnywhereIgnoringAccents() {
        val r = data.search("beatles some")
        assertEquals(listOf("Something"), r.tracks.map { it.title })
        assertEquals(listOf("Björk"), data.search("BJORK").artists.map { it.name })
        assertEquals(listOf("Hits"), data.search("hits").albums.map { it.title })
        assert(data.search("  ").isEmpty)
    }

    @Test
    fun numbersAndYears() {
        assertEquals(3, Track.number("3/12"))
        assertEquals(7, Track.number(" 07 "))
        assertNull(Track.number("A1"))
        assertEquals("1969", data.tracks.first { it.title == "Something" }.year)
    }

    @Test
    fun replayGainTagsAreRead() {
        val flac = AudioTags.inspect(File("src/test/resources/rg.flac").toPath()).replayGain
        assertEquals("-3.50 dB", flac["REPLAYGAIN_TRACK_GAIN"])
        assertEquals("0.988", flac["REPLAYGAIN_ALBUM_PEAK"])
        assertEquals(4, flac.size)
        val v23 = AudioTags.inspect(File("src/test/resources/rg-v23.mp3").toPath()).replayGain
        assertEquals(mapOf("REPLAYGAIN_TRACK_GAIN" to "-3.50 dB", "REPLAYGAIN_ALBUM_PEAK" to "0.988"), v23)
        val v24 = AudioTags.inspect(File("src/androidTest/assets/gapless/with-header-2.mp3").toPath()).replayGain
        assertEquals(mapOf("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB"), v24)
    }

    @Test
    fun gainModes() {
        val g = Gains(trackGain = -6.0206f, trackPeak = 0.5f, albumGain = 6.0206f, albumPeak = 0.9f)
        assertEquals(1f, GainMode.OFF.factor(g), 1e-4f)
        assertEquals(0.5f, GainMode.TRACK.factor(g), 1e-3f)
        // +6 dB would clip a 0.9 peak, so it's held to 1/0.9.
        assertEquals(1 / 0.9f, GainMode.ALBUM.factor(g), 1e-4f)
        assertEquals(0.5f, GainMode.ALBUM.factor(g.copy(albumGain = null)), 1e-3f)
        assert(abs(GainMode.TRACK.factor(Gains.NONE) - 1f) < 1e-6)
    }
}
