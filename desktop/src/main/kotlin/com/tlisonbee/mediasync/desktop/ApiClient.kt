package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetHistory
import com.tlisonbee.mediasync.api.ChangesResponse
import com.tlisonbee.mediasync.api.HeadEvent
import com.tlisonbee.mediasync.api.IssueDto
import com.tlisonbee.mediasync.api.MediaDto
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.api.PairResponse
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.api.PushResponse
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.api.RevertResponse
import com.tlisonbee.mediasync.api.RevisionDiff
import com.tlisonbee.mediasync.api.RevisionsResponse
import com.tlisonbee.mediasync.api.UploadRequest
import com.tlisonbee.mediasync.api.UploadResponse
import com.tlisonbee.mediasync.library.Subscription
import com.tlisonbee.mediasync.library.TagPolicy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration

class ApiException(message: String, val status: Int) : IOException(message)

/** The sync API over plain java.net.http; see shared/.../api/Api.kt for the types. */
class ApiClient(serverUrl: String, private val token: String?) {
    private val base = serverUrl.trimEnd('/') + "/api/v1"
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun health() = get("/health", kotlinx.serialization.json.JsonObject.serializer())

    fun pair(request: PairRequest): PairResponse = post("/pair", request, PairRequest.serializer(), PairResponse.serializer())

    fun policy(): TagPolicy = get("/policy", TagPolicy.serializer())

    fun putSubscription(subscription: Subscription): Subscription =
        send(json("/devices/me/subscription", "PUT", ApiJson.encodeToString(Subscription.serializer(), subscription)), Subscription.serializer())

