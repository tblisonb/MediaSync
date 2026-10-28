package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.PushOutcome
import com.tlisonbee.mediasync.library.SyncMode
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
        // The same tracks in two formats.
        val mp3s = listOf("Lucky" to 10, "Let Down" to 5).map { (title, n) -> desktop.add(TestDevice.fakeFile("$title.mp3"), track(title, n)) }
        val m4as = listOf("Lucky" to 10, "Let Down" to 5).map { (title, n) -> desktop.add(TestDevice.fakeFile("$title.m4a"), track(title, n)) }
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
        assertEquals(5, issues.size)
        val web = loggedIn()
        val page = web.get("/resolve").bodyAsText()
        for (title in listOf("Identical copies", "Same track in another format", "Imported tags that fix the library", "Other tag differences")) {
            assertTrue(title in page, title)
        }
        assertTrue("genre: (17) → Rock" in page)
        assertFalse("One by one" in page, "every entry is in a batch")
        fun id(assetId: String) = issues.single { it.assetId == assetId }.id
        suspend fun settle(batch: String, vararg ids: Long) = web.submitForm("/resolve/batch", parameters {
            append("batch", batch)
            ids.forEach { append("id", it.toString()) }
        })

        settle("copies", id(copy.assetId))
        assertTrue(repo.headVersion(copy.assetId)!!.deleted)
        assertEquals(audio, repo.headVersion(airbag.assetId)!!.renditions)

        val head = repo.head()
        settle("formats", *m4as.map { id(it.assetId) }.toLongArray())
        assertEquals(head + 1, repo.head(), "one revision for the lot")
        assertTrue(m4as.all { repo.headVersion(it.assetId)!!.deleted })
        assertTrue(mp3s.all { repo.headVersion(it.assetId)!!.renditions.size == 2 })

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
