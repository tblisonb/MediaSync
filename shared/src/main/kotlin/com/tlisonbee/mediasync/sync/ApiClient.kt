package com.tlisonbee.mediasync.sync

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetHistory
import com.tlisonbee.mediasync.api.ChangesResponse
import com.tlisonbee.mediasync.api.DeviceCleanup
import com.tlisonbee.mediasync.api.HeadEvent
import com.tlisonbee.mediasync.api.EditRequest
import com.tlisonbee.mediasync.api.EditResponse
import com.tlisonbee.mediasync.api.FolderPickItem
import com.tlisonbee.mediasync.api.FolderPickRequest
import com.tlisonbee.mediasync.api.IssueDto
import com.tlisonbee.mediasync.api.PickItem
import com.tlisonbee.mediasync.api.PickRequest
import com.tlisonbee.mediasync.api.PickResponse
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
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int) : IOException(message)

/**
 * The sync API, over OkHttp so the same client runs on the desktop and on Android. See
 * shared/.../api/Api.kt for the types.
 */
class ApiClient(serverUrl: String, private val token: String?) {
    private val base = serverUrl.trimEnd('/') + "/api/v1"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // a large push is merged in one transaction
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    fun health(): JsonObject = get("/health", JsonObject.serializer())

    fun pair(request: PairRequest): PairResponse = post("/pair", request, PairRequest.serializer(), PairResponse.serializer())

    fun policy(): TagPolicy = get("/policy", TagPolicy.serializer())

    /** The cleanup the server asks of this device's folders; a server from before cleanup asks none. */
    fun cleanup(): DeviceCleanup = try {
        get("/cleanup", DeviceCleanup.serializer())
    } catch (e: ApiException) {
        if (e.status == 404) DeviceCleanup() else throw e
    }

    /** This device's rules as the server holds them. */
    fun subscription(): Subscription = get("/devices/me/subscription", Subscription.serializer())

    fun putSubscription(subscription: Subscription): Subscription =
        send(request("/devices/me/subscription").put(json(Subscription.serializer(), subscription)).build(), Subscription.serializer())

    fun changes(since: Long, after: String?, limit: Int = 500): ChangesResponse =
        get("/changes?since=$since&limit=$limit" + (after?.let { "&after=" + URLEncoder.encode(it, "UTF-8") } ?: ""), ChangesResponse.serializer())