    fun changes(since: Long, after: String?, limit: Int = 500): ChangesResponse =
        get("/changes?since=$since&limit=$limit" + (after?.let { "&after=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""), ChangesResponse.serializer())

    /** The server's record of a media payload, or null if it has never seen it. */
    fun media(mediaHash: String): MediaDto? = try {
        get("/media/$mediaHash", MediaDto.serializer())
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    fun hasBlob(hash: String): Boolean {
        val response = http.send(request("/blobs/$hash").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        return when (response.statusCode()) {
            200 -> true
            404 -> false
            else -> throw ApiException("HEAD /blobs/$hash: ${response.statusCode()}", response.statusCode())
        }
    }

    fun push(request: PushRequest): PushResponse =
        send(json("/push", "POST", ApiJson.encodeToString(PushRequest.serializer(), request)), PushResponse.serializer(), timeout = Duration.ofMinutes(10))

    fun revisions(before: Long? = null, limit: Int = 50): RevisionsResponse =
        get("/revisions?limit=$limit" + (before?.let { "&before=$it" } ?: ""), RevisionsResponse.serializer())

    fun diff(rev: Long): RevisionDiff = get("/revisions/$rev", RevisionDiff.serializer())

    fun history(assetId: String): AssetHistory = get("/assets/$assetId/history", AssetHistory.serializer())

    fun revert(request: RevertRequest): RevertResponse = post("/revert", request, RevertRequest.serializer(), RevertResponse.serializer())

    fun openIssues(): List<IssueDto> = get("/resolve", ListSerializer(IssueDto.serializer()))

    /**
     * Uploads a file in chunks, resuming where an earlier attempt stopped (the server remembers
     * partial uploads per device and hash). [hash] is the SHA-256 of the file's bytes.
     */
    fun upload(file: Path, hash: String, name: String, chunk: Long = 16L shl 20, onBytes: (Long) -> Unit = {}): UploadResponse {
        val size = Files.size(file)
        var state = post("/uploads", UploadRequest(hash, size, name), UploadRequest.serializer(), UploadResponse.serializer())
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            while (!state.done) {
                val offset = state.offset
                val length = minOf(chunk, size - offset)
                val body = HttpRequest.BodyPublishers.fromPublisher(
                    HttpRequest.BodyPublishers.ofInputStream { bounded(Channels.newInputStream(channel.position(offset)), length) },
                    length,
                )
                val request = request("/uploads/${state.id}")
                    .header("Upload-Offset", offset.toString())
                    .header("Content-Type", "application/octet-stream")
                    .timeout(Duration.ofMinutes(10))
                    .method("PATCH", body)
                    .build()
                state = send(request, UploadResponse.serializer())
                onBytes(length)
            }
        }
        return state
    }

    /** Uploads bytes already in memory, such as cover art. */
    fun uploadBytes(bytes: ByteArray, name: String): UploadResponse {
        val hash = sha256Hex(bytes)
        val start = post("/uploads", UploadRequest(hash, bytes.size.toLong(), name), UploadRequest.serializer(), UploadResponse.serializer())
        if (start.done) return start
        val request = request("/uploads/${start.id}")
            .header("Upload-Offset", start.offset.toString())
            .header("Content-Type", "application/octet-stream")
            .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(bytes, start.offset.toInt(), bytes.size - start.offset.toInt()))
            .build()
        return send(request, UploadResponse.serializer())
    }

    /**
     * Downloads a blob into [target], resuming a partial file already there, and checks its hash.
     * Returns the number of bytes fetched.
     */
    fun download(hash: String, target: Path): Long {
        val have = if (Files.exists(target)) Files.size(target) else 0L
        val builder = request("/blobs/$hash").timeout(Duration.ofMinutes(30)).GET()
        if (have > 0) builder.header("Range", "bytes=$have-")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        var fetched = 0L
        response.body().use { body ->
            when (response.statusCode()) {
                200 -> Files.newOutputStream(target).use { fetched = body.copyTo(it) }
                206 -> Files.newOutputStream(target, StandardOpenOption.APPEND).use { fetched = body.copyTo(it) }
                416 -> Unit // already complete
                else -> throw ApiException("GET /blobs/$hash: ${response.statusCode()} ${String(body.readNBytes(200))}", response.statusCode())
            }
        }
        val actual = sha256Hex(target)
        if (actual != hash) {
            Files.deleteIfExists(target)
            throw IOException("downloaded blob $hash hashed to $actual; it will be fetched again")
        }
        return fetched
    }

    fun downloadBytes(hash: String): ByteArray {
        val response = http.send(request("/blobs/$hash").GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) throw ApiException("GET /blobs/$hash: ${response.statusCode()}", response.statusCode())
        return response.body().also { if (sha256Hex(it) != hash) throw IOException("blob $hash arrived corrupted") }
    }

    /** Follows GET /events until the stream ends, calling [onHead] for each announced revision. */
    fun events(onHead: (Long) -> Unit) {
        val request = request("/events").header("Accept", "text/event-stream").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() != 200) throw ApiException("GET /events: ${response.statusCode()}", response.statusCode())
        response.body().use { lines ->
            lines.forEach { line ->
                if (line.startsWith("data:")) {
                    runCatching { ApiJson.decodeFromString(HeadEvent.serializer(), line.removePrefix("data:").trim()) }
                        .getOrNull()?.let { onHead(it.head) }
                }
            }
        }
    }

    // ---- Plumbing ----

    private fun request(path: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(2))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun <T> get(path: String, serializer: KSerializer<T>): T = send(request(path).GET().build(), serializer)

    private fun <R, T> post(path: String, body: R, bodySerializer: KSerializer<R>, serializer: KSerializer<T>): T =
        send(json(path, "POST", ApiJson.encodeToString(bodySerializer, body)), serializer)

    private fun json(path: String, method: String, body: String): HttpRequest = request(path)
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build()

    private fun <T> send(request: HttpRequest, serializer: KSerializer<T>, timeout: Duration? = null): T {
        val actual = if (timeout != null) HttpRequest.newBuilder(request) { _, _ -> true }.timeout(timeout).build() else request
        val response = http.send(actual, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if (status == 401) {
            // Without a token this was pairing, and the server says why the code didn't work.
            val message = if (token == null) response.body().trim() else "The server rejected this device's token; pair it again with `mediasync-desktop setup`."
            throw ApiException(message, 401)
        }
        if (status !in 200..299) throw ApiException("${request.method()} ${request.uri().path}: $status ${response.body().take(300)}", status)
        return ApiJson.decodeFromString(serializer, response.body())
    }

    private fun bounded(input: InputStream, limit: Long): InputStream = object : InputStream() {
        var left = limit
        override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

fun sha256Hex(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
