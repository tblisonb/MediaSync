package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.PushOutcome
import com.tlisonbee.mediasync.library.SubscriptionRule
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebTest {
    private suspend fun ServerFixture.loggedIn(): HttpClient = browser().also {
        assertEquals(HttpStatusCode.Found, it.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) }).status)
    }

    @Test
    fun pagesNeedTheAdminPassword() = withServer {
        val client = browser()
        val response = client.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/login", response.headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.Unauthorized, client.submitForm("/login", parameters { append("password", "wrong") }).status)
        assertEquals(HttpStatusCode.Found, client.get("/resolve").status)
        // A device token doesn't open the web UI either.
        val device = pair("Desktop")
        assertEquals(HttpStatusCode.Found, client.get("/") { bearerAuth(device.token) }.status)
    }

    @Test
    fun browsingHistoryAndRevertingWork() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val cover = TestDevice.file("cover.jpg", byteArrayOf(-1, -40, -1, -32) + ByteArray(64))
        desktop.upload(cover)
        val file = TestDevice.fakeFile("a.flac")
        desktop.upload(file)
        val asset = desktop.push(
            PushChange(
                path = "music/x/a", kind = AssetKind.AUDIO, mediaHash = file.mediaHash,
                tags = track("<script>alert(1)</script>", 1), pictures = listOf(PictureRef("front", cover.blobHash, "image/jpeg")),
            ),
        ).results.single().asset
        val retag = desktop.push(desktop.retag(asset.assetId, track("Airbag", 1))).rev
        val web = loggedIn()

        val status = web.get("/").bodyAsText()
        assertTrue("r2" in status)
        assertTrue("Desktop" in status)

        val album = web.get("/browse?path=music/Radiohead/OK%20Computer").bodyAsText()
        assertTrue("01 Airbag" in album)
        assertTrue("/assets/${asset.assetId}" in album)
        assertTrue("Radiohead/" in web.get("/browse?path=music").bodyAsText())

        val page = web.get("/assets/${asset.assetId}").bodyAsText()
        assertTrue("&lt;script&gt;" in page, "tags are escaped")
        assertFalse("<script>" in page)
        assertTrue("/web/blobs/${cover.blobHash}" in page)
        assertEquals(HttpStatusCode.OK, web.get("/web/blobs/${cover.blobHash}").status)
        assertEquals(HttpStatusCode.NotFound, web.get("/web/blobs/${file.blobHash}").status, "only images are served to the browser")

        assertTrue("Airbag" in web.get("/revisions/$retag").bodyAsText())
        assertEquals(HttpStatusCode.Found, web.submitForm("/revisions/$retag/revert", parameters { }).status)
        assertEquals(listOf("<script>alert(1)</script>"), repo.headVersion(asset.assetId)!!.tags["title"])
        assertTrue("Web UI" in web.get("/revisions").bodyAsText(), "web edits are attributed to the web UI")

        // Restore one field from the asset page.
        web.submitForm("/assets/${asset.assetId}/restore", parameters { append("toRev", retag.toString()); append("field", "title") })
        assertEquals(listOf("Airbag"), repo.headVersion(asset.assetId)!!.tags["title"])
    }

    @Test
    fun resolveQueueTakesFieldPicks() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val phone = pair("Pixel", DeviceKind.ANDROID).apply { subscribeMusic() }
        val asset = desktop.add(TestDevice.fakeFile("a.flac"), track("Airbag", 1) + tags("genre" to "Rock", "date" to "1997"))
        phone.pull()
        desktop.push(desktop.retag(asset.assetId, track("Airbag", 1) + tags("genre" to "Alt", "date" to "1998")))
        phone.push(phone.retag(asset.assetId, track("Airbag", 1) + tags("genre" to "Brit", "date" to "1999")))

        val web = loggedIn()
        val page = web.get("/resolve").bodyAsText()
        assertTrue("Conflicting edits" in page)
        assertTrue("Brit" in page)
        val id = repo.openIssues().single().id
        web.submitForm("/resolve/$id", parameters {
            append("choice", "merge")
            append("f.genre", "1")
            append("f.date", "0")
        })
        val head = repo.headVersion(asset.assetId)!!
        assertEquals(listOf("Brit"), head.tags["genre"])
        assertEquals(listOf("1998"), head.tags["date"])
        assertTrue("Nothing waits" in web.get("/resolve").bodyAsText())
    }

    @Test
    fun resolveQueueSettlesBatches() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val laptop = pair("Laptop").apply { subscribeMusic() }
        // Two files at one path, the second later given the first one's audio.
        val airbag = desktop.add(TestDevice.fakeFile("airbag.mp3"), track("Airbag", 1))
        val copy = desktop.add(TestDevice.fakeFile("airbag-copy.mp3"), track("Airbag", 1))
        val audio = repo.headVersion(airbag.assetId)!!.renditions
        repo.write { RevisionScope(repo, desktop.id).apply { write(repo.headVersion(copy.assetId)!!.copy(renditions = audio)) }.finish() }
        // The same tracks in two formats, the second one added moved aside: mostly the M4A, once the MP3.
        suspend fun twice(title: String, n: Int, first: String, second: String) =
            listOf(first, second).map { format -> desktop.add(TestDevice.fakeFile("$title.$format"), track(title, n)) }
        val pairs = listOf(twice("Lucky", 10, "mp3", "m4a"), twice("Let Down", 5, "mp3", "m4a"), twice("No Surprises", 10, "m4a", "mp3"))
        val both = twice("Climbing Up the Walls", 7, "mp3", "m4a")
        // Imports of the library's audio: the library's genre number and date with a time, a title
        // that's another matter, and tags that differ only in case and zeros.
        val exitFile = TestDevice.fakeFile("exit.mp3")
        val exit = desktop.add(exitFile, track("Exit Music", 4) + tags("genre" to "(17)", "date" to "1997-05-21T07:00:00Z"))
        val karmaFile = TestDevice.fakeFile("karma.mp3")
        val karma = desktop.add(karmaFile, track("Karma Police", 6))
        val tourist = TestDevice.fakeFile("tourist.mp3")
        desktop.add(tourist, track("The Tourist", 12))
        fun dup(file: LocalFile, tags: Map<String, List<String>>) = PushChange(path = "music/x/${file.name}", kind = AssetKind.AUDIO, mediaHash = file.mediaHash, tags = tags)
        laptop.push(dup(exitFile, track("Exit Music", 4, artist = "radiohead") + tags("genre" to "Rock", "date" to "1997-05-21")))
        laptop.push(dup(karmaFile, track("Karma Police (Live)", 6)))
        val same = laptop.push(dup(tourist, track("the tourist ", 12) + tags("tracknumber" to "012"))).results.single()
        assertEquals(PushOutcome.ADOPTED, same.outcome, "the same tags, written differently")
        // Given a disc number in the library since the import.
        desktop.push(desktop.retag(exit.assetId, track("Exit Music", 4, disc = "1") + tags("genre" to "(17)", "date" to "1997-05-21T07:00:00Z")))

        val issues = repo.openIssues()
        assertEquals(7, issues.size)
        val web = loggedIn()
        val page = web.get("/resolve").bodyAsText()
        for (title in listOf("Identical copies", "Same track in another format", "Imported tags that fix the library", "Other tag differences")) {
            assertTrue(title in page, title)
        }
        assertTrue("genre: (17) → Rock" in page)
        assertFalse("One by one" in page, "every entry is in a batch")
        fun id(assetId: String) = issues.single { it.assetId == assetId }.id
        suspend fun settle(batch: String, vararg ids: Long, how: String? = null) = web.submitForm("/resolve/batch", parameters {
            append("batch", batch)
            ids.forEach { append("id", it.toString()) }
            how?.let { append("how", it) }
        })

        settle("copies", id(copy.assetId))
        assertTrue(repo.headVersion(copy.assetId)!!.deleted)
        assertEquals(audio, repo.headVersion(airbag.assetId)!!.renditions)

        // Keeping only the MP3s: each pair becomes the first track, with the one MP3 file.
        val head = repo.head()
        settle("formats", *pairs.map { id(it[1].assetId) }.toLongArray())
        assertEquals(head + 1, repo.head(), "one revision for the lot")
        for ((first, second) in pairs) {
            assertTrue(repo.headVersion(second.assetId)!!.deleted)
            val mp3 = (if (first.renditions.single().format == "mp3") first else second).renditions.single().mediaHash
            assertEquals(listOf(mp3), repo.headVersion(first.assetId)!!.renditions)
        }
        // Or both files.
        settle("formats", id(both[1].assetId), how = "alternative")
        assertEquals(2, repo.headVersion(both[0].assetId)!!.renditions.size)

        settle("other_tags")
        assertEquals(listOf("Karma Police"), repo.headVersion(karma.assetId)!!.tags["title"])
        assertEquals(2, repo.openIssues().size, "nothing ticked, nothing settled")

        settle("fixes", id(exit.assetId))
        val fixed = repo.headVersion(exit.assetId)!!.tags
        assertEquals(listOf("Rock"), fixed["genre"])
        assertEquals(listOf("1997-05-21"), fixed["date"])
        assertEquals(listOf("1"), fixed["discnumber"], "the edit since stays")
        assertEquals(listOf("Radiohead"), fixed["artist"])
        assertTrue("Settled in" in web.get("/resolve?done=${repo.head()}").bodyAsText())

        // The last one, one by one.
        assertTrue("Karma Police (Live)" in web.get("/resolve/${id(karma.assetId)}").bodyAsText())
    }

    @Test
    fun plainFilesOfATrackCanBeLinked() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        suspend fun plain(file: LocalFile, path: String): AssetDto {
            desktop.upload(file)
            return desktop.push(PushChange(path = path, kind = AssetKind.OTHER, mediaHash = file.mediaHash)).results.single().asset
        }
        // A track, and an untagged WMA an older client sent as a plain file, at its path.
        val airbag = desktop.add(TestDevice.fakeFile("airbag.mp3"), track("Airbag", 1))
        val wma = plain(TestDevice.fakeFile("airbag.wma"), airbag.path)
        // An empty plain file first, and the track moved aside by it.
        val empty = plain(TestDevice.file("paranoid.mp3", ByteArray(0)), "music/Radiohead/OK Computer/02 Paranoid Android")
        val paranoid = desktop.add(TestDevice.fakeFile("paranoid-good.mp3"), track("Paranoid Android", 2))
        assertEquals("music/Radiohead/OK Computer/02 Paranoid Android (2)", paranoid.path)

        val issues = repo.openIssues()
        assertEquals(2, issues.size)
        val web = loggedIn()
        val page = web.get("/resolve").bodyAsText()
        assertTrue("Same track in another format" in page)
        assertFalse("One by one" in page)

        web.submitForm("/resolve/batch", parameters {
            append("batch", "formats")
            append("id", issues.single { it.assetId == wma.assetId }.id.toString())
        })
        assertTrue(repo.headVersion(wma.assetId)!!.deleted)
        assertEquals(airbag.renditions.map { it.mediaHash }, repo.headVersion(airbag.assetId)!!.renditions)

        // One by one: the tagged track stays, without the empty file, at the path it was kept from.
        web.submitForm("/resolve/${issues.single { it.assetId == paranoid.assetId }.id}", parameters { append("choice", "link:mp3") })
        assertTrue(repo.headVersion(empty.assetId)!!.deleted)
        val kept = repo.headVersion(paranoid.assetId)!!
        assertEquals(paranoid.renditions.map { it.mediaHash }, kept.renditions)
        assertEquals("music/Radiohead/OK Computer/02 Paranoid Android", kept.path)
        assertTrue(repo.openIssues().isEmpty())
    }

    @Test
    fun devicesCanBePairedAndManaged() = withServer {
        val web = loggedIn()
        val page = web.submitForm("/devices/pair", parameters { }) {
            headers.append(HttpHeaders.Host, "server.example.ts.net:8443")
        }.bodyAsText()
        assertTrue("<svg" in page, "a QR code")
        assertTrue("https://server.example.ts.net:8443" in page)
        val code = Regex("""<code class="code">([A-Z0-9-]+)</code>""").find(page)!!.groupValues[1]
        assertEquals(9, code.length)
        val device = pair("Laptop")

        val list = web.get("/devices").bodyAsText()
        assertTrue("Laptop" in list)
        web.submitForm("/devices/${device.id}/subscription", parameters {
            append("subscription", """{"rules":[{"prefix":"music/","localFolder":"/home/me/Music","mode":"receive-only"}]}""")
        })
        assertEquals(SyncMode.RECEIVE_ONLY, repo.device(device.id)!!.subscription.rules.single().mode)
        assertEquals(
            HttpStatusCode.BadRequest,
            web.submitForm("/devices/${device.id}/subscription", parameters { append("subscription", """{"rules":[{"prefix":"music"}]}""") }).status,
        )
        web.submitForm("/devices/${device.id}/revoke", parameters { })
        assertEquals(HttpStatusCode.Unauthorized, device.get("/changes?since=0").status)
    }

    @Test
    fun settingsChangeTheTagPolicy() = withServer {
        val web = loggedIn()
        web.submitForm("/settings", parameters {
            append("field.title", "on")
            append("field.artist", "on")
            append("cover", "on")
        })
        assertEquals(listOf("title", "artist"), repo.policy.fields[AssetKind.AUDIO])
        assertEquals(repo.policy, RepoDir(repo.dir.root).readSettings().tagPolicy, "saved to settings.json")

        val desktop = pair("Desktop").apply { subscribeMusic() }
        val asset = desktop.add(TestDevice.fakeFile("a.flac"), track("Airbag", 1))
        assertEquals(setOf("title", "artist"), asset.tags.keys)
    }

    @Test
    fun homeSumsUpTheLibraryAndWhatNeedsALook() = withServer {
        val web = loggedIn()
        assertTrue("Everything's in order" in web.get("/").bodyAsText())

        val pc = pair("PC").apply { subscribe(SubscriptionRule("music/", "/home/test/Music"), SubscriptionRule("images/photos/", "/home/test/Pictures")) }
        val tracks = (1..2).map { n ->
            val file = TestDevice.fakeFile("$n.mp3")
            pc.upload(file)
            pc.push(PushChange(path = "music/x/$n", kind = AssetKind.AUDIO, mediaHash = file.mediaHash, tags = track("Song $n", n))).results.single().asset
        }
        val scan = TestDevice.file("scan.png", PhotosTest.png(1))
        pc.upload(scan)
        pc.push(PushChange(path = "images/photos/import/scan", kind = AssetKind.IMAGE, mediaHash = scan.mediaHash, sourcePath = "/home/test/Pictures/scan.png"))
        repo.editTags(repo.webDeviceId(), "Retitled a song", mapOf(tracks[0].assetId to mapOf(TagField.TITLE to listOf("Airbag"))))
        pair("Laptop")
        repo.db.exec("UPDATE device SET last_seen_at = ? WHERE name = 'Laptop'", System.currentTimeMillis() - 5 * 24 * 3600_000L)

        val home = web.get("/").bodyAsText()
        assertTrue("2 tracks" in home && "1 album" in home)
        assertTrue("1 photo has no date" in home, "an undated photo asks for a date")
        assertTrue("Laptop hasn't synced in 5 days" in home, "a quiet device that's behind is mentioned")
        // The PC's three pushes are one line; the web UI's edit keeps its message.
        assertTrue("PC added 2 tracks, 1 photo" in home)
        assertTrue("Retitled a song" in home)
        assertTrue("Photos" in home && "Library by area" in home)
    }
}

