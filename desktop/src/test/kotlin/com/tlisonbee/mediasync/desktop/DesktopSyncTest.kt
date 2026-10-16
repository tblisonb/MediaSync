package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.ChangeType
import com.tlisonbee.mediasync.media.MediaHasher
import com.tlisonbee.mediasync.server.diff
import com.tlisonbee.mediasync.sync.ClientConfig
import com.tlisonbee.mediasync.sync.FolderConfig
import com.tlisonbee.mediasync.sync.SyncEngine
import com.tlisonbee.mediasync.tags.AudioTags
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jaudiotagger.tag.FieldKey

class DesktopSyncTest {
    private val okComputer = Fixtures.album("Radiohead", "OK Computer", "Airbag", "Paranoid Android", "Subterranean Homesick Alien")

    /** Desktop with a messy rip folder: three tagged FLACs, an Opus file and a text file. */
    private fun TestWorld.importedDesktop(): TestClient {
        val desktop = client("Desktop")
        okComputer.forEachIndexed { i, tags -> Fixtures.track(desktop.file("rips/ok computer/track$i.flac"), tags, variant = i) }
        Fixtures.copy("tone.opus", desktop.file("rips/ok computer/bonus.opus"))
        Files.writeString(desktop.file("rips/ok computer/notes.txt"), "ripped 2026")
        val report = desktop.sync()
        assertEquals(5, report.pushed, report.warnings.joinToString())
        assertTrue(report.warnings.isEmpty(), report.warnings.joinToString())
        return desktop
    }

    @Test
    fun importRenamesByTagsAndASecondPcReceives() = TestWorld().use { world ->
        val desktop = world.importedDesktop()
        assertEquals(
            listOf(
                "Radiohead/OK Computer/01 Airbag.flac",
                "Radiohead/OK Computer/02 Paranoid Android.flac",
                "Radiohead/OK Computer/03 Subterranean Homesick Alien.flac",
                "rips/ok computer/bonus.opus",
                "rips/ok computer/notes.txt",
            ),
            desktop.files(),
            "music follows the tags; files whose tags MediaSync can't manage stay put",
        )
        val airbag = desktop.file("Radiohead/OK Computer/01 Airbag.flac")
        val inspection = AudioTags.inspect(airbag)
        assertFalse(inspection.hasExtras, "the ripper's comment and encoder fields are gone")
        assertEquals(listOf("Airbag"), inspection.tags.fields["title"])
        assertTrue(inspection.tags.frontCover != null, "the cover stays")
        assertEquals("nothing to do (at r1)", desktop.sync().summary(), "a second sync has nothing to do")

        val laptop = world.client("Laptop")
        val received = laptop.sync()
        assertEquals(5, received.downloadedFiles)
        assertEquals(desktop.files(), laptop.files())
        val copy = laptop.file("Radiohead/OK Computer/01 Airbag.flac")
        assertEquals(MediaHasher.hash(airbag), MediaHasher.hash(copy))
        assertEquals(inspection.tags.fields, AudioTags.read(copy).fields)
        assertEquals(inspection.tags.frontCover, AudioTags.read(copy).frontCover)
        assertEquals("nothing to do (at r1)", laptop.sync().summary())
    }

    @Test
    fun whereCaseDoesNotCountACaseOnlyMoveIsNoChange() = TestWorld().use { world ->
        val desktop = world.importedDesktop()
        // Android's shared storage ignores case, like this client.
        val phone = world.client("Phone", ignoresCase = true).apply { sync() }
        val notes = world.repo.liveAssetAt("music/rips/ok computer/notes")!!

        // A case-only move from elsewhere: on the phone the file is already there.
        Files.move(desktop.file("rips"), desktop.file("Rips"))
        assertEquals(2, desktop.sync().pushed)
        val received = phone.sync()
        assertEquals(0, received.renamed)
        assertEquals(0, received.pushed, "nothing to push back")
        assertTrue("rips/ok computer/notes.txt" in phone.files())

        // The disk shows a folder in another case than the phone recorded: still the same file.
        Files.move(phone.file("rips"), phone.file("RIPS"))
        val again = phone.sync()
        assertEquals(0, again.pushed)
        assertEquals(0, again.filesRead, "not even read again")
        assertEquals("music/Rips/ok computer/notes", world.repo.headVersion(notes)!!.path)
    }

