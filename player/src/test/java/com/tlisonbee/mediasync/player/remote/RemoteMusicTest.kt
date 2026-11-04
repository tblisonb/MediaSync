package com.tlisonbee.mediasync.player.remote

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.RenditionDto
import com.tlisonbee.mediasync.player.library.LibraryData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMusicTest {
    private val dto = AssetDto(
        assetId = "a1", rev = 7, deleted = false, path = "music/Boston/Third Stage/05 My Destination", kind = AssetKind.AUDIO,
        renditions = listOf(RenditionDto("m1", "b1", "mp3", 5_000_000, 221_000)),
        tags = mapOf(
            "title" to listOf("My Destination"), "artist" to listOf("Boston"), "album" to listOf("Third Stage"),
            "albumartist" to listOf("Boston"), "date" to listOf("1986"), "tracknumber" to listOf("5"),
            "replaygain_track_gain" to listOf("-6.02 dB"), "replaygain_album_peak" to listOf("0.988000"),
        ),
        pictures = listOf(PictureRef("front", "c1", "image/jpeg")),
    )

    @Test
    fun aServerTrackBecomesAStreamedTrack() {
        val t = RemoteMusic.toTrack(dto, "/storage/emulated/0/Music")!!
        assertEquals("mediasync:a1", t.path)
        assertEquals("My Destination", t.title)
        assertEquals("1986", t.year)
        assertEquals(5, t.trackNumber)
        assertEquals(221_000L, t.durationMs)
        assertEquals("rc1", t.cover)
        assertEquals(-6.02f, t.gain.trackGain!!, 1e-4f)
        assertEquals(0.988f, t.gain.albumPeak!!, 1e-4f)
        assertNull(t.gain.albumGain)
        assertEquals("b1", t.stream!!.blob)
        // Where MediaSync would put it, to tell whether the phone already has it.
        assertEquals("/storage/emulated/0/Music/Boston/Third Stage/05 My Destination.mp3", t.stream!!.localPath)
        assertNull(RemoteMusic.toTrack(dto.copy(renditions = emptyList()), null))
        assertNull(RemoteMusic.toTrack(dto, null)!!.stream!!.localPath)
    }

    @Test
    fun serverTracksJoinAlbumsButNotFolders() {
        val t = RemoteMusic.toTrack(dto, "/storage/emulated/0/Music")!!
        val data = LibraryData(listOf(t))
        assertEquals(listOf(t), data.albums.single().tracks)
        val root = data.folder("/storage/emulated/0/Music")
        assertEquals(0, root.tracks.size + root.folders.size)
        assertEquals(listOf(t), data.search("destination").tracks)
    }
}
