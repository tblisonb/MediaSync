package com.tlisonbee.mediasync.player.tidal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidalCatalogTest {
    /** A favorites page as TIDAL sends it (trimmed): linkage with meta, included tracks, albums, artists and artwork. */
    private val page = """
        {"data":[{"id":"58990486","type":"tracks","meta":{"addedAt":"2026-10-01T10:00:00Z","itemId":"e1"}},
                 {"id":"999","type":"videos"}],
         "links":{"meta":{"nextCursor":"abc"}},
         "included":[
          {"id":"58990486","type":"tracks","attributes":{"title":"Creep","version":"Remastered","duration":"PT3M58S","isrc":"GBAYE9200070"},
           "relationships":{"artists":{"data":[{"id":"64518","type":"artists"}]},"albums":{"data":[{"id":"58990484","type":"albums"}]}}},
          {"id":"64518","type":"artists","attributes":{"name":"Radiohead"}},
          {"id":"58990484","type":"albums","attributes":{"title":"Pablo Honey","releaseDate":"1993-02-22","type":"ALBUM"},
           "relationships":{"coverArt":{"data":[{"id":"art1","type":"artworks"}]},"artists":{"data":[{"id":"64518","type":"artists"}]}}},
          {"id":"art1","type":"artworks","attributes":{"files":[{"href":"https://resources.tidal.com/images/1a2b3c4d/5e6f/7a8b/9c0d/1e2f3a4b5c6d/1280x1280.jpg"}]}}
         ]}
    """.trimIndent()

    @Test
    fun aPageOfFavoriteTracks() {
        val parsed = TidalCatalog.parsePage(Json.parseToJsonElement(page).jsonObject)
        assertEquals("abc", parsed.nextCursor)
        val item = parsed.items.single() // the video is left out
        assertEquals("Creep (Remastered)", item.title)
        assertEquals("Radiohead", item.artist)
        assertEquals("Pablo Honey", item.album)
        assertEquals(238_000L, item.durationMs)
        assertEquals("e1", item.playlistItemId)
        assertEquals("GBAYE9200070", item.isrc)
        val track = item.track!!
        assertEquals("tidal:58990486", track.path)
        assertEquals("Radiohead", track.albumArtist)
        assertEquals("1993", track.year)
        assertEquals("t1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d", item.cover)
        assertEquals("https://resources.tidal.com/images/1a2b3c4d/5e6f/7a8b/9c0d/1e2f3a4b5c6d/640x640.jpg", TidalCatalog.coverUrl(item.cover!!))
    }

    @Test
    fun durations() {
        assertEquals(239_000L, TidalCatalog.durationMs("PT3M59S"))
        assertEquals(3_723_500L, TidalCatalog.durationMs("PT1H2M3.5S"))
        assertNull(TidalCatalog.durationMs("3:59"))
    }

    @Test
    fun pkceChallengeIsTheVerifiersSha256InBase64Url() {
        // Worked out separately with Python's hashlib and base64.urlsafe_b64encode.
        assertEquals("Ei2zyVe0xV1I1kd6SeGsjTcqNUOM8CCKbZit1mYqQcY", TidalLogin.challenge("dBjftJeZ4CVP-mJ92K9qp6TQmXp9Ob1G1i0iBYmVYwY"))
    }

    @Test
    fun theTracksSeenAreKept() {
        val file = java.io.File.createTempFile("tidal", ".json")
        try {
            val track = TidalCatalog.parsePage(Json.parseToJsonElement(page).jsonObject).items.single().track!!
            TidalTracks(file).apply { remember(listOf(track)); save() }
            assertEquals(track, TidalTracks(file).get("tidal:58990486"))
        } finally {
            file.delete()
        }
    }
}

class TidalCacheTest {
    @Test
    fun listsAreKeptPerAccount() {
        val dir = kotlin.io.path.createTempDirectory("tidal").toFile()
        try {
            java.io.File(dir, "account.json").writeText("""{"client_id":"c","refresh_token":"r","user_id":"42"}""")
            val cache = TidalCache(java.io.File(dir, "cache"), TidalSession(java.io.File(dir, "account.json"), null))
            val doc = Json.parseToJsonElement("""{"data":[{"id":"1","type":"artists"}],"included":[{"id":"1","type":"artists","attributes":{"name":"Boston"}}]}""").jsonObject
            cache.write(TidalList.FAVORITE_ARTISTS, null, "v1", listOf(doc))
            val back = cache.read(TidalList.FAVORITE_ARTISTS, null)!!
            assertEquals("v1", back.version)
            assertEquals(listOf("Boston"), back.items.map { it.title })
            assert(java.io.File(dir, "cache/42/favorite-artists.json").isFile)
            assertNull(cache.read(TidalList.FAVORITE_TRACKS, null))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun comparableText() {
        assertEquals("beyonce", TidalCatalog.comparable("Beyoncé"))
        assertEquals("dontlookbackinanger", TidalCatalog.comparable("Don't Look Back in Anger"))
    }
}

/** A real search reply (2026-11-04, "radiohead creep", with the includes Segue asks for). */
class TidalRealSearchTest {
    @Test
    fun parsesWhatTidalSends() {
        val text = javaClass.getResource("/tidal/search-creep.json")!!.readText()
        val results = TidalCatalog.parseSearch(Json.parseToJsonElement(text).jsonObject)
        val creep = results.tracks.first { it.id == "58990486" }
        assertEquals("Creep", creep.title.substringBefore(" ("))
        assert("Radiohead" in creep.artist) { creep.artist }
        assert(creep.album.isNotEmpty())
        assert((creep.durationMs ?: 0) in 230_000L..245_000L) { "${creep.durationMs}" }
        assert(creep.cover?.startsWith("t") == true) { "no cover key" }
        assert(results.tracks.count { it.cover != null } >= results.tracks.size / 2)
        assert(results.albums.isNotEmpty() && results.playlists.isNotEmpty()) // (this reply lists no artists)
        assert(results.albums.all { it.cover != null || it.title.isNotEmpty() })
    }
}
