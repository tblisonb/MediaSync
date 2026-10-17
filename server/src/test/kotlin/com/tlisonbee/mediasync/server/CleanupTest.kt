package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceCleanup
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.api.Tags
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanupTest {
    private suspend fun ServerFixture.loggedIn(): HttpClient = browser().also {
        assertEquals(HttpStatusCode.Found, it.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) }).status)
    }

    /** Pushes a file as a device that imported it from [source] would. */
    private suspend fun TestDevice.import(name: String, kind: AssetKind, source: String, tags: Tags = emptyMap(), path: String = "music/import/$name"): AssetDto {
        val file = TestDevice.fakeFile(name)
        upload(file)
        return push(PushChange(path = path, kind = kind, mediaHash = file.mediaHash, tags = tags, sourcePath = source)).results.single().asset
    }

    private fun ServerFixture.path(id: String) = repo.headVersion(id)!!.path

    @Test
    fun namesThatDifferOnlyInSpellingGroupTogether() {
        assertEquals(nameKey("The Killers"), nameKey("Killers"))
        assertEquals(nameKey("AC/DC"), nameKey("AC - DC"))
        assertEquals(nameKey("AC DC"), nameKey("ACDC"))
        assertEquals(nameKey("Blue Öyster Cult"), nameKey("Blue Oyster Cult"))
        assertEquals(nameKey("King Gizzard & The Lizard Wizard;"), nameKey("King Gizzard and the Lizard Wizard"))
        assertEquals(nameKey("Mr. Suicide Sheep"), nameKey("Mr Suicide Sheep"))
        assertTrue(nameKey("Theory of a Deadman") != nameKey("Theory of a Dead"))
    }

    @Test
    fun renamingAnArtistRetagsAndMovesEveryTrackInOneRevision() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val a = desktop.add(TestDevice.fakeFile("a.mp3"), track("Mr. Brightside", 2, "Hot Fuss", "The Killers"))
        val b = desktop.add(TestDevice.fakeFile("b.mp3"), track("Somebody Told Me", 4, "Hot Fuss", "Killers"))
        val c = desktop.add(TestDevice.fakeFile("c.mp3"), track("Human", 1, "Day & Age", "Killers") + tags("artist" to "Killers"))
        desktop.add(TestDevice.fakeFile("d.mp3"), track("Airbag", 1))

        val groups = repo.nameVariants()
        assertEquals(1, groups.size, "Radiohead is spelled one way")
        assertEquals(setOf("The Killers", "Killers"), groups.single().spellings.map { it.value }.toSet())

        val rev = repo.rename(repo.webDeviceId(), "artist", setOf("The Killers", "Killers"), "The Killers")!!
        assertEquals("music/The Killers/Hot Fuss/04 Somebody Told Me", path(b.assetId))
        assertEquals("music/The Killers/Day & Age/01 Human", path(c.assetId))
        assertEquals(listOf("The Killers"), repo.headVersion(c.assetId)!!.tags["artist"])
        assertEquals(a.rev, repo.headVersion(a.assetId)!!.rev, "already spelled that way")
        assertTrue(repo.nameVariants().isEmpty())
        assertTrue("Renamed artist “Killers” to “The Killers”" in repo.revision(rev)!!.message)

        repo.revert(repo.webDeviceId(), RevertRequest(rev = rev))
        assertEquals("music/Killers/Hot Fuss/04 Somebody Told Me", path(b.assetId))
    }

    @Test
    fun albumsAreRenamedOnlyForTheirArtist() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val a = desktop.add(TestDevice.fakeFile("a.mp3"), track("One", 1, "Them vs. You vs. Me", "Finger Eleven"))
        val b = desktop.add(TestDevice.fakeFile("b.mp3"), track("Two", 2, "Them Vs You Vs Me", "Finger Eleven"))
        val other = desktop.add(TestDevice.fakeFile("c.mp3"), track("Three", 3, "Them vs. You vs. Me", "Someone Else"))
        val group = repo.nameVariants().single()
        assertEquals("album", group.field)
        assertEquals("Finger Eleven", group.artist)

        repo.rename(repo.webDeviceId(), "album", setOf("Them vs. You vs. Me", "Them Vs You Vs Me"), "Them vs. You vs. Me", group.artistKey)
        assertEquals("music/Finger Eleven/Them vs. You vs. Me/02 Two", path(b.assetId))
        assertEquals(a.path, path(a.assetId))
        assertEquals(other.rev, repo.headVersion(other.assetId)!!.rev)
    }

    @Test
    fun extrasFollowTheirAlbum() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val track = desktop.add(TestDevice.fakeFile("a.mp3"), track("Airbag", 1))
        val cover = desktop.import("folder.jpg", AssetKind.IMAGE, "/home/test/Music/x/folder.jpg", path = "music/Radiohead/OK Computer/folder")
        val scan = desktop.import("back.jpg", AssetKind.IMAGE, "/home/test/Music/x/Scans/back.jpg", path = "music/Radiohead/OK Computer/Scans/back")
        // The folder the album moves to has the same cover (as "keep both" can leave) and a scan of its own.
        val there = desktop.add(TestDevice.fakeFile("b.mp3"), track("Lucky", 11, "OK Computer OKNOTOK"))
        val copy = repo.write {
            RevisionScope(repo, repo.webDeviceId()).run {
                write(repo.headVersion(cover.assetId)!!.copy(assetId = "copy", path = "music/Radiohead/OK Computer OKNOTOK/folder")).also { finish() }
            }
        }
        val otherScan = desktop.import("back2.jpg", AssetKind.IMAGE, "/home/test/Music/y/back.jpg", path = "music/Radiohead/OK Computer OKNOTOK/Scans/back")

        val rev = desktop.push(desktop.retag(track.assetId, track("Airbag", 1, "OK Computer OKNOTOK"))).rev
        assertEquals("music/Radiohead/OK Computer OKNOTOK/Scans/back (2)", path(scan.assetId))
        assertEquals("music/Radiohead/OK Computer OKNOTOK/Scans/back", path(otherScan.assetId))
        assertTrue(repo.headVersion(cover.assetId)!!.deleted, "an identical copy is already there")
        assertEquals(copy.path, path("copy"))
        assertEquals("music/Radiohead/OK Computer OKNOTOK/11 Lucky", path(there.assetId))

        repo.revert(repo.webDeviceId(), RevertRequest(rev = rev))
        assertEquals("music/Radiohead/OK Computer/Scans/back", path(scan.assetId))
        assertEquals("music/Radiohead/OK Computer/folder", path(cover.assetId))
        assertTrue(!repo.headVersion(cover.assetId)!!.deleted)
    }

    @Test
    fun strayFilesGoWhereTheirTracksWent() = withServer {
        val phone = pair("Phone").apply { subscribeMusic() }
        val root = "/storage/emulated/0/Music"
        val track = phone.import("01.mp3", AssetKind.AUDIO, "$root/Iron Maiden/1984 - Powerslave/01 Aces High.mp3", track("Aces High", 1, "Powerslave", "IRON MAIDEN"))
        val front = phone.import("front.jpg", AssetKind.IMAGE, "$root/Iron Maiden/1984 - Powerslave/Covers/front.jpg", path = "music/Iron Maiden/1984 - Powerslave/Covers/front")
        // A compilation whose tracks went to two artists' folders stays put.
        phone.import("02.mp3", AssetKind.AUDIO, "$root/Mix/Best Of/01.mp3", track("A", 1, "Best Of", "One"))
        phone.import("03.mp3", AssetKind.AUDIO, "$root/Mix/Best Of/02.mp3", track("B", 2, "Best Of", "Two"))
        val mixed = phone.import("cover.jpg", AssetKind.IMAGE, "$root/Mix/Best Of/cover.jpg", path = "music/Mix/Best Of/cover")
        assertEquals("music/IRON MAIDEN/Powerslave/01 Aces High", track.path)

        val plan = repo.strayPlan()
        assertEquals(listOf(StrayMove(front.assetId, front.path, "music/IRON MAIDEN/Powerslave/Covers/front")), plan.moves)
        assertEquals(listOf(mixed.path), plan.unplaced)

        val web = loggedIn()
        assertTrue("Move 1 files" in web.get("/cleanup").bodyAsText())
        web.submitForm("/cleanup/strays", parameters { })
        assertEquals("music/IRON MAIDEN/Powerslave/Covers/front", path(front.assetId))
        assertTrue(repo.strayPlan().moves.isEmpty())
    }

    @Test
    fun leftoverFilesAreDeletedByKindInOneRevision() = withServer {
        val phone = pair("Phone").apply { subscribeMusic() }
        phone.add(TestDevice.fakeFile("a.mp3"), track("Airbag", 1))
        val desktopIni = phone.import("desktop.ini", AssetKind.OTHER, "/x/OK Computer/desktop.ini", path = "music/Radiohead/OK Computer/desktop")
        val folderIni = phone.import("Folder.ini", AssetKind.OTHER, "/x/Folder.ini", path = "music/Radiohead/Folder")
        val log = phone.import("rip.log", AssetKind.OTHER, "/x/OK Computer/rip.log", path = "music/Radiohead/OK Computer/rip")
        val cover = phone.import("folder.jpg", AssetKind.IMAGE, "/x/OK Computer/folder.jpg", path = "music/Radiohead/OK Computer/folder")
        assertEquals(listOf("ini" to 2, "log" to 1), repo.leftovers().map { it.format to it.paths.size }, "junk first")

        val web = loggedIn()
        val page = web.get("/cleanup").bodyAsText()
        assertTrue("Radiohead/OK Computer/desktop.ini" in page)
        web.submitForm("/cleanup/leftovers", parameters { append("format", "ini") })
        assertTrue(repo.headVersion(desktopIni.assetId)!!.deleted)
        assertTrue(repo.headVersion(folderIni.assetId)!!.deleted)
        assertTrue(!repo.headVersion(log.assetId)!!.deleted)
        assertTrue(!repo.headVersion(cover.assetId)!!.deleted)
        assertEquals(listOf("log"), repo.leftovers().map { it.format })
        assertEquals("Deleted 2 leftover files (.ini)", repo.revisions(null, 1).revisions.single().message)
    }

    @Test
    fun devicesAreAskedToDeleteTheirClutter() = withServer {
        val phone = pair("Phone")
        assertEquals(DeviceCleanup(), phone.get("/cleanup").body<DeviceCleanup>())
        val web = loggedIn()
        assertTrue("Delete them on every device" in web.get("/cleanup").bodyAsText())
        web.submitForm("/cleanup/clutter", parameters { append("on", "true") })
        assertEquals(DeviceCleanup(deleteClutter = true), phone.get("/cleanup").body<DeviceCleanup>())
        assertTrue("Stop deleting them" in web.get("/cleanup").bodyAsText())
    }

    @Test
    fun theAlbumEditorSetsSharedAndPerTrackFields() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val a = desktop.add(TestDevice.fakeFile("a.mp3"), tags("title" to "Track 1", "tracknumber" to "1", "genre" to "Rock"))
        val b = desktop.add(TestDevice.fakeFile("b.mp3"), tags("title" to "Track 2", "tracknumber" to "2", "genre" to "Pop"))
        assertEquals("music/Unknown Artist/Unknown Album/01 Track 1", a.path)
        val web = loggedIn()
        assertTrue("Unknown Artist/Unknown Album" in web.get("/cleanup").bodyAsText())
        val page = web.get("/album?path=music/Unknown%20Artist/Unknown%20Album").bodyAsText()
        assertTrue("Several values" in page, "genres differ")

        val response = web.submitForm("/album", parameters {
            append("folder", "music/Unknown Artist/Unknown Album")
            append("rev.${a.assetId}", a.rev.toString())
            append("rev.${b.assetId}", b.rev.toString())
            append("all.albumartist", "Radiohead")
            append("all.album", "OK Computer")
            append("all.genre", "")
            append("${a.assetId}.title", "Airbag")
            append("${a.assetId}.artist", "Radiohead")
            append("${b.assetId}.title", "Track 2")
            append("${b.assetId}.artist", "Radiohead; Guest")
        })
        assertEquals("/browse?path=music%2FRadiohead%2FOK+Computer", response.headers["Location"])
        val headA = repo.headVersion(a.assetId)!!
        assertEquals("music/Radiohead/OK Computer/01 Airbag", headA.path)
        assertEquals(listOf("Rock"), headA.tags["genre"], "left empty: several values stay")
        assertEquals(listOf("Radiohead", "Guest"), repo.headVersion(b.assetId)!!.tags["artist"])
        assertEquals(1, repo.revisions(null, 10).revisions.count { it.message.startsWith("Edited album") })

        // A form opened before that edit is refused rather than undoing it.
        val stale = web.submitForm("/album", parameters {
            append("folder", "music/Radiohead/OK Computer")
            append("rev.${a.assetId}", a.rev.toString())
            append("all.album", "Kid A")
        })
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(listOf("OK Computer"), repo.headVersion(a.assetId)!!.tags["album"])
    }

    @Test
    fun theCleanupPageRenamesSpellings() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        desktop.add(TestDevice.fakeFile("a.mp3"), track("Hotel California", 1, "Hotel California", "Eagles"))
        val b = desktop.add(TestDevice.fakeFile("b.mp3"), track("New Kid in Town", 2, "Hotel California", "The Eagles"))
        val web = loggedIn()
        val page = web.get("/cleanup").bodyAsText()
        assertTrue("The Eagles" in page)
        web.submitForm("/cleanup/rename", parameters {
            append("field", "artist")
            append("from", "Eagles")
            append("from", "The Eagles")
            append("to", "The Eagles")
            append("custom", "Eagles")
        })
        assertEquals("music/Eagles/Hotel California/02 New Kid in Town", path(b.assetId))
        assertTrue("Every artist and album is spelled one way" in web.get("/cleanup").bodyAsText())
        assertTrue("Rename “Eagles”" in web.get("/browse?path=music/Eagles").bodyAsText())
    }
}
