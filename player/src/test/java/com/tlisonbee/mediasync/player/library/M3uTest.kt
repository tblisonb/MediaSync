package com.tlisonbee.mediasync.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uTest {
    private val m = "/sdcard/Music"
    private val folder = "$m/Playlists"

    @Test
    fun parsesRelativeAbsoluteWindowsAndUrlEntries() {
        val text = "﻿#EXTM3U\n#EXTINF:245,Boston - More Than a Feeling\n../Boston/Boston/01 More Than a Feeling.mp3\r\n" +
            "\n# a comment\n/sdcard/Music/A/b.mp3\n..\\Windows\\Path.mp3\nfile:///sdcard/Music/With%20Space%2B.mp3\n"
        val entries = M3u.parse(text, folder)
        assertEquals(
            listOf("$m/Boston/Boston/01 More Than a Feeling.mp3", "$m/A/b.mp3", "$m/Windows/Path.mp3", "$m/With Space+.mp3"),
            entries.map { it.path },
        )
        assertEquals("Boston - More Than a Feeling", entries[0].title)
        assertEquals(245, entries[0].durationSec)
        assertNull(entries[1].title)
    }

    @Test
    fun writesEntriesRelativeToThePlaylist() {
        val t = track("$m/Boston/Third Stage/05 My Destination.mp3", "My Destination", "Boston")
        val text = M3u.write(listOf(M3u.entry(t)), folder)
        assertEquals("#EXTM3U\n#EXTINF:200,Boston - My Destination\n../Boston/Third Stage/05 My Destination.mp3\n", text)
        assertEquals(listOf(M3u.entry(t)), M3u.parse(text, folder))
    }

    @Test
    fun aMovedTrackIsFoundByItsTitle() {
        val moved = track("$m/Boston/Third Stage/05 My Destination (Remastered).mp3", "My Destination", "Boston")
        val data = LibraryData(listOf(moved))
        val entry = M3u.Entry("$m/Boston/Third Stage/05 My Destination.mp3", "boston - my destination", 200)
        assertEquals(moved, M3u.match(entry, data))
        assertNull(M3u.match(entry.copy(title = null), data))
    }
}

class M3uTidalTest {
    @Test
    fun tidalEntriesAsTheFooyinPluginWritesThem() {
        val text = "#EXTM3U\n#EXTINF:238,Radiohead - Creep\ntidal://track/58990486\n../Boston/More.mp3\n"
        val entries = M3u.parse(text, "/sdcard/Music/Playlists")
        assertEquals("tidal:58990486", entries[0].path)
        assertEquals("/sdcard/Music/Boston/More.mp3", entries[1].path)
        assertEquals(text, M3u.write(entries, "/sdcard/Music/Playlists"))
        // Not seen before: made from its #EXTINF line, and it plays by its ID.
        val t = M3u.match(entries[0], LibraryData.EMPTY)!!
        assertEquals("Creep", t.title)
        assertEquals(listOf("Radiohead"), t.artists)
        assertEquals(238_000L, t.durationMs)
        assert(t.isTidal)
        // Known: the full track.
        val known = t.copy(album = "Pablo Honey")
        assertEquals(known, M3u.match(entries[0], LibraryData.EMPTY) { known })
    }
}