    /** The server's record of a media payload, or null if it has never seen it. */
    fun media(mediaHash: String): MediaDto? = try {
        get("/media/$mediaHash", MediaDto.serializer())
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    fun hasBlob(hash: String): Boolean = http.newCall(request("/blobs/$hash").head().build()).execute().use {
        when (it.code) {
            200 -> true
            404 -> false
            else -> throw ApiException("HEAD /blobs/$hash: ${it.code}", it.code)
        }
    }

    fun push(request: PushRequest): PushResponse = post("/push", request, PushRequest.serializer(), PushResponse.serializer())

    fun revisions(before: Long? = null, limit: Int = 50): RevisionsResponse =
        get("/revisions?limit=$limit" + (before?.let { "&before=$it" } ?: ""), RevisionsResponse.serializer())

    fun diff(rev: Long): RevisionDiff = get("/revisions/$rev", RevisionDiff.serializer())

    fun history(assetId: String): AssetHistory = get("/assets/$assetId/history", AssetHistory.serializer())

    fun revert(request: RevertRequest): RevertResponse = post("/revert", request, RevertRequest.serializer(), RevertResponse.serializer())

    fun openIssues(): List<IssueDto> = get("/resolve", ListSerializer(IssueDto.serializer()))

    fun edit(request: EditRequest): EditResponse = post("/edits", request, EditRequest.serializer(), EditResponse.serializer())

    /** The videos under [prefix], each with whether this device keeps it. */
    fun picks(prefix: String = "videos/"): List<PickItem> =
        get("/picks?prefix=" + java.net.URLEncoder.encode(prefix, Charsets.UTF_8), ListSerializer(PickItem.serializer()))

    fun setPicks(request: PickRequest): PickResponse =
        send(request("/picks").put(json(PickRequest.serializer(), request)).build(), PickResponse.serializer())

    /** The platforms and games under [prefix], and which this device keeps whole. */
    fun folderPicks(prefix: String = "games/"): List<FolderPickItem> =
        get("/picks/folders?prefix=" + java.net.URLEncoder.encode(prefix, Charsets.UTF_8), ListSerializer(FolderPickItem.serializer()))

    fun setFolderPicks(request: FolderPickRequest): PickResponse =
        send(request("/picks/folders").put(json(FolderPickRequest.serializer(), request)).build(), PickResponse.serializer())

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
                val body = object : RequestBody() {
                    override fun contentType() = OCTET_STREAM
                    override fun contentLength() = length

                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteBuffer.allocate(1 shl 16)
                        var position = offset
                        val end = offset + length
                        while (position < end) {
                            buffer.clear()
                            buffer.limit(minOf(buffer.capacity().toLong(), end - position).toInt())
                            val n = channel.read(buffer, position)
                            if (n < 0) throw IOException("$file got shorter while uploading")
                            buffer.flip()
                            sink.write(buffer)
                            position += n
                        }
                    }
                }
                val request = request("/uploads/${state.id}").header("Upload-Offset", offset.toString()).patch(body).build()
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
        val offset = start.offset.toInt()
        val request = request("/uploads/${start.id}")
            .header("Upload-Offset", offset.toString())
            .patch(bytes.toRequestBody(OCTET_STREAM, offset, bytes.size - offset))
            .build()
        return send(request, UploadResponse.serializer())
    }

    /**
     * Downloads a blob into [target], resuming a partial file already there, and checks its hash.
     * Returns the number of bytes fetched.
     */
    fun download(hash: String, target: Path, onBytes: (Long) -> Unit = {}): Long {
        val have = if (Files.exists(target)) Files.size(target) else 0L
        val builder = request("/blobs/$hash")
        if (have > 0) builder.header("Range", "bytes=$have-")
        var fetched = 0L
        http.newCall(builder.build()).execute().use { response ->
            val append = when (response.code) {
                200 -> false
                206 -> true
                416 -> null // already complete
                else -> throw ApiException("GET /blobs/$hash: ${response.code} ${response.body.string().take(200)}", response.code)
            }
            if (append != null) {
                val options = if (append) arrayOf(StandardOpenOption.APPEND) else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                Files.newOutputStream(target, *options).use { out ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        fetched += n
                        onBytes(n.toLong())
                    }
                }
            }
        }
        val actual = sha256Hex(target)
        if (actual != hash) {
            Files.deleteIfExists(target)
            throw IOException("downloaded blob $hash hashed to $actual; it will be fetched again")
        }
        return fetched
    }

    fun downloadBytes(hash: String): ByteArray = http.newCall(request("/blobs/$hash").build()).execute().use {
        if (it.code != 200) throw ApiException("GET /blobs/$hash: ${it.code}", it.code)
        it.body.bytes().also { bytes -> if (sha256Hex(bytes) != hash) throw IOException("blob $hash arrived corrupted") }
    }

    /** Follows GET /events until the stream ends, calling [onHead] for each announced revision. */
    fun events(onHead: (Long) -> Unit) {
        val streaming = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        streaming.newCall(request("/events").header("Accept", "text/event-stream").build()).execute().use { response ->
            if (response.code != 200) throw ApiException("GET /events: ${response.code}", response.code)
            val source = response.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    runCatching { ApiJson.decodeFromString(HeadEvent.serializer(), line.removePrefix("data:").trim()) }
                        .getOrNull()?.let { onHead(it.head) }
                }
            }
        }
    }

    // ---- Plumbing ----

    private fun request(path: String): Request.Builder {
        val builder = Request.Builder().url(base + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun <T> json(serializer: KSerializer<T>, value: T): RequestBody =
        ApiJson.encodeToString(serializer, value).toRequestBody(JSON)

    private fun <T> get(path: String, serializer: KSerializer<T>): T = send(request(path).build(), serializer)

    private fun <R, T> post(path: String, body: R, bodySerializer: KSerializer<R>, serializer: KSerializer<T>): T =
        send(request(path).post(json(bodySerializer, body)).build(), serializer)

    private fun <T> send(request: Request, serializer: KSerializer<T>): T = http.newCall(request).execute().use { response ->
        val text = response.body.string()
        check(response, request, text)
        ApiJson.decodeFromString(serializer, text)
    }

    private fun check(response: Response, request: Request, text: String) {
        val status = response.code
        if (status == 401) {
            // Without a token this was pairing, and the server says why the code didn't work.
            val message = if (token == null) text.trim() else "The server rejected this device's token; pair it again."
            throw ApiException(message, 401)
        }
        if (status !in 200..299) throw ApiException("${request.method} ${request.url.encodedPath}: $status ${text.take(300)}", status)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
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
