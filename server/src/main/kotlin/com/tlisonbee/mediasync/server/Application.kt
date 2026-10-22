package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.HeadEvent
import com.tlisonbee.mediasync.api.Limits
import com.tlisonbee.mediasync.api.DeviceCleanup
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.EditRequest
import com.tlisonbee.mediasync.api.EditResponse
import com.tlisonbee.mediasync.api.FolderPickRequest
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.api.PickRequest
import com.tlisonbee.mediasync.api.PickResponse
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.api.ResolveRequest
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.api.UploadRequest
import com.tlisonbee.mediasync.api.UploadResponse
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.Subscription
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.auth.session
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sse.SSE
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.Base64
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Signed (not encrypted) browser session; it holds nothing secret, only an expiry. */
@Serializable
data class WebSession(val expiresAt: Long)

class DevicePrincipal(val device: Device)

val SESSION_LIFETIME = 30.days
val LOGIN_RATE_LIMIT = RateLimitName("login")
val PAIR_RATE_LIMIT = RateLimitName("pair")

/** From the jar's manifest; null when not run from the built jar (in tests). */
val VERSION: String? = Repository::class.java.`package`?.implementationVersion

fun Application.mediaSyncModule(repo: Repository) {
    install(ContentNegotiation) { json(ApiJson) }
    install(CallLogging)
    install(StatusPages) {
        exception<RequestException> { call, e ->
            call.respondText(e.message ?: "Bad request", status = HttpStatusCode.fromValue(e.status))
        }
        exception<BadRequestException> { call, e ->
            call.respondText(e.message ?: "Bad request", status = HttpStatusCode.BadRequest)
        }
    }
    install(SecurityHeaders)
    install(PartialContent)
    install(AutoHeadResponse)
    install(SSE)
    install(RateLimit) {
        register(LOGIN_RATE_LIMIT) { rateLimiter(limit = 10, refillPeriod = 1.minutes) }
        register(PAIR_RATE_LIMIT) { rateLimiter(limit = 10, refillPeriod = 1.minutes) }
    }
    val secrets = repo.dir.readSecrets()
    install(Sessions) {
        cookie<WebSession>("mediasync_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = SESSION_LIFETIME.inWholeSeconds
            // Strict keeps the cookie off cross-site requests, which is our CSRF protection. Not
            // marked Secure: the server speaks plain HTTP on loopback behind `tailscale serve`.
            cookie.extensions["SameSite"] = "Strict"
            transform(SessionTransportTransformerMessageAuthentication(Base64.getDecoder().decode(secrets.sessionKey)))
        }
    }
    install(Authentication) {
        bearer("api") {
            authenticate { credential -> io { repo.authenticate(credential.token) }?.let(::DevicePrincipal) }
        }
        session<WebSession>("web") {
            validate { session -> session.takeIf { it.expiresAt > System.currentTimeMillis() } }
            challenge { call.respondRedirect("/login") }
        }
    }

    routing {
        staticResources("/static", "static")
        route("/api/v1") {
            get("/health") {
                call.respond(
                    buildJsonObject {
                        put("ok", true)
                        VERSION?.let { put("version", it) }
                    },
                )
            }
            rateLimit(PAIR_RATE_LIMIT) {
                post("/pair") {
                    val request = call.receive<PairRequest>()
                    call.respond(io { repo.pair(request) })
                }
            }
            authenticate("api") { apiRoutes(repo) }
        }
        webRoutes(repo)
    }
}

