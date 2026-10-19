package com.tlisonbee.mediasync.library

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.sync.FolderConfig
import com.tlisonbee.mediasync.sync.LocalFiles
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamesTest {
    @Test
    fun `a kind from a newer server reads as a plain file`() {
        val json = """{"assetId":"a","rev":1,"deleted":false,"path":"x/y","kind":"hologram","renditions":[],"tags":{},"pictures":[]}"""
        assertEquals(AssetKind.OTHER, ApiJson.decodeFromString(AssetDto.serializer(), json).kind)
        assertEquals(AssetKind.GAME, AssetKind.of("game"))
        assertTrue("\"kind\":\"game\"" in ApiJson.encodeToString(AssetDto.serializer(), ApiJson.decodeFromString(AssetDto.serializer(), json.replace("hologram", "game"))))
    }

    @Test
    fun `game files keep their names, extension and all`() {
        val games = FolderConfig("games/", "/home/me/Games")
        val cue = LocalFiles.repoPath(games, Path.of("/home/me/Games/PS1/Final Fantasy VII (Disc 1).cue"))
        val bin = LocalFiles.repoPath(games, Path.of("/home/me/Games/PS1/Final Fantasy VII (Disc 1).bin"))
        assertEquals("games/PS1/Final Fantasy VII (Disc 1).cue", cue)
        assertTrue(cue != bin, "a .cue and its .bin don't share a path")
        assertEquals(Path.of("/home/me/Games/PS1/Final Fantasy VII (Disc 1).cue"), LocalFiles.localPath(games, cue, "cue"))

        val music = FolderConfig("music/", "/home/me/Music")
        assertEquals("music/a/b", LocalFiles.repoPath(music, Path.of("/home/me/Music/a/b.flac")))
        assertEquals(Path.of("/home/me/Music/a/b.flac"), LocalFiles.localPath(music, "music/a/b", "flac"))
    }

    @Test
    fun `games are filed in their game's folder under their own names`() {
        val tags = mapOf("title" to listOf("Metal Gear Solid 3: Snake Eater"), "region" to listOf("USA"))
        assertEquals("games/PlayStation 2/Metal Gear Solid 3 - Snake Eater (USA)/mgs3.bin", RepoPaths.game(tags, "ps2", "games/Backups/mgs3.bin"))
        assertEquals("games/Super NES/zelda/zelda.sfc", RepoPaths.game(emptyMap(), "snes", "games/zelda.sfc"), "no title yet: the file's name")
        assertNull(RepoPaths.game(tags, null, "games/x.iso"), "an unknown platform keeps its place")
        assertTrue(RepoPaths.followsFields(AssetKind.GAME, "games/x.iso", "ps2"))
        assertFalse(RepoPaths.followsFields(AssetKind.GAME, "games/x.iso", null))
        assertEquals("games/PlayStation 2/Shadow of the Colossus (USA)", RepoPaths.gameFolder("games/PlayStation 2/Shadow of the Colossus (USA)/sotc.iso"))
    }
}
