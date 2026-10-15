package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetHistory
import com.tlisonbee.mediasync.api.ChangeType
import com.tlisonbee.mediasync.api.Choice
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.HeadEvent
import com.tlisonbee.mediasync.api.IssueDto
import com.tlisonbee.mediasync.api.IssueKind
import com.tlisonbee.mediasync.api.MediaDto
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.PushOutcome
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.api.ResolveRequest
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.api.RevertResponse
import com.tlisonbee.mediasync.api.RevisionDiff
import com.tlisonbee.mediasync.api.RevisionsResponse
import com.tlisonbee.mediasync.api.UploadRequest
import com.tlisonbee.mediasync.api.UploadResponse
import com.tlisonbee.mediasync.library.SubscriptionRule
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.media.MediaHasher
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncApiTest {
    @Test
    fun pairingIssuesTokensOnlyForValidCodes() = withServer {
        val wrong = json.post("/api/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest("AAAA-BBBB", "Laptop", DeviceKind.DESKTOP))
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val code = repo.newPairingCode()
        val first = json.post("/api/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest(code.lowercase(), "Laptop", DeviceKind.DESKTOP))
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val reused = json.post("/api/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest(code, "Laptop", DeviceKind.DESKTOP))
        }
        assertEquals(HttpStatusCode.Unauthorized, reused.status, "a code works once")

        assertEquals(HttpStatusCode.Unauthorized, json.get("/api/v1/changes?since=0").status)
        assertEquals(HttpStatusCode.Unauthorized, json.get("/api/v1/changes?since=0") { bearerAuth("not-a-token") }.status)

        val device = pair("Pixel", DeviceKind.ANDROID)
        assertEquals(HttpStatusCode.OK, device.get("/changes?since=0").status)
        repo.revoke(device.id)
        assertEquals(HttpStatusCode.Unauthorized, device.get("/changes?since=0").status, "revoked tokens stop working")
    }

    @Test
    fun uploadsResumeAndCheckTheHash() = withServer {
        val device = pair("Desktop")
        val file = TestDevice.fakeFile("song.flac")
        val start = device.post("/uploads", UploadRequest(file.blobHash, file.bytes.size.toLong(), file.name)).body<UploadResponse>()
        assertEquals(0, start.offset)
        val id = start.id!!

        suspend fun chunk(offset: Int, end: Int) = json.patch("/api/v1/uploads/$id") {
            bearerAuth(device.token)
            header("Upload-Offset", offset.toString())
            contentType(ContentType.Application.OctetStream)
            setBody(file.bytes.copyOfRange(offset, end))
        }
        assertEquals(HttpStatusCode.OK, chunk(0, 1000).status)
        assertEquals(HttpStatusCode.Conflict, chunk(500, 1000).status, "a chunk must start where the last one ended")
        val resumed = json.head("/api/v1/uploads/$id") { bearerAuth(device.token) }
        assertEquals("1000", resumed.headers["Upload-Offset"])
        assertEquals(1000, device.get("/uploads/$id").body<UploadResponse>().offset)
        val done = chunk(1000, file.bytes.size).body<UploadResponse>()
        assertTrue(done.done)
        assertEquals(file.mediaHash, done.media?.mediaHash)
        assertEquals("flac", done.media?.format)

        // Uploading it again is instant, and the media is findable by its media hash.
        assertTrue(device.post("/uploads", UploadRequest(file.blobHash, file.bytes.size.toLong(), file.name)).body<UploadResponse>().done)
        assertEquals(file.blobHash, device.get("/media/${file.mediaHash}").body<MediaDto>().blob)

        val range = json.get("/api/v1/blobs/${file.blobHash}") {
            bearerAuth(device.token)
            header(HttpHeaders.Range, "bytes=100-199")
        }
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertContentEquals(file.bytes.copyOfRange(100, 200), range.bodyAsBytes())
        assertContentEquals(file.bytes, json.get("/api/v1/blobs/${file.blobHash}") { bearerAuth(device.token) }.bodyAsBytes())

        val liar = TestDevice.fakeFile("other.flac")
        val bad = device.post("/uploads", UploadRequest(liar.blobHash, file.bytes.size.toLong(), "x.flac")).body<UploadResponse>()
        val rejected = json.patch("/api/v1/uploads/${bad.id}") {
            bearerAuth(device.token)
            header("Upload-Offset", "0")
            setBody(file.bytes)
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
    }

    @Test
    fun serverHashesRealFilesLikeTheDevices() = withServer {
        val device = pair("Desktop")
        val fixtures = Path.of(System.getProperty("mediasync.fixtures"))
        for (name in listOf("tone.flac", "tone.mp3", "tone-aac.m4a", "tone.ogg")) {
            val file = TestDevice.file(name, Files.readAllBytes(fixtures.resolve(name)))
            assertEquals(file.mediaHash, MediaHasher.hash(fixtures.resolve(name)).hex)
            assertTrue(file.mediaHash != file.blobHash, "$name: the media hash leaves the tags out")
            assertEquals(file.mediaHash, device.upload(file).media?.mediaHash, name)
        }
    }

    @Test
    fun aRetagTravelsAsMetadataOnly() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val phone = pair("Pixel", DeviceKind.ANDROID).apply { subscribeMusic() }
        val file = TestDevice.fakeFile("01 airbag.flac")

        val added = desktop.add(file, track("Airbag", 1) + tags("comment" to "Ripped with EAC"))
        assertEquals("music/Radiohead/OK Computer/01 Airbag", added.path, "the path follows the tags")
        assertNull(added.tags["comment"], "fields outside the tag policy are dropped")

        phone.pull()
        val onPhone = phone.synced.getValue(added.assetId)
        assertEquals(file.blobHash, onPhone.renditions.single().blob)

        val retag = phone.push(phone.retag(added.assetId, track("Airbag (Remastered)", 1)), message = "Fix title").results.single()
        assertEquals(PushOutcome.UPDATED, retag.outcome)
        assertEquals("music/Radiohead/OK Computer/01 Airbag (Remastered)", retag.asset.path)

        val pulled = desktop.pull().flatMap { it.assets }.single()
        assertEquals(listOf("Airbag (Remastered)"), pulled.tags["title"])
        assertEquals(file.mediaHash, pulled.renditions.single().mediaHash, "the media is untouched")

        val log = desktop.get("/revisions").body<RevisionsResponse>().revisions
        assertEquals("Fix title", log.first().message)
        assertEquals(mapOf(ChangeType.RETAGGED to 1, ChangeType.MOVED to 1), log.first().changes)
        assertEquals("Added 1", log.last().message)

        val history = desktop.get("/assets/${added.assetId}/history").body<AssetHistory>()
        assertEquals(2, history.versions.size)
        assertEquals("Pixel", history.versions.first().deviceName)
        assertTrue(history.versions.first().fields.any { it.field == "title" && it.old == listOf("Airbag") })

        // Pushing the same state again changes nothing and makes no revision.
        val again = phone.push(phone.retag(added.assetId, track("Airbag (Remastered)", 1)))
        assertEquals(PushOutcome.UNCHANGED, again.results.single().outcome)
        assertEquals(log.first().rev, again.rev)
    }

    @Test
    fun editsToDifferentFieldsMergeAndClashesQueue() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val phone = pair("Pixel", DeviceKind.ANDROID).apply { subscribeMusic() }
        val asset = desktop.add(TestDevice.fakeFile("a.flac"), track("Airbag", 1) + tags("genre" to "Rock"))
        phone.pull()

        // Both start from the same base; the desktop changes the genre, the phone the date.
        desktop.push(desktop.retag(asset.assetId, track("Airbag", 1) + tags("genre" to "Alternative")))
        val merged = phone.push(phone.retag(asset.assetId, track("Airbag", 1) + tags("genre" to "Rock", "date" to "1997"))).results.single()
        assertEquals(listOf("Alternative"), merged.asset.tags["genre"])
        assertEquals(listOf("1997"), merged.asset.tags["date"])
        assertTrue(merged.issues.isEmpty())

        // Now both change the genre differently: the server's value stays, the phone's is queued.
        desktop.pull()
        desktop.push(desktop.retag(asset.assetId, merged.asset.tags + tags("genre" to "Art Rock")))
        val clash = phone.push(phone.retag(asset.assetId, merged.asset.tags + tags("genre" to "Britpop"))).results.single()
        assertEquals(listOf("Art Rock"), clash.asset.tags["genre"])
        val issue = phone.get("/resolve").body<List<IssueDto>>().single()
        assertEquals(IssueKind.FIELD_CONFLICT, issue.kind)
        assertEquals(listOf("genre"), issue.fields)
        assertEquals(listOf("Britpop"), issue.candidates[1].fields["genre"])
        assertEquals("Pixel", issue.deviceName)

        assertEquals(HttpStatusCode.BadRequest, phone.post("/resolve/${issue.id}", ResolveRequest(Choice.LINK)).status)
        assertEquals(HttpStatusCode.OK, phone.post("/resolve/${issue.id}", ResolveRequest(Choice.OTHER)).status)
        assertEquals(listOf("Britpop"), repo.headVersion(asset.assetId)!!.tags["genre"])
        assertTrue(phone.get("/resolve").body<List<IssueDto>>().isEmpty())
        assertEquals(HttpStatusCode.Conflict, phone.post("/resolve/${issue.id}", ResolveRequest(Choice.KEEP)).status)
    }

    @Test
    fun duplicatesAreAdoptedOrQueued() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val laptop = pair("Laptop").apply { subscribeMusic() }
        val file = TestDevice.fakeFile("airbag.flac")
        val original = desktop.add(file, track("Airbag", 1))

        val same = laptop.push(PushChange(path = "music/x/airbag", kind = original.kind, mediaHash = file.mediaHash, tags = track("Airbag", 1)))
        assertEquals(PushOutcome.ADOPTED, same.results.single().outcome)
        assertEquals(original.assetId, same.results.single().assetId)

        val other = laptop.push(PushChange(path = "music/x/airbag", kind = original.kind, mediaHash = file.mediaHash, tags = track("Airbag!", 1)))
        val result = other.results.single()
        assertEquals(PushOutcome.DUPLICATE, result.outcome)
        assertEquals(listOf("Airbag"), result.asset.tags["title"], "the repository's tags stay in use")
        val issue = laptop.get("/resolve").body<List<IssueDto>>().single()
        assertEquals(IssueKind.DUPLICATE, issue.kind)

        // Keep both: the imported tags become a second asset with the same media.
        laptop.post("/resolve/${issue.id}", ResolveRequest(Choice.BOTH))
        val assets = laptop.pull().flatMap { it.assets }.filter { !it.deleted }
        assertEquals(setOf("Airbag", "Airbag!"), assets.map { it.tags.getValue("title").single() }.toSet())
    }

    @Test
    fun pathCollisionsGetASuffixAndAnEntry() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        desktop.add(TestDevice.fakeFile("a.flac"), track("Airbag", 1))
        val second = desktop.add(TestDevice.fakeFile("b.flac"), track("Airbag", 1, artist = "radiohead"))
        assertEquals("music/radiohead/OK Computer/01 Airbag (2)", second.path, "paths differing only in case collide")
        assertEquals(IssueKind.PATH_COLLISION, desktop.get("/resolve").body<List<IssueDto>>().single().kind)
    }

    @Test
    fun aSecondDiscRenamesTheWholeAlbum() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val one = desktop.add(TestDevice.fakeFile("a.flac"), track("Airbag", 1, disc = "1"))
        assertEquals("music/Radiohead/OK Computer/01 Airbag", one.path)
        val two = desktop.add(TestDevice.fakeFile("b.flac"), track("Lull", 1, disc = "2"))
        assertEquals("music/Radiohead/OK Computer/2-01 Lull", two.path)
        assertEquals("music/Radiohead/OK Computer/1-01 Airbag", repo.headVersion(one.assetId)!!.path)
    }

    @Test
    fun revertingARevisionSkipsFieldsChangedSince() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val asset = desktop.add(TestDevice.fakeFile("a.flac"), track("Airbag", 1) + tags("genre" to "Rock"))
        val bad = desktop.push(desktop.retag(asset.assetId, track("Airbg", 1) + tags("genre" to "Pop"))).rev
        desktop.push(desktop.retag(asset.assetId, track("Airbg", 1) + tags("genre" to "Jazz")))

        val reverted = desktop.post("/revert", RevertRequest(rev = bad)).body<RevertResponse>()
        assertNotNull(reverted.rev)
        assertEquals(listOf("genre"), reverted.skipped.map { it.field })
        val head = repo.headVersion(asset.assetId)!!
        assertEquals(listOf("Airbag"), head.tags["title"])
        assertEquals(listOf("Jazz"), head.tags["genre"])
        assertEquals("music/Radiohead/OK Computer/01 Airbag", head.path)
        val diff = desktop.get("/revisions/${reverted.rev}").body<RevisionDiff>()
        assertEquals(bad, diff.revision.reverts)

        // Restoring one field from an old version.
        desktop.post("/revert", RevertRequest(assetId = asset.assetId, toRev = asset.rev, fields = listOf("genre")))
        assertEquals(listOf("Rock"), repo.headVersion(asset.assetId)!!.tags["genre"])
    }

    @Test
    fun anEditBeatsADeletion() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val phone = pair("Pixel", DeviceKind.ANDROID).apply { subscribeMusic() }
        val a = desktop.add(TestDevice.fakeFile("a.flac"), track("Airbag", 1))
        val b = desktop.add(TestDevice.fakeFile("b.flac"), track("Paranoid Android", 2))
        phone.pull()

        // b: deleted on the phone with nothing else changed: it goes.
        assertEquals(PushOutcome.UPDATED, phone.push(phone.delete(b.assetId)).results.single().outcome)
        assertTrue(repo.headVersion(b.assetId)!!.deleted)

        // a: retagged on the desktop, deleted on the phone: the retag wins.
        desktop.push(desktop.retag(a.assetId, track("Airbag!", 1)))
        assertEquals(PushOutcome.UNCHANGED, phone.push(phone.delete(a.assetId)).results.single().outcome)
        assertTrue(!repo.headVersion(a.assetId)!!.deleted)

        // And the other way round: deleted on the server, edited on a device that hadn't heard.
        val c = desktop.add(TestDevice.fakeFile("c.flac"), track("Subterranean", 3))
        phone.pull()
        desktop.push(desktop.delete(c.assetId))
        val restored = phone.push(phone.retag(c.assetId, track("Subterranean Homesick Alien", 3))).results.single()
        assertTrue(!restored.asset.deleted)
        assertEquals(listOf("Subterranean Homesick Alien"), restored.asset.tags["title"])
    }

    @Test
    fun scopeFollowsSubscriptionsAndTags() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val phone = pair("Pixel", DeviceKind.ANDROID)
        val podcast = desktop.add(TestDevice.fakeFile("p.mp3"), track("Episode 1", 1, album = "Show", artist = "Podcasts"))
        val song = desktop.add(TestDevice.fakeFile("s.flac"), track("Airbag", 1))

        // No subscription: the phone sees nothing and may push nothing.
        assertTrue(phone.pull().flatMap { it.assets }.isEmpty())
        val refused = phone.post("/push", PushRequest(changes = listOf(PushChange(path = "music/x", kind = song.kind, mediaHash = TestDevice.fakeFile("s.flac").mediaHash))))
        assertEquals(HttpStatusCode.Forbidden, refused.status)

        // Subscribing lists everything already in scope, whatever revision it came from.
        phone.subscribe(SubscriptionRule("music/", "/sdcard/Music", SyncMode.RECEIVE_ONLY, filter = listOf("not path:music/Podcasts/**")))
        val listing = phone.pull().single()
        assertTrue(listing.reset)
        assertEquals(listOf(song.assetId), listing.assets.map { it.assetId })
        assertEquals(HttpStatusCode.Forbidden, phone.post("/push", PushRequest(changes = listOf(phone.retag(song.assetId, track("x", 1))))).status)

        // A retag can move an asset out of scope: the phone is told it left.
        desktop.pull()
        desktop.push(desktop.retag(song.assetId, track("Airbag", 1, artist = "Podcasts")))
        val after = phone.pull().single()
        assertEquals(listOf(song.assetId), after.left)
        assertTrue(after.assets.isEmpty())
        assertTrue(podcast.assetId !in phone.synced)
    }

    @Test
    fun changesArePaged() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val phone = pair("Pixel", DeviceKind.ANDROID).apply { subscribeMusic() }
        val files = (1..5).map { TestDevice.fakeFile("t$it.flac") }
        files.forEach { desktop.upload(it) }
        desktop.push(
            *files.mapIndexed { i, f ->
                PushChange(path = "music/x/${f.name}", kind = com.tlisonbee.mediasync.api.AssetKind.AUDIO, mediaHash = f.mediaHash, tags = track("T$i", i + 1))
            }.toTypedArray(),
        )
        phone.pull() // the subscription listing
        desktop.push(*desktop.synced.keys.map { desktop.retag(it, track("Retagged $it", 9)) }.toTypedArray())
        val pages = phone.pull(limit = 2)
        assertEquals(listOf(2, 2, 1), pages.map { it.assets.size })
        assertEquals(5, pages.flatMap { it.assets }.map { it.assetId }.toSet().size)
    }

    @Test
    fun possibleRenditionsAreOfferedAndLinked() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val phone = pair("Pixel", DeviceKind.ANDROID).apply { subscribeMusic() }
        val flac = desktop.add(TestDevice.fakeFile("airbag.flac"), track("Airbag", 1), durationMs = 284_000)
        val mp3 = phone.add(TestDevice.fakeFile("airbag.mp3"), track("Airbag", 1), durationMs = 284_900)
        assertTrue(mp3.assetId != flac.assetId, "never linked automatically")

        val issue = phone.get("/resolve").body<List<IssueDto>>().single()
        assertEquals(IssueKind.POSSIBLE_RENDITION, issue.kind, "one entry, though the same tags also mean the same path")
        assertEquals("music/Radiohead/OK Computer/01 Airbag (2)", mp3.path)
        phone.post("/resolve/${issue.id}", ResolveRequest(Choice.LINK))
        assertTrue(phone.get("/resolve").body<List<IssueDto>>().isEmpty())
        val linked = repo.headVersion(flac.assetId)!!
        assertEquals(2, linked.renditions.size)
        assertTrue(repo.headVersion(mp3.assetId)!!.deleted)
        assertEquals(listOf("flac", "mp3"), repo.toDto(linked).renditions.map { it.format })
    }

    @Test
    fun replacedMediaUpdatesTheRendition() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        val old = TestDevice.fakeFile("a.flac", seed = 1)
        val asset = desktop.add(old, track("Airbag", 1))
        val reencoded = TestDevice.fakeFile("a.flac", seed = 2)
        desktop.upload(reencoded)
        val change = desktop.retag(asset.assetId, asset.tags).copy(mediaHash = reencoded.mediaHash)
        val result = desktop.push(change).results.single()
        assertEquals(listOf(reencoded.mediaHash), result.asset.renditions.map { it.mediaHash })
        assertEquals(listOf(ChangeType.REPLACED), desktop.get("/assets/${asset.assetId}/history").body<AssetHistory>().versions.first().changes)
    }

    @Test(timeout = 60_000)
    fun eventsAnnounceNewRevisionsOverRealHttp() {
        // Ktor's test engine doesn't stream responses, so this one runs a real server on loopback.
        val root = Files.createTempDirectory("mediasync-sse-test")
        val dir = RepoDir(root.resolve("repo"))
        Setup.create(dir, ADMIN_PASSWORD)
        val repo = Repository(dir)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { mediaSyncModule(repo) }.start(wait = false)
        try {
            runBlocking {
                val outer = this
                val port = server.engine.resolvedConnectors().first().port
                val client = HttpClient(CIO) {
                    defaultRequest { url("http://127.0.0.1:$port") }
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(ApiJson) }
                    install(io.ktor.client.plugins.sse.SSE)
                }
                val code = repo.newPairingCode()
                val pairing = client.post("/api/v1/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(PairRequest(code, "Desktop", DeviceKind.DESKTOP))
                }.body<com.tlisonbee.mediasync.api.PairResponse>()
                val desktop = TestDevice(client, pairing.deviceId, pairing.token).apply { subscribeMusic() }
                val heads = mutableListOf<Long>()
                client.sse("/api/v1/events", { bearerAuth(desktop.token) }) {
                    // The current head arrives on connecting; a push then announces the next one.
                    incoming.mapNotNull { it.data }.map { ApiJson.decodeFromString(HeadEvent.serializer(), it).head }.first { head ->
                        heads += head
                        if (heads.size == 1) outer.launch { desktop.add(TestDevice.fakeFile("a.flac"), track("Airbag", 1)) }
                        head > 0
                    }
                }
                assertEquals(listOf(0L, 1L), heads)
                client.close()
            }
        } finally {
            server.stop(0, 0)
            repo.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun healthNeedsNoToken() = withServer {
        assertEquals("{\"ok\":true}", json.get("/api/v1/health").bodyAsText())
    }
}