    @Test
    fun aServerChangeThatFailsToApplyIsTriedAgain() = TestWorld().use { world ->
        world.importedDesktop()
        val laptop = world.client("Laptop")
        // The album's folder can't be written to yet, so its downloads fail.
        val folder = Files.createDirectories(laptop.file("Radiohead/OK Computer"))
        folder.toFile().setWritable(false)
        val first = laptop.sync()
        assertEquals(3, first.warnings.size, first.warnings.joinToString())
        folder.toFile().setWritable(true)
        assertEquals(3, laptop.sync().downloadedFiles, "tried again, although the server has nothing new")
        assertTrue("Radiohead/OK Computer/01 Airbag.flac" in laptop.files())
    }

    @Test
    fun aRetagInAnotherAppIsATagOnlyRevision() = TestWorld().use { world ->
        val desktop = world.importedDesktop()
        val laptop = world.client("Laptop").apply { sync() }
        val before = MediaHasher.hash(laptop.file("Radiohead/OK Computer/02 Paranoid Android.flac"))

        // Kid3 on the desktop: new title, file left where it was.
        Fixtures.retag(desktop.file("Radiohead/OK Computer/02 Paranoid Android.flac"), mapOf(FieldKey.TITLE to "Paranoid Android (Remastered)"))
        val pushed = desktop.sync()
        assertEquals(1, pushed.pushed)
        assertEquals(0, pushed.uploadedFiles, "a retag uploads nothing")
        assertTrue("Radiohead/OK Computer/02 Paranoid Android (Remastered).flac" in desktop.files(), "renamed to follow the new title")
        val revision = world.repo.diff(pushed.rev!!)
        assertEquals(mapOf(ChangeType.RETAGGED to 1, ChangeType.MOVED to 1), revision.revision.changes)
        assertEquals(setOf("title", "path"), revision.assets.single().fields.map { it.field }.toSet())

        val received = laptop.sync()
        assertEquals(0, received.downloadedFiles, "the other PC only rewrites tags")
        assertEquals(1, received.retagged)
        assertEquals(1, received.renamed)
        val moved = laptop.file("Radiohead/OK Computer/02 Paranoid Android (Remastered).flac")
        assertEquals(listOf("Paranoid Android (Remastered)"), AudioTags.read(moved).fields["title"])
        assertEquals(before, MediaHasher.hash(moved))
        assertFalse(Files.exists(laptop.file("Radiohead/OK Computer/02 Paranoid Android.flac")))
    }

    @Test
    fun editsToDifferentFieldsOnTwoPcsBothLand() = TestWorld().use { world ->
        val desktop = world.importedDesktop()
        val laptop = world.client("Laptop").apply { sync() }
        Fixtures.retag(desktop.file("Radiohead/OK Computer/01 Airbag.flac"), mapOf(FieldKey.GENRE to "Alternative"))
        Fixtures.retag(laptop.file("Radiohead/OK Computer/01 Airbag.flac"), mapOf(FieldKey.YEAR to "1997"))
        desktop.sync()
        laptop.sync()
        desktop.sync()
        for (client in listOf(desktop, laptop)) {
            val tags = AudioTags.read(client.file("Radiohead/OK Computer/01 Airbag.flac")).fields
            assertEquals(listOf("Alternative"), tags["genre"], client.name)
            assertEquals(listOf("1997"), tags["date"], client.name)
        }
        assertTrue(world.repo.db.read { world.repo.db.long("SELECT COUNT(*) FROM issue") } == 0L, "no conflict")
    }

    @Test
    fun manualRenamesAreUndoneAndOtherFilesMove() = TestWorld().use { world ->
        val desktop = world.importedDesktop()
        val laptop = world.client("Laptop").apply { sync() }
        Files.move(desktop.file("Radiohead/OK Computer/01 Airbag.flac"), desktop.file("Radiohead/OK Computer/my favourite.flac"))
        Files.move(desktop.file("rips/ok computer/notes.txt"), desktop.file("rips/notes about ok computer.txt"))
        val report = desktop.sync()
        assertTrue("Radiohead/OK Computer/01 Airbag.flac" in desktop.files(), "music goes back where its tags put it")
        assertEquals(1, report.pushed, "only the text file's move is a change")
        laptop.sync()
        assertTrue("rips/notes about ok computer.txt" in laptop.files())
        assertFalse("rips/ok computer/notes.txt" in laptop.files())
    }

