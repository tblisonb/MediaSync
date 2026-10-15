package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.PushChange
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
