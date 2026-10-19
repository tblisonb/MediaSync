package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.FolderPickItem
import com.tlisonbee.mediasync.api.FolderPickRequest
import com.tlisonbee.mediasync.api.PickResponse
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.library.Platforms
import com.tlisonbee.mediasync.library.SubscriptionRule
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTest {
    // Shadow of the Colossus: a PS2 DVD dumped exactly as the Redump list has it.
    private val colossus = iso("BOOT2 = cdrom0:\\SCUS_974.72;1", raw = false)

    // Metal Gear Solid 3: a raw dump that isn't byte-for-byte Redump's, known by its serial.
    private val snakeEater = iso("BOOT2 = cdrom0:\\SLUS_208.13;1", raw = true)

    // Final Fantasy VII, disc 1: a PS1 .bin and its .cue, which no list has.
    private val ff7Bin = ByteArray(300_000) { (it * 7).toByte() }
    private val ff7Cue = "FILE \"Final Fantasy VII (USA) (Disc 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n".toByteArray()

    // Chrono Trigger with a copier's 512-byte header, which the No-Intro list leaves out.
    private val chronoRom = ByteArray(4096) { (it * 13 + 1).toByte() }
    private val chronoSmc = ByteArray(512) + chronoRom

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun dat(vararg games: String) = "clrmamepro (\n\tname \"test\"\n)\n\n" + games.joinToString("\n")

    /** The lists and LaunchBox's database, as fetch-games-db would leave them. */
    private fun ServerFixture.installDatabases() {
        val work = Files.createTempDirectory("gamedb-test")
        val ps2 = work.resolve("ps2.dat").also {
            Files.writeString(it, dat(
                """game ( name "Shadow of the Colossus (USA)" region "USA" serial "SCUS-97472" rom ( name "Shadow of the Colossus (USA).iso" size ${colossus.size} sha1 ${sha1(colossus).uppercase()} serial "SCUS-97472" ) )""",
                """game ( name "Metal Gear Solid 3 - Snake Eater (USA)" region "USA" serial "SLUS-20813" rom ( name "Metal Gear Solid 3 - Snake Eater (USA).iso" size 1 sha1 0000000000000000000000000000000000000000 serial "SLUS-20813" ) )""",
            ))
        }
        val ps1 = work.resolve("ps1.dat").also {
            Files.writeString(it, dat("""game ( name "Final Fantasy VII (USA) (Disc 1)" region "USA" serial "SCUS-94163" rom ( name "Final Fantasy VII (USA) (Disc 1).bin" size ${ff7Bin.size} sha1 ${sha1(ff7Bin)} ) )"""))
        }
        val snes = work.resolve("snes.dat").also {
            Files.writeString(it, dat("""game ( name "Chrono Trigger (USA)" region "USA" rom ( name "Chrono Trigger (USA).sfc" size ${chronoRom.size} sha1 ${sha1(chronoRom)} ) )"""))
        }
        val zip = work.resolve("Metadata.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("Metadata.xml"))
            out.write(
                """
                <?xml version="1.0" standalone="yes"?>
                <LaunchBox>
                  <Game><Name>Shadow of the Colossus</Name><ReleaseDate>2005-10-18</ReleaseDate><MaxPlayers>1</MaxPlayers><DatabaseID>2240</DatabaseID>
                    <Platform>Sony Playstation 2</Platform><Genres>Action; Adventure</Genres><Developer>Team ICO</Developer><Publisher>Sony Computer Entertainment</Publisher></Game>
                  <Game><Name>Metal Gear Solid 3: Snake Eater</Name><ReleaseDate>2004-11-17</ReleaseDate><DatabaseID>100</DatabaseID>
                    <Platform>Sony Playstation 2</Platform><Genres>Action; Stealth</Genres><Developer>Konami</Developer><Publisher>Konami</Publisher></Game>
                  <Game><Name>FF7</Name><ReleaseDate>1997-09-07</ReleaseDate><DatabaseID>200</DatabaseID>
                    <Platform>Sony Playstation</Platform><Genres>Role-Playing</Genres><Developer>Square</Developer><Publisher>Sony Computer Entertainment America</Publisher></Game>
                  <Game><Name>Chrono Trigger</Name><ReleaseDate>1995-08-22</ReleaseDate><DatabaseID>300</DatabaseID>
                    <Platform>Super Nintendo Entertainment System</Platform><Genres>Role-Playing</Genres><Developer>Square</Developer><Publisher>Square</Publisher></Game>
                  <Game><Name>Shadow Hearts</Name><ReleaseDate>2001-06-28</ReleaseDate><DatabaseID>400</DatabaseID>
                    <Platform>Sony Playstation 2</Platform><Genres>Role-Playing</Genres><Publisher>Midway</Publisher></Game>
                  <GameAlternateName><AlternateName>Final Fantasy VII</AlternateName><DatabaseID>200</DatabaseID></GameAlternateName>
                </LaunchBox>
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        Files.createDirectories(repo.gameDb.root)
        GameDbFetch.build(
            repo.gameDb.index,
            mapOf(Platforms.byId("ps2")!! to ps2, Platforms.byId("ps1")!! to ps1, Platforms.byId("snes")!! to snes),
            zip,
        )
        work.toFile().deleteRecursively()
        // Identification runs in place, so each push's games are identified when it returns.
        repo.inBackground = { _, work -> work() }
    }

    private suspend fun ServerFixture.pc(): TestDevice = pair("PC").apply { subscribe(SubscriptionRule("games/", "/home/test/Games")) }

    /**
     * Pushes files found under /home/test/Games as new, in one push, as the desktop client does.
     * Identification has run by the time it returns; [now] shows where it left each one.
     */
    private suspend fun TestDevice.import(vararg files: Pair<String, ByteArray>): List<AssetDto> {
        val changes = files.map { (source, bytes) ->
            val file = TestDevice.file(source.substringAfterLast('/'), bytes)
            upload(file)
            // The client calls a disc image a plain file; the server knows better.
            PushChange(path = "games/$source", kind = AssetKind.OTHER, mediaHash = file.mediaHash, sourcePath = "/home/test/Games/$source")
        }
        return push(*changes.toTypedArray()).results.map { it.asset }
    }

    private fun ServerFixture.now(asset: AssetDto) = repo.headVersion(asset.assetId)!!

    @Test
    fun gamesAreIdentifiedAndFiledByTheirGame() = withServer {
        installDatabases()
        val pc = pc()
        val (sotc) = pc.import("PS2/sotc.iso" to colossus)
        assertEquals(AssetKind.GAME, now(sotc).kind)
        assertEquals("ps2", now(sotc).category)
        assertEquals("games/PlayStation 2/Shadow of the Colossus (USA)/sotc.iso", now(sotc).path)
        assertEquals(
            mapOf(
                "title" to listOf("Shadow of the Colossus"), "region" to listOf("USA"), "year" to listOf("2005"),
                "publisher" to listOf("Sony Computer Entertainment"), "developer" to listOf("Team ICO"), "genre" to listOf("Action", "Adventure"),
                "players" to listOf("1"), "serial" to listOf("SCUS-97472"), "release" to listOf("Shadow of the Colossus (USA)"),
            ),
            now(sotc).tags,
        )
        assertEquals(How.CHECKSUM, repo.gameIdents()[sotc.assetId])

        // Not the Redump dump, but its SYSTEM.CNF names the game; LaunchBox spells the title with a colon.
        val (mgs3) = pc.import("Backups/mgs3.bin" to snakeEater)
        assertEquals(How.SERIAL, repo.gameIdents()[mgs3.assetId])
        assertEquals("games/PlayStation 2/Metal Gear Solid 3 - Snake Eater (USA)/mgs3.bin", now(mgs3).path)
        assertEquals(listOf("Metal Gear Solid 3: Snake Eater"), now(mgs3).tags["title"])
        assertEquals(listOf("2004"), now(mgs3).tags["year"])

        // A .cue no list has goes with the disc it names; LaunchBox knows the game by another name.
        val (cue, bin) = pc.import("PS1/Final Fantasy VII (USA) (Disc 1).cue" to ff7Cue, "PS1/Final Fantasy VII (USA) (Disc 1).bin" to ff7Bin)
        // LaunchBox's own name for it is the title.
        val folder = "games/PlayStation/FF7 (USA)"
        assertEquals("$folder/Final Fantasy VII (USA) (Disc 1).bin", now(bin).path)
        assertEquals("$folder/Final Fantasy VII (USA) (Disc 1).cue", now(cue).path, "beside it, under its own name")
        assertEquals(How.SIBLING, repo.gameIdents()[cue.assetId])
        assertEquals(listOf("1"), now(bin).tags["discnumber"])
        assertEquals(listOf("FF7"), now(cue).tags["title"])
        assertEquals("ps1", now(cue).category)

        // The copier header doesn't hide a cartridge.
        val (chrono) = pc.import("SNES/Chrono Trigger (U) [!].smc" to chronoSmc)
        assertEquals("games/Super NES/Chrono Trigger (USA)/Chrono Trigger (U) [!].smc", now(chrono).path)

        // Homebrew no database knows: its extension says Game Boy Advance, and its name is its title.
        val (homebrew) = pc.import("homebrew.gba" to ByteArray(100) { 1 })
        assertEquals(How.UNKNOWN, repo.gameIdents()[homebrew.assetId])
        assertEquals("gba", now(homebrew).category)
        assertEquals("games/Game Boy Advance/homebrew/homebrew.gba", now(homebrew).path)

        // A device can't rename a filed game; a new title moves it.
        pc.push(pc.retag(sotc.assetId, now(sotc).tags).copy(path = "games/PlayStation 2/Shadow of the Colossus (USA)/renamed.iso"))
        assertEquals("games/PlayStation 2/Shadow of the Colossus (USA)/sotc.iso", now(sotc).path)
        repo.editTags(repo.webDeviceId(), "Retitled", mapOf(sotc.assetId to mapOf("title" to listOf("Wander and the Colossus"))))
        assertEquals("games/PlayStation 2/Wander and the Colossus (USA)/sotc.iso", now(sotc).path)
    }

    @Test
    fun withoutDatabasesGamesKeepTheirFirstGuess() = withServer {
        repo.inBackground = { _, work -> work() }
        val (sotc) = pc().import("PS2/Shadow of the Colossus (USA).iso" to colossus)
        assertEquals("games/PlayStation 2/Shadow of the Colossus (USA)/Shadow of the Colossus (USA).iso", now(sotc).path)
        assertEquals(How.UNKNOWN, repo.gameIdents()[sotc.assetId])
        // Once the databases are in, trying again finds it.
        installDatabases()
        repo.startIdentifying(repo.webDeviceId(), listOf(sotc.assetId))
        assertEquals(How.CHECKSUM, repo.gameIdents()[sotc.assetId])
        assertEquals(listOf("2005"), now(sotc).tags["year"])
    }

    @Test
    fun aDeviceKeepsTheGamesAndPlatformsPickedForIt() = withServer {
        installDatabases()
        val pc = pc()
        val (sotc) = pc.import("PS2/sotc.iso" to colossus)
        val (cue, bin) = pc.import("PS1/Final Fantasy VII (USA) (Disc 1).cue" to ff7Cue, "PS1/Final Fantasy VII (USA) (Disc 1).bin" to ff7Bin)
        val phone = pair("Phone", DeviceKind.ANDROID)
        phone.subscribe(SubscriptionRule("games/", "/sdcard/ROMs/MediaSync", pick = true))
        assertTrue(phone.pull().flatMap { it.assets }.isEmpty(), "nothing picked yet")

        val list = phone.get("/picks/folders?prefix=games/").body<List<FolderPickItem>>()
        assertEquals(
            listOf("PlayStation" to true, "FF7 (USA)" to false, "PlayStation 2" to true, "Shadow of the Colossus (USA)" to false),
            list.map { it.label to it.whole },
        )
        assertEquals(2, list.first { it.label == "FF7 (USA)" }.files)

        // A game: every file of it.
        val ff7 = list.first { it.label == "FF7 (USA)" }.folder
        val picked = phone.put("/picks/folders", FolderPickRequest(pick = listOf(ff7))).body<PickResponse>()
        assertEquals(setOf(cue.assetId, bin.assetId), picked.changed.toSet())
        assertEquals(setOf(cue.assetId, bin.assetId), phone.pull().flatMap { it.assets }.map { it.assetId }.toSet())

        // Still kept once a retitle renames its folder.
        repo.editTags(repo.webDeviceId(), "Retitled", listOf(cue, bin).associate { it.assetId to mapOf("title" to listOf("Final Fantasy VII")) })
        assertEquals("games/PlayStation/Final Fantasy VII (USA)", RepoPathsName.folder(now(bin).path))
        assertTrue(phone.get("/picks/folders").body<List<FolderPickItem>>().first { it.label == "Final Fantasy VII (USA)" }.picked)

        // A platform: its games now, and the ones added later.
        phone.put("/picks/folders", FolderPickRequest(pick = listOf("games/PlayStation 2")))
        phone.pull()
        assertTrue(sotc.assetId in phone.synced)
        val (mgs3) = pc.import("mgs3.bin" to snakeEater)
        phone.pull()
        assertTrue(mgs3.assetId in phone.synced, "a PlayStation 2 game added later comes too")

        // Unpicking the game leaves the phone's full listing without it.
        phone.put("/picks/folders", FolderPickRequest(unpick = listOf(RepoPathsName.folder(now(bin).path))))
        val after = phone.pull()
        assertTrue(after.first().reset)
        assertTrue(after.flatMap { it.assets }.none { it.assetId == bin.assetId || it.assetId == cue.assetId })
    }

    @Test
    fun theWebUiShowsEditsAndIdentifiesGames() = withServer {
        installDatabases()
        val (sotc) = pc().import("PS2/sotc.iso" to colossus)
        val (homebrew) = pc().import("homebrew.gba" to ByteArray(100) { 1 })
        val web = browser().also { it.submitForm("/login", io.ktor.http.parameters { append("password", ADMIN_PASSWORD) }) }
        val games = web.get("/games").bodyAsText()
        assertTrue("PlayStation 2" in games && "Game Boy Advance" in games)
        assertTrue("known dumps" in games, "the databases' state")

        val folder = "games/PlayStation 2/Shadow of the Colossus (USA)"
        assertTrue("Shadow of the Colossus" in web.get("/games/platform?folder=" + enc("games/PlayStation 2")).bodyAsText())
        val page = web.get("/games/game?folder=" + enc(folder) + "&q=shadow&platform=ps2").bodyAsText()
        assertTrue("matched by checksum" in page)
        assertTrue("Shadow Hearts" in page && "Use this" in page, "search results from LaunchBox")

        // Editing the details edits every file of the game.
        web.submitForm("/games/game", io.ktor.http.parameters {
            append("folder", folder)
            append("rev.${sotc.assetId}", now(sotc).rev.toString())
            append("platform", "ps2")
            append("title", "Shadow of the Colossus")
            append("region", "USA")
            append("year", "2006")
        })
        assertEquals(listOf("2006"), now(sotc).tags["year"])

        // Choosing a game from the database by hand.
        val homeFolder = RepoPathsName.folder(now(homebrew).path)
        web.submitForm("/games/game/use", io.ktor.http.parameters {
            append("folder", homeFolder)
            append("platform", "ps2")
            append("title", "Shadow Hearts")
        })
        assertEquals("ps2", now(homebrew).category)
        assertEquals(listOf("Midway"), now(homebrew).tags["publisher"])

        web.submitForm("/games/db/update", io.ktor.http.parameters { })
        assertTrue(Files.exists(repo.gameDb.request), "the downloader service is asked")
        assertTrue("Downloading the databases now" in web.get("/games").bodyAsText())
    }

    @Test
    fun namesSerialsAndListsAreRead() {
        assertEquals(GameGuess("Final Fantasy X", "USA", "1"), GameNames.guess("Final Fantasy X (USA) (Disc 1).iso"))
        assertEquals(GameGuess("zelda", "USA", null), GameNames.guess("zelda_(U)_[!].sfc"))
        assertEquals(GameGuess("Shadow of the Colossus", null, null), GameNames.guess("SCUS_974.72.Shadow of the Colossus.iso"))
        assertEquals("SLUS-20312", GameIndex.normalizeSerial("SLUS_203.12"))
        assertEquals(GameIndex.titleKey("Metal Gear Solid 3: Snake Eater"), GameIndex.titleKey("Metal Gear Solid 3 - Snake Eater"))
        assertEquals(GameIndex.titleKey("The Legend of Zelda"), GameIndex.titleKey("Legend of Zelda, The"))
        assertEquals("ps2", Platforms.guess("/home/me/Games/PS2/sotc.iso")?.id)
        assertEquals("snes", Platforms.guess("roms/chrono.sfc")?.id)
        assertNull(Platforms.guess("Downloads/thing.iso"))

        val dir = Files.createTempDirectory("iso-test")
        try {
            val iso = dir.resolve("a.iso").also { Files.write(it, colossus) }
            assertEquals(IsoSerial.Boot("SCUS_974.72", ps2 = true), IsoSerial.read(iso))
            val raw = dir.resolve("b.bin").also { Files.write(it, snakeEater) }
            assertEquals(IsoSerial.Boot("SLUS_208.13", ps2 = true), IsoSerial.read(raw))
            val ps1 = dir.resolve("c.bin").also { Files.write(it, iso("BOOT = cdrom:\\SCUS_941.63;1", raw = true)) }
            assertEquals(IsoSerial.Boot("SCUS_941.63", ps2 = false), IsoSerial.read(ps1))
            assertNull(IsoSerial.read(dir.resolve("d.bin").also { Files.write(it, ByteArray(100_000)) }))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    /**
     * A tiny ISO 9660 image holding only SYSTEM.CNF with [boot] in it, over a megabyte as real discs
     * are: in 2048-byte sectors, or [raw] 2352-byte Mode 2 sectors as a .bin dump has them.
     */
    private fun iso(boot: String, raw: Boolean): ByteArray {
        val sectors = 600
        val data = Array(sectors) { ByteArray(2048) }
        val cnf = "$boot\r\nVER = 1.00\r\nVMODE = NTSC\r\n".toByteArray()
        fun record(name: ByteArray, lba: Int, size: Int, dir: Boolean): ByteArray {
            val len = (33 + name.size).let { it + it % 2 }
            return ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0, len.toByte())
                putInt(2, lba)
                putInt(6, Integer.reverseBytes(lba))
                putInt(10, size)
                putInt(14, Integer.reverseBytes(size))
                put(25, if (dir) 2 else 0)
                put(32, name.size.toByte())
                name.forEachIndexed { i, b -> put(33 + i, b) }
            }.array()
        }
        data[16].apply {
            this[0] = 1
            "CD001".toByteArray().copyInto(this, 1)
            this[6] = 1
            record(byteArrayOf(0), 18, 2048, dir = true).copyInto(this, 156)
        }
        data[17].apply { this[0] = 0xFF.toByte(); "CD001".toByteArray().copyInto(this, 1) }
        var pos = 0
        for (r in listOf(record(byteArrayOf(0), 18, 2048, true), record(byteArrayOf(1), 18, 2048, true), record("SYSTEM.CNF;1".toByteArray(), 19, cnf.size, false))) {
            r.copyInto(data[18], pos)
            pos += r.size
        }
        cnf.copyInto(data[19])
        if (!raw) return data.reduce(ByteArray::plus)
        return data.map { sector ->
            ByteArray(2352).also { s ->
                for (i in 1..10) s[i] = 0xFF.toByte()
                s[15] = 2
                sector.copyInto(s, 24)
            }
        }.reduce(ByteArray::plus)
    }
}

/** Repository path helpers for these tests. */
private object RepoPathsName {
    fun folder(path: String) = com.tlisonbee.mediasync.library.RepoPaths.gameFolder(path)!!

}