private fun Route.apiRoutes(repo: Repository) {
    get("/changes") {
        val since = call.parameters["since"]?.toLongOrNull() ?: badRequest("since is required")
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 500
        call.respond(io { repo.changes(call.device(), since, call.parameters["after"], limit) })
    }
    get("/assets/{id}/history") { call.respond(io { repo.history(call.parameters["id"]!!) }) }
    get("/revisions") {
        val before = call.parameters["before"]?.toLongOrNull()
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
        call.respond(io { repo.revisions(before, limit) })
    }
    get("/revisions/{rev}") {
        val rev = call.parameters["rev"]?.toLongOrNull() ?: badRequest("bad revision")
        call.respond(io { repo.diff(rev) })
    }

    get("/blobs/{hash}") {
        val hash = call.parameters["hash"]!!
        BlobStore.requireHash(hash)
        val blob = io { repo.blob(hash) } ?: notFound("no blob $hash")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        call.respond(LocalPathContent(repo.dir.blob(hash), ContentType.parse(blob.mime)))
    }
    get("/media/{hash}") {
        val hash = call.parameters["hash"]!!
        BlobStore.requireHash(hash)
        call.respond(io { repo.media(hash) }?.toDto() ?: notFound("no media $hash"))
    }
    post("/uploads") {
        val request = call.receive<UploadRequest>()
        call.respond(io { repo.blobs.startUpload(call.device().id, request) })
    }
    // Where an interrupted upload resumes; HEAD gives the same Upload-Offset header without a body.
    get("/uploads/{id}") {
        val id = call.parameters["id"]!!
        val offset = io { repo.blobs.offset(call.device().id, id) }
        call.response.header("Upload-Offset", offset.toString())
        call.respond(UploadResponse(id, offset, done = false))
    }
    patch("/uploads/{id}") {
        val offset = call.request.header("Upload-Offset")?.toLongOrNull() ?: badRequest("Upload-Offset header is required")
        val result = withContext(Dispatchers.IO) {
            call.receiveStream().use { repo.blobs.append(call.device().id, call.parameters["id"]!!, offset, it) }
        }
        call.response.header("Upload-Offset", result.offset.toString())
        call.respond(result)
    }

    post("/push") {
        val request = call.receive<PushRequest>()
        val response = io { repo.push(call.device(), request) }
        io { repo.convertArrivals(request) }
        io { repo.identifyArrivals(request, response.results.map { it.outcome }) }
        call.respond(response)
    }
    // Field edits (a tag editor, a kind) as one revision; a device edits only what it may send.
    post("/edits") {
        val request = call.receive<EditRequest>()
        if (request.edits.size > Limits.MAX_CHANGES_PER_PUSH) badRequest("at most ${Limits.MAX_CHANGES_PER_PUSH} assets per edit")
        if ((request.message?.length ?: 0) > Limits.MAX_MESSAGE) badRequest("message too long")
        val device = call.device()
        io {
            for (id in request.edits.keys) {
                val head = repo.headVersion(id) ?: notFound("no asset $id")
                if (device.kind != DeviceKind.WEB && !device.subscription.sends(head.path, head.kind)) {
                    throw RequestException("${head.path} isn't in a two-way or send-only rule of this device's subscription", 403)
                }
            }
        }
        call.respond(EditResponse(io { repo.editTags(device.id, request.message, request.edits, request.expectedRevs) }))
    }
    post("/revert") {
        val request = call.receive<RevertRequest>()
        call.respond(io { repo.revert(call.device().id, request) })
    }
    get("/resolve") { call.respond(io { repo.openIssues() }) }
    post("/resolve/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: badRequest("bad issue id")
        val request = call.receive<ResolveRequest>()
        call.respond(io { repo.resolve(call.device().id, id, request) })
    }

    // Which videos this device keeps, under its pick rules.
    get("/picks") {
        val prefix = call.parameters["prefix"] ?: "videos/"
        call.respond(io { repo.pickList(call.device().id, prefix) })
    }
    put("/picks") {
        val request = call.receive<PickRequest>()
        if (request.pick.size + request.unpick.size > Limits.MAX_CHANGES_PER_PUSH) badRequest("too many picks at once")
        call.respond(PickResponse(io { repo.setPicks(call.device().id, request.pick, request.unpick) }))
    }
    // Which games and platforms this device keeps whole, under its pick rules.
    get("/picks/folders") {
        val prefix = call.parameters["prefix"] ?: RepoPaths.GAMES
        call.respond(io { repo.folderPickList(call.device().id, prefix) })
    }
    put("/picks/folders") {
        val request = call.receive<FolderPickRequest>()
        if (request.pick.size + request.unpick.size > Limits.MAX_CHANGES_PER_PUSH) badRequest("too many picks at once")
        call.respond(PickResponse(io { repo.setFolderPicks(call.device().id, request.pick, request.unpick) }))
    }

    // Clients preview the import and keep files tidy with the same rules the server applies.
    get("/policy") { call.respond(repo.policy) }
    get("/cleanup") { call.respond(DeviceCleanup(deleteClutter = repo.settings.deleteClutter)) }
    get("/devices/me") { call.respond(call.device().toDto()) }
    get("/devices/{id}/subscription") { call.respond(call.ownDevice().subscription) }
    put("/devices/{id}/subscription") {
        val device = call.ownDevice()
        val subscription = call.receive<Subscription>()
        io { repo.setSubscription(device.id, subscription) }
        call.respond(subscription)
    }

    // Only a hint to sync now; devices still ask GET /changes for the facts.
    sse("/events") {
        heartbeat { period = 30.seconds }
        repo.headFlow.collect { send(ServerSentEvent(data = ApiJson.encodeToString(HeadEvent.serializer(), HeadEvent(it)))) }
    }
}

private fun ApplicationCall.device(): Device = principal<DevicePrincipal>()!!.device

/** A device may read and change only its own settings; "me" names itself. */
private fun ApplicationCall.ownDevice(): Device {
    val device = device()
    val id = parameters["id"]
    if (id != "me" && id != device.id) throw RequestException("a device can only change its own subscription", 403)
    return device
}

suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        with(call.response.headers) {
            append(
                "Content-Security-Policy",
                // Scripts only from the server's own files (static/progress.js), never inline.
                "default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'self'; img-src 'self' data:; form-action 'self'; " +
                    "frame-ancestors 'none'; base-uri 'none'",
            )
            append("X-Content-Type-Options", "nosniff")
            append("Referrer-Policy", "no-referrer")
        }
    }
}

/** Startup housekeeping: partial uploads abandoned for a week are removed. */
fun Repository.startup() {
    blobs.cleanStaleUploads()
    // Leftover partial files without a database row (a crash between the two).
    Files.list(dir.uploads).use { files ->
        val known = db.read { db.query("SELECT id FROM upload") { it.getString(1) }.toSet() }
        files.filter { it.fileName.toString() !in known }.forEach { Files.deleteIfExists(it) }
    }
    // Game files a stopped server never got to.
    if (liveGames().any { it.assetId !in gameIdents() }) startIdentifying(webDeviceId())
}
