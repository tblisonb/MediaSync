package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PushChange
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistsTest {
    private val moves = mapOf("music/Boston/Third Stage/05 My Destination" to "music/Boston/Third Stage/05 My Destination (Remastered)")

    @Test
    fun entriesNamingAMovedFileFollowIt() {
        val text = "#EXTM3U\r\n#EXTINF:220,Boston - My Destination\r\n../Boston/Third Stage/05 My Destination.mp3\r\n../Boston/Third Stage/06 A New World.mp3\r\n"
        assertEquals(
            "#EXTM3U\r\n#EXTINF:220,Boston - My Destination\r\n../Boston/Third Stage/05 My Destination (Remastered).mp3\r\n../Boston/Third Stage/06 A New World.mp3\r\n",
            Playlists.rewrite(text, "music/Playlists", moves),
        )
        // Beside the tracks, with Windows separators, and in a playlist at the top of music/.
        assertEquals("05 My Destination (Remastered).flac", Playlists.rewrite("05 My Destination.flac", "music/Boston/Third Stage", moves))
        assertEquals("..\\Boston\\Third Stage\\05 My Destination (Remastered).mp3", Playlists.rewrite("..\\Boston\\Third Stage\\05 My Destination.mp3", "music/Playlists", moves))
        assertEquals("Boston/Third Stage/05 My Destination (Remastered).mp3", Playlists.rewrite("Boston/Third Stage/05 My Destination.mp3", "music", moves))
        // Absolute paths, URLs and other files are left alone.
        assertNull(Playlists.rewrite("/storage/emulated/0/Music/Boston/Third Stage/05 My Destination.mp3\nhttp://x/y.mp3\n../Other.mp3", "music/Playlists", moves))
    }

    @Test
    fun aRetagThatRenamesATrackRewritesThePlaylistInTheSameRevision() = withServer {
        val phone = pair("Phone").apply { subscribeMusic() }
        val track = phone.add(TestDevice.fakeFile("dest.mp3"), track("My Destination", 5, album = "Third Stage", artist = "Boston"))
        assertEquals("music/Boston/Third Stage/05 My Destination", track.path)
        val text = "#EXTM3U\n#EXTINF:220,Boston - My Destination\n../Boston/Third Stage/05 My Destination.mp3\n"
        val file = TestDevice.file("Road Trip.m3u8", text.toByteArray())
        phone.upload(file)
        val playlist = phone.push(PushChange(path = "music/Playlists/Road Trip", kind = AssetKind.OTHER, mediaHash = file.mediaHash, sourcePath = "/x/Road Trip.m3u8"))
            .results.single().asset

        phone.push(phone.retag(track.assetId, track("My Destination (Remastered)", 5, album = "Third Stage", artist = "Boston")))
        val moved = repo.headVersion(track.assetId)!!
        assertEquals("music/Boston/Third Stage/05 My Destination (Remastered)", moved.path)
        val now = repo.headVersion(playlist.assetId)!!
        assertEquals(moved.rev, now.rev, "one revision")
        val media = repo.media(now.renditions.single())!!
        assertEquals("m3u8", media.format)
        assertEquals(
            "#EXTM3U\n#EXTINF:220,Boston - My Destination\n../Boston/Third Stage/05 My Destination (Remastered).mp3\n",
            Files.readString(repo.dir.blob(media.baseBlob)),
        )
    }
}