    @Test
    fun deletionsSyncButMassDeletionsWait() = TestWorld().use { world ->
        val desktop = world.client("Desktop")
        val many = Fixtures.album("Various", "Big Box", *Array(24) { "Song $it" })
        many.forEachIndexed { i, tags -> Fixtures.track(desktop.file("box/$i.flac"), tags, variant = i) }
        desktop.sync()
        val laptop = world.client("Laptop").apply { sync() }
        assertEquals(24, laptop.files().size)

        // One file deleted on the laptop goes everywhere.
        Files.delete(laptop.file("Various/Big Box/01 Song 0.flac"))
        laptop.sync()
        assertEquals(1, desktop.sync().deleted)
        assertFalse(Files.exists(desktop.file("Various/Big Box/01 Song 0.flac")))

        // Everything vanishing at once (an unmounted disk, a slip) is held back.
        laptop.files().forEach { Files.delete(laptop.file(it)) }
        val held = laptop.sync()
        assertTrue(held.warnings.single().contains("--allow-deletes"))
        assertEquals(23, desktop.sync().let { desktop.files().size }, "nothing was deleted elsewhere")
        laptop.sync(allowDeletes = true)
        // Now the desktop is asked to delete 23 of its 23 files, which it holds back too.
        assertTrue(desktop.sync().warnings.single().contains("--allow-deletes"))
        assertEquals(23, desktop.files().size)
        desktop.sync(allowDeletes = true)
        assertEquals(0, desktop.files().size)
    }

    @Test
    fun windowsClutterStaysBehind() = TestWorld().use { world ->
        val desktop = world.client("Desktop")
        Fixtures.track(desktop.file("Album/01.flac"), okComputer[0], variant = 0)
        for (name in listOf("desktop.ini", "AlbumArtSmall.jpg", "AlbumArt_{6A1E2E5D-1C4B-4A8E-9F2B-2B9C7D3E4F10}_Large.jpg", "Folder.jpg")) {
            Files.writeString(desktop.file("Album/$name"), "x")
        }
        assertEquals(2, desktop.sync().pushed, "the track and Folder.jpg; Windows Media Player's files stay out")
        val laptop = world.client("Laptop").apply { sync() }
        assertEquals(listOf("Album/Folder.jpg", "Radiohead/OK Computer/01 Airbag.flac"), laptop.files())
    }

    @Test
    fun aMissingFolderIsNotADeletion() = TestWorld().use { world ->
        val desktop = world.importedDesktop()
        desktop.music.toFile().deleteRecursively()
        val report = desktop.sync()
        assertTrue(report.warnings.single().contains("isn't there"))
        assertEquals(0, report.pushed)
    }

    @Test
    fun junkAddedByAnotherAppIsStrippedWithoutARevision() = TestWorld().use { world ->
        val desktop = world.importedDesktop()
        val file = desktop.file("Radiohead/OK Computer/01 Airbag.flac")
        Fixtures.retag(file, mapOf(FieldKey.COMMENT to "Tagged with SomeApp"))
        assertTrue(AudioTags.inspect(file).hasExtras)
        val report = desktop.sync()
        assertEquals(0, report.pushed)
        assertEquals(1, report.retagged)
        assertFalse(AudioTags.inspect(file).hasExtras)
        assertEquals(1, world.repo.head())
    }

    @Test
    fun aSecondLocalCopyIsLeftAlone() = TestWorld().use { world ->
        val desktop = world.importedDesktop()
        Files.createDirectories(desktop.file("backup"))
        Files.copy(desktop.file("Radiohead/OK Computer/01 Airbag.flac"), desktop.file("backup/airbag copy.flac"))
        val report = desktop.sync()
        assertTrue(report.warnings.single().contains("same audio"))
        assertTrue("backup/airbag copy.flac" in desktop.files())
        assertEquals("nothing to do (at r1)", desktop.sync().summary())
    }

    @Test
    fun filesStillBeingWrittenWait() = TestWorld().use { world ->
        val desktop = world.client("Desktop")
        val engine = SyncEngine(
            ClientConfig(world.url, "x", "x", listOf(FolderConfig("music/", desktop.music.toString()))),
            desktop.api, desktop.index, world.root.resolve("pictures"),
            log = {},
        )
        Fixtures.track(desktop.file("new.flac"), okComputer[0], variant = 0)
        val report = engine.sync()
        assertTrue(report.unsettled)
        assertEquals(0, report.pushed)
    }
}
