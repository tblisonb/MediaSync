package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.ChangesResponse
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.api.PairResponse
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.api.PushResponse
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.api.UploadRequest
import com.tlisonbee.mediasync.api.UploadResponse
import com.tlisonbee.mediasync.library.Subscription
import com.tlisonbee.mediasync.library.SubscriptionRule
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.media.MediaHasher
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertEquals

/** A file as a device holds it: its bytes, what MediaSync computes from them, and its tags. */
data class LocalFile(val name: String, val bytes: ByteArray, val mediaHash: String, val blobHash: String)

/**
 * The scripted client: drives the sync API the way the desktop client and the Android app will,
 * with the shared hashing code computing what a real device would send.
 */
class TestDevice(val http: HttpClient, val id: String, val token: String) {
    /** The version this device last synced of each asset: its merge base. */
    val synced = mutableMapOf<String, AssetDto>()
    var since = 0L

    suspend fun get(path: String): HttpResponse = http.get("/api/v1$path") { bearerAuth(token) }

    suspend fun post(path: String, body: Any): HttpResponse = http.post("/api/v1$path") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend fun put(path: String, body: Any): HttpResponse = http.put("/api/v1$path") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend fun subscribe(vararg rules: SubscriptionRule) {
        val response = http.put("/api/v1/devices/me/subscription") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Subscription(rules.toList()))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    suspend fun subscribeMusic(mode: SyncMode = SyncMode.TWO_WAY, filter: List<String> = emptyList()) =
        subscribe(SubscriptionRule("music/", "/home/test/Music", mode, filter))

    /** Uploads [file] in two chunks, as an interrupted upload would arrive. */
    suspend fun upload(file: LocalFile): UploadResponse {
        val start = post("/uploads", UploadRequest(file.blobHash, file.bytes.size.toLong(), file.name)).body<UploadResponse>()
        if (start.done) return start
        val half = file.bytes.size / 2
        var response: UploadResponse = start
        for ((from, to) in listOf(0 to half, half to file.bytes.size)) {
            response = http.patch("/api/v1/uploads/${start.id}") {
                bearerAuth(token)
                header("Upload-Offset", from.toString())
                contentType(ContentType.Application.OctetStream)
                setBody(file.bytes.copyOfRange(from, to))
            }.body()
        }
        return response
    }

    suspend fun push(vararg changes: PushChange, message: String? = null): PushResponse {
        val response = post("/push", PushRequest(message, changes.toList()))
        assertEquals(HttpStatusCode.OK, response.status, "push failed: ${response.body<String>()}")
        return response.body<PushResponse>().also { pushed -> pushed.results.forEach { synced[it.assetId] = it.asset } }
    }

    /** Uploads [file] and pushes it as a new asset. */
    suspend fun add(file: LocalFile, tags: Tags, path: String = "music/import/${file.name.substringBeforeLast('.')}", durationMs: Long? = null): AssetDto {
        upload(file)
        return push(PushChange(path = path, kind = AssetKind.AUDIO, mediaHash = file.mediaHash, tags = tags, durationMs = durationMs, sourcePath = "/home/test/Music/${file.name}"))
            .results.single().asset
    }

    /** A local retag of an asset this device has synced: only the tags differ from its base. */
    fun retag(assetId: String, tags: Tags): PushChange {
        val base = synced.getValue(assetId)
        val media = base.renditions.first().mediaHash
        return PushChange(assetId, base.rev, base.path, base.kind, media, media, tags = tags, pictures = base.pictures)
    }

    fun delete(assetId: String): PushChange {
        val base = synced.getValue(assetId)
        return PushChange(assetId, base.rev, base.path, base.kind, deleted = true)
    }

    /** Pulls every page of changes and records them as synced. */
    suspend fun pull(limit: Int = 500): List<ChangesResponse> {
        val pages = mutableListOf<ChangesResponse>()
        var after: String? = null
        do {
            val page = get("/changes?since=$since&limit=$limit" + (after?.let { "&after=$it" } ?: "")).body<ChangesResponse>()
            pages += page
            page.assets.forEach { synced[it.assetId] = it }
            page.left.forEach { synced.remove(it) }
            after = page.next
        } while (page.more)
        since = pages.last().head
        return pages
    }

    companion object {
        /** Bytes no format parser recognises, so the media hash is the blob hash; enough for the API. */
        fun fakeFile(name: String, seed: Int = name.hashCode()): LocalFile = file(name, Random(seed).nextBytes(4096))

        fun file(name: String, bytes: ByteArray): LocalFile {
            val dir = Files.createTempDirectory("mediasync-device")
            try {
                val path = dir.resolve(name)
                Files.write(path, bytes)
                return LocalFile(name, bytes, MediaHasher.hash(path).hex, BlobStore.sha256(path))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
}

fun tags(vararg pairs: Pair<String, String>): Tags = pairs.groupBy({ it.first }, { it.second })

fun track(title: String, track: Int, album: String = "OK Computer", artist: String = "Radiohead", disc: String? = null): Tags =
    tags("title" to title, "artist" to artist, "albumartist" to artist, "album" to album, "tracknumber" to "$track") +
        (disc?.let { tags("discnumber" to it) } ?: emptyMap())

class ServerFixture(val repo: Repository, private val builder: ApplicationTestBuilder) {
    val json: HttpClient = builder.createClient { install(ContentNegotiation) { json(com.tlisonbee.mediasync.api.ApiJson) } }

    suspend fun pair(name: String, kind: DeviceKind = DeviceKind.DESKTOP): TestDevice {
        val code = repo.newPairingCode()
        val response = json.post("/api/v1/pair") {
            contentType(ContentType.Application.Json)
            setBody(PairRequest(code, name, kind))
        }.body<PairResponse>()
        return TestDevice(json, response.deviceId, response.token)
    }

    fun sseClient(): HttpClient = builder.createClient { install(io.ktor.client.plugins.sse.SSE) }

    fun browser(): HttpClient = builder.createClient {
        followRedirects = false
        install(io.ktor.client.plugins.cookies.HttpCookies)
    }
}

const val ADMIN_PASSWORD = "correct horse battery"

fun withServer(block: suspend ServerFixture.() -> Unit) {
    val root = Files.createTempDirectory("mediasync-server-test")
    val dir = RepoDir(root.resolve("repo"))
    Setup.create(dir, ADMIN_PASSWORD)
    val repo = Repository(dir)
    try {
        testApplication {
            application { mediaSyncModule(repo) }
            ServerFixture(repo, this).block()
        }
    } finally {
        repo.close()
        root.toFile().deleteRecursively()
    }
}