class MaintenanceTest {
    @Test
    fun fsckPruneAndSnapshot() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val old = TestDevice.fakeFile("a.flac", seed = 1)
        val asset = desktop.add(old, track("Airbag", 1))
        val new = TestDevice.fakeFile("a.flac", seed = 2)
        desktop.upload(new)
        desktop.push(desktop.retag(asset.assetId, asset.tags).copy(mediaHash = new.mediaHash))
        assertEquals(0, Maintenance.fsck(repo, full = true))

        // Corrupt a blob: fsck --full notices.
        val path = repo.dir.blob(new.blobHash)
        val bytes = Files.readAllBytes(path)
        bytes[0] = (bytes[0] + 1).toByte()
        Files.write(path, bytes)
        assertEquals(1, Maintenance.fsck(repo, full = true))
        Files.write(path, new.bytes)

        // Prune with a clock far in the future: the replaced media's blob goes, the current stays.
        val later = Repository(repo.dir) { System.currentTimeMillis() + 400L * 24 * 3600 * 1000 }
        later.use { Maintenance.prune(it, "1y") }
        assertFalse(Files.exists(repo.dir.blob(old.blobHash)))
        assertTrue(Files.exists(repo.dir.blob(new.blobHash)))
        assertEquals(1, repo.versions(asset.assetId).size)
        assertEquals(0, Maintenance.fsck(repo, full = true))

        Maintenance.snapshot(repo)
        assertTrue(Files.size(repo.dir.snapshots.resolve("repo.db")) > 0)
    }
}
