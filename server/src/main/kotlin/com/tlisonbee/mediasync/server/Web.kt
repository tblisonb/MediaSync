package com.tlisonbee.mediasync.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.ChangeType
import com.tlisonbee.mediasync.api.Choice
import com.tlisonbee.mediasync.api.FieldDiff
import com.tlisonbee.mediasync.api.Fields
import com.tlisonbee.mediasync.api.IssueDto
import com.tlisonbee.mediasync.api.IssueKind
import com.tlisonbee.mediasync.api.ResolveRequest
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.api.RevisionDto
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.Subscription
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.TABLE
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.hiddenInput
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The admin web UI: plain server-rendered HTML forms with no JavaScript, which keeps the CSP
 * strict. Every change is a POST that redirects back to a page.
 */
fun Route.webRoutes(repo: Repository) {
    get("/login") { call.respondHtml { loginPage(failed = false) } }
    rateLimit(LOGIN_RATE_LIMIT) {
        post("/login") {
            val password = call.receiveParameters()["password"].orEmpty()
            if (io { Passwords.verify(password, repo.dir.readSecrets().passwordHash) }) {
                call.sessions.set(WebSession(System.currentTimeMillis() + SESSION_LIFETIME.inWholeMilliseconds))
                call.respondRedirect("/")
            } else {
                call.respondHtml(HttpStatusCode.Unauthorized) { loginPage(failed = true) }
            }
        }
    }
    // The Android app's web view logs in with a ticket from POST /api/v1/web/ticket (WebAccess.kt).
    post("/login/device") {
        val form = call.receiveParameters()
        val device = form["ticket"]?.let { io { repo.redeemWebTicket(it) } }
            ?: return@post call.respondRedirect("/login")
        call.sessions.set(WebSession(System.currentTimeMillis() + SESSION_LIFETIME.inWholeMilliseconds, device.id))
        call.respondRedirect(localPath(form["next"]))
    }
    post("/logout") {
        call.sessions.clear<WebSession>()
        call.respondRedirect("/login")
    }

    authenticate("web") {
        get("/browse") {
            val folder = call.parameters["path"].orEmpty().trim('/')
            val listing = io { repo.browse(folder) }
            val formats = io { listing.assets.associate { it.assetId to repo.toDto(it).renditions.map { r -> r.format } } }
            val artists = if (RepoPaths.isMusic("$folder/") && folder.count { it == '/' } == 1) io { repo.artistsIn(folder) } else emptyList()
            call.respondHtml { browsePage(folder, listing, formats, artists) }
        }
        get("/assets/{id}") {
            val id = call.parameters["id"]!!
            val head = io { repo.headVersion(id) } ?: return@get call.respond(HttpStatusCode.NotFound)
            val dto = io { repo.toDto(head) }
            val history = io { repo.history(id) }
            val video = if (head.kind == AssetKind.VIDEO && !head.deleted) io { VideoInfo(repo.sidecarsOf(head), repo.videoDevices(head.path, head.assetId)) } else null
            call.respondHtml { assetPage(head, dto, history, video) }
        }
        post("/assets/{id}/restore") {
            val form = call.receiveParameters()
            val id = call.parameters["id"]!!
            val toRev = form["toRev"]?.toLongOrNull() ?: badRequest("toRev is required")
            val fields = form.getAll("field")?.takeIf { it.isNotEmpty() }
            io { repo.revert(repo.webDeviceId(), RevertRequest(assetId = id, toRev = toRev, fields = fields)) }
            call.respondRedirect("/assets/$id")
        }
        get("/deleted") {
            val deleted = io { repo.recentlyDeleted() }
            call.respondHtml { deletedPage(deleted) }
        }

        get("/revisions") {
            val before = call.parameters["before"]?.toLongOrNull()
            val page = io { repo.revisions(before, 50) }
            call.respondHtml { revisionsPage(page.revisions, page.more) }
        }
        get("/revisions/{rev}") {
            val rev = call.parameters["rev"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
            val diff = io { repo.diff(rev) }
            call.respondHtml { diffPage(diff) }
        }
        post("/revisions/{rev}/revert") {
            val rev = call.parameters["rev"]?.toLongOrNull() ?: badRequest("bad revision")
            val result = io { repo.revert(repo.webDeviceId(), RevertRequest(rev = rev)) }
            call.respondRedirect(result.rev?.let { "/revisions/$it" } ?: "/revisions/$rev")
        }

        get("/resolve") {
            val issues = io { repo.openIssues() }
            val batches = io { repo.batches(issues) }
            call.respondHtml { resolvePage(issues, batches, call.parameters["done"]?.toLongOrNull()) }
        }
        get("/resolve/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
            val issue = io { repo.issue(id) }?.takeIf { it.resolvedRev == null } ?: return@get call.respondRedirect("/resolve")
            call.respondHtml { resolvePage(listOf(issue), emptyList(), null) }
        }
        // Settles the ticked entries of one batch, each the way the batch does, in one revision.
        post("/resolve/batch") {
            val form = call.receiveParameters()
            val kind = BatchKind.entries.firstOrNull { it.name.lowercase() == form["batch"] } ?: badRequest("no such batch")
            val ids = form.getAll("id").orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            val alternative = form["how"] == "alternative" && kind.alternative != null
            val rev = io {
                val items = repo.batches(repo.openIssues()).firstOrNull { it.kind == kind }?.items.orEmpty().filter { it.issue.id in ids }
                val how = (if (alternative) kind.alternative else kind.action)!!.lowercase()
                val message = "Resolved ${items.size} ${if (items.size == 1) "entry" else "entries"}: ${kind.title.lowercase()} ($how)"
                repo.resolveAll(repo.webDeviceId(), message, items.associate { it.issue.id to if (alternative) it.alternative else it.request })
            }
            call.respondRedirect(rev?.let { "/resolve?done=$it" } ?: "/resolve")
        }
        post("/resolve/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: badRequest("bad issue id")
            val form = call.receiveParameters()
            val picked = form["choice"].orEmpty().split(':', limit = 2)
            val choice = Choice.entries.firstOrNull { it.name.lowercase() == picked[0] } ?: badRequest("pick a choice")
            val fields = form.names().filter { it.startsWith("f.") }
                .associate { it.removePrefix("f.") to (form[it]?.toIntOrNull() ?: 0) }.takeIf { it.isNotEmpty() }
            io { repo.resolve(repo.webDeviceId(), id, ResolveRequest(choice, fields, keepFormat = picked.getOrNull(1))) }
            call.respondRedirect("/resolve")
        }

        get("/devices") {
            val devices = io { repo.devices() }
            val head = io { repo.head() }
            val web = io { devices.filter(repo::webAllowed).map { it.id }.toSet() }
            call.respondHtml { devicesPage(devices, pairing = null, head = head, web = web) }
        }
        post("/devices/pair") {
            val code = io { repo.newPairingCode() }
            val server = call.publicUrl()
            val devices = io { repo.devices() }
            val head = io { repo.head() }
            val web = io { devices.filter(repo::webAllowed).map { it.id }.toSet() }
            call.respondHtml { devicesPage(devices, Pairing(server, code), head = head, web = web) }
        }
        post("/devices/{id}/revoke") {
            io { repo.revoke(call.parameters["id"]!!) }
            call.respondRedirect("/devices")
        }
        post("/devices/{id}/web-access/revoke") {
            io { repo.disallowWeb(call.parameters["id"]!!) }
            call.respondRedirect("/devices")
        }
        post("/devices/{id}/subscription") {
            val text = call.receiveParameters()["subscription"].orEmpty()
            val subscription = try {
                ApiJson.decodeFromString(Subscription.serializer(), text)
            } catch (e: kotlinx.serialization.SerializationException) {
                badRequest("That isn't a valid subscription: ${e.message}")
            }
            io { repo.setSubscription(call.parameters["id"]!!, subscription) }
            call.respondRedirect("/devices")
        }

        cleanupRoutes(repo)
        videoRoutes(repo)
        gameRoutes(repo)
        homeRoutes(repo)
        imageRoutes(repo)

        get("/settings") { call.respondHtml { settingsPage(repo.settings, saved = call.parameters["saved"] != null) } }
        post("/settings") {
            val form = call.receiveParameters()
            val fields = TagField.MUSIC.filter { form["field.$it"] != null }
            val pictures = if (form["cover"] != null) listOf(TagPolicy.FRONT_COVER) else emptyList()
            val policy = repo.settings.tagPolicy.copy(
                fields = repo.settings.tagPolicy.fields + (AssetKind.AUDIO to fields),
                pictures = repo.settings.tagPolicy.pictures + (AssetKind.AUDIO to pictures),
            )
            io { repo.updateSettings(repo.settings.copy(tagPolicy = policy)) }
            call.respondRedirect("/settings?saved")
        }

        // Cover art, images and videos for the pages above; never other types, which a browser
        // could run as a page of this site.
        get("/web/blobs/{hash}") {
            val hash = call.parameters["hash"]!!
            if (!BlobStore.isHash(hash)) return@get call.respond(HttpStatusCode.NotFound)
            val blob = io { repo.blob(hash) }?.takeIf { it.mime.startsWith("image/") || it.mime.startsWith("video/") }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            // ?download=name saves it under that name, also where the download attribute isn't
            // followed (the Android app's web view hands downloads to the system).
            call.parameters["download"]?.takeIf { it.isNotBlank() }?.let { name ->
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString(),
                )
            }
            call.respond(LocalPathContent(repo.dir.blob(hash), ContentType.parse(blob.mime)))
        }

        // A photo's or video's thumbnail, made the first time it's shown.
        get("/web/thumbs/{hash}") {
            val hash = call.parameters["hash"]!!
            if (!BlobStore.isHash(hash)) return@get call.respond(HttpStatusCode.NotFound)
            val thumb = io { repo.thumbnail(hash) } ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respond(LocalPathContent(thumb, ContentType.Image.JPEG))
        }

        // A video's stills from start to end, for previews.js to step through.
        get("/web/frames/{hash}/{n}") {
            val hash = call.parameters["hash"]!!
            val n = call.parameters["n"]?.toIntOrNull()
            if (!BlobStore.isHash(hash) || n == null) return@get call.respond(HttpStatusCode.NotFound)
            val frame = io { repo.frame(hash, n) } ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respond(LocalPathContent(frame, ContentType.Image.JPEG))
        }
    }
}

private data class Pairing(val server: String, val code: String)

/**
 * How devices reach this server: through `tailscale serve`, so HTTPS on the tailnet name. The
 * install script sets MEDIASYNC_PUBLIC_URL to that, so the pairing code is right even when the web
 * UI itself was opened on localhost.
 */
private fun ApplicationCall.publicUrl(): String {
    System.getenv("MEDIASYNC_PUBLIC_URL")?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
    val host = request.headers["X-Forwarded-Host"] ?: request.headers[HttpHeaders.Host] ?: "localhost"
    val proto = request.headers["X-Forwarded-Proto"] ?: if (host.substringBefore(':').endsWith(".ts.net")) "https" else "http"
    return "$proto://$host"
}

// ---- Pages ----

/** [refresh]: reload every so many seconds, for a page following a job. */
internal fun HTML.page(pageTitle: String, loggedIn: Boolean = true, refresh: Int? = null, content: FlowContent.() -> Unit) {
    head {
        meta(charset = "utf-8")
        refresh?.let { meta { httpEquiv = "refresh"; this.content = it.toString() } }
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("$pageTitle · MediaSync")
        link(rel = "stylesheet", href = "/static/style.css")
        link(rel = "icon", href = "/static/icon.svg")
    }
    body {
        header {
            a(href = "/", classes = "brand") { +"MediaSync" }
            if (loggedIn) {
                nav {
                    a(href = "/browse") { +"Library" }
                    a(href = "/images") { +"Images" }
                    a(href = "/videos") { +"Videos" }
                    a(href = "/games") { +"Games" }
                    a(href = "/revisions") { +"History" }
                    a(href = "/resolve") { +"Resolve" }
                    a(href = "/cleanup") { +"Cleanup" }
                    a(href = "/devices") { +"Devices" }
                    a(href = "/settings") { +"Settings" }
                    form(action = "/logout", method = FormMethod.post) { button(classes = "link") { +"Log out" } }
                }
            }
        }
        main { content() }
    }
}

private fun HTML.loginPage(failed: Boolean) = page("Log in", loggedIn = false) {
    form(action = "/login", method = FormMethod.post, classes = "card login") {
        label {
            +"Admin password"
            input(type = InputType.password, name = "password") {
                required = true
                autoFocus = true
                attributes["autocomplete"] = "current-password"
            }
        }
        if (failed) p("error") { +"That password didn't match." }
        button(classes = "primary") { +"Log in" }
    }
}

private fun HTML.browsePage(folder: String, listing: Listing, formats: Map<String, List<String>>, artists: List<String>) = page(folder.ifEmpty { "Library" }) {
    h1 { breadcrumbs(folder) }
    if (listing.assets.any { it.kind == AssetKind.AUDIO && RepoPaths.isMusic(it.path) }) {
        p { a(href = "/album?path=" + enc(folder)) { +"Edit this album's tags" } }
    }
    if (listing.assets.any { RepoPaths.isPhoto(it.kind, it.path) }) {
        p { a(href = "/images?path=" + enc(folder)) { +"View and date these photos" } }
    } else if (folder.startsWith(RepoPaths.IMAGES) && listing.assets.isNotEmpty()) {
        p { a(href = "/images?path=" + enc(folder)) { +"View as a gallery" } }
    } else if (listing.assets.any { it.kind == AssetKind.VIDEO }) {
        p {
            a(href = "/videos?path=" + enc(folder)) { +"Watch these videos" }
            +" · "
            a(href = "/videos/edit?path=" + enc(folder)) { +"Edit these videos' details" }
        }
    } else if (listing.assets.any { it.kind == AssetKind.GAME }) {
        p { a(href = "/games/game?folder=" + enc(folder)) { +"This game's details" } }
    } else if (RepoPaths.GAMES.startsWith("$folder/") || folder.startsWith(RepoPaths.GAMES)) {
        p { a(href = "/games") { +"Games, by platform" } }
    }
    artists.forEach { renameArtistForm(it) }
    if (listing.folders.isEmpty() && listing.assets.isEmpty()) p("empty") { +"Nothing here yet." }
    ul("list") {
        listing.folders.forEach { (name, count) ->
            li {
                a(href = "/browse?path=" + enc(if (folder.isEmpty()) name else "$folder/$name")) { +"$name/" }
                span("muted") { +" $count" }
            }
        }
        listing.assets.forEach { v ->
            li {
                val viewable = v.kind == AssetKind.IMAGE || v.kind == AssetKind.VIDEO
                a(href = if (viewable) viewerUrl(v.assetId) else "/assets/${v.assetId}") { +RepoPaths.name(v.path) }
                span("muted") { +(" " + formats[v.assetId].orEmpty().joinToString(" · ")) }
            }
        }
    }
    p { a(href = "/deleted") { +"Recently deleted" } }
}

/** [folder]'s path, each part linking to its Library listing, or where [href] says. */
internal fun FlowContent.breadcrumbs(folder: String, href: (String) -> String = { "/browse?path=" + enc(it) }) {
    a(href = "/browse") { +"Library" }
    var path = ""
    folder.split('/').filter { it.isNotEmpty() }.forEach { part ->
        path = if (path.isEmpty()) part else "$path/$part"
        +" / "
        a(href = href(path)) { +part }
    }
}

private fun HTML.assetPage(head: Version, dto: com.tlisonbee.mediasync.api.AssetDto, history: com.tlisonbee.mediasync.api.AssetHistory, video: VideoInfo?) =
    page(RepoPaths.name(head.path)) {
        h1 {
            breadcrumbs(RepoPaths.parent(head.path))
            +" / ${RepoPaths.name(head.path)}"
        }
        if (head.deleted) p("notice") { +"This file is deleted. Restore an earlier version below to bring it back." }
        div("asset") {
            head.pictures.firstOrNull()?.let { img(src = "/web/blobs/${it.blob}", alt = "Cover", classes = "cover") }
            if ((head.kind == AssetKind.IMAGE || head.kind == AssetKind.VIDEO) && !head.deleted) {
                dto.renditions.firstOrNull()?.let { r ->
                    a(href = viewerUrl(head.assetId)) { img(src = "/web/thumbs/${r.blob}", alt = "View", classes = "cover") }
                }
            }
            table("fields") {
                tbody {
                    head.category?.let { tr { th { +"category" }; td { +it } } }
                    orderedTags(head.tags).forEach { (field, values) ->
                        tr {
                            th { +field }
                            td { +values.joinToString("; ") }
                        }
                    }
                }
            }
        }
        video?.let { videoSections(head, it) }
        if (head.kind == AssetKind.GAME && !head.deleted) {
            p { a(href = "/games/game?folder=" + enc(RepoPaths.gameFolder(head.path) ?: RepoPaths.parent(head.path))) { +"This game's details and devices" } }
        }
        h2 { +"Renditions" }
        table {
            thead { tr { th { +"Format" }; th { +"Size" }; th { +"Length" }; th { +"Media hash" } } }
            tbody {
                dto.renditions.forEach { r ->
                    tr {
                        td { +r.format }
                        td("num") { +bytes(r.size) }
                        td { +(r.durationMs?.let(::duration) ?: "") }
                        td { code { +r.mediaHash.take(12) } }
                    }
                }
            }
        }
        h2 { +"History" }
        history.versions.forEachIndexed { i, entry ->
            section("version") {
                p {
                    a(href = "/revisions/${entry.version.rev}") { +"r${entry.version.rev}" }
                    +" · ${entry.deviceName} · ${time(entry.createdAt)} · "
                    +entry.changes.joinToString(", ") { it.name.lowercase() }
                    if (i > 0) {
                        form(action = "/assets/${head.assetId}/restore", method = FormMethod.post, classes = "inline") {
                            hiddenInput(name = "toRev") { value = entry.version.rev.toString() }
                            button(classes = "small") { +"Restore this version" }
                        }
                    }
                }
                val previous = history.versions.getOrNull(i + 1)
                if (entry.fields.isNotEmpty() && previous != null) {
                    table("diff") {
                        tbody {
                            entry.fields.forEach { f ->
                                tr {
                                    th { +f.field }
                                    td("old") { +show(f.field, f.old) }
                                    td { +"→" }
                                    td("new") { +show(f.field, f.new) }
                                    td {
                                        if (f.field != Fields.PATH || !RepoPaths.followsFields(head.kind, head.path, head.category)) {
                                            form(action = "/assets/${head.assetId}/restore", method = FormMethod.post, classes = "inline") {
                                                hiddenInput(name = "toRev") { value = previous.version.rev.toString() }
                                                hiddenInput(name = "field") { value = f.field }
                                                button(classes = "small") {
                                                    attributes["title"] = "Put back the old value"
                                                    +"Restore"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

private fun HTML.deletedPage(deleted: List<Version>) = page("Recently deleted") {
    h1 { +"Recently deleted" }
    if (deleted.isEmpty()) p("empty") { +"Nothing has been deleted." }
    ul("list") {
        deleted.forEach { v ->
            li {
                a(href = "/assets/${v.assetId}") { +v.path }
                span("muted") { +" deleted in r${v.rev}" }
            }
        }
    }
}

private fun HTML.revisionsPage(revisions: List<RevisionDto>, more: Boolean) = page("History") {
    h1 { +"History" }
    if (revisions.isEmpty()) p("empty") { +"No revisions yet." }
    revisionList(revisions)
    if (more) p { a(href = "/revisions?before=${revisions.last().rev}") { +"Older" } }
}

private fun FlowContent.revisionList(revisions: List<RevisionDto>) = ul("list revisions") {
    revisions.forEach { r ->
        li {
            a(href = "/revisions/${r.rev}") { +"r${r.rev}" }
            +" · ${r.deviceName} · ${time(r.createdAt)} · ${r.message}"
        }
    }
}

private fun HTML.diffPage(diff: com.tlisonbee.mediasync.api.RevisionDiff) = page("r${diff.revision.rev}") {
    val r = diff.revision
    h1 { +"r${r.rev}: ${r.message}" }
    p("muted") {
        +"${r.deviceName} · ${time(r.createdAt)}"
        r.reverts?.let { +" · reverts "; a(href = "/revisions/$it") { +"r$it" } }
    }
    if (diff.assets.isNotEmpty()) {
        form(action = "/revisions/${r.rev}/revert", method = FormMethod.post) {
            button(classes = "danger") { +"Revert this revision" }
        }
    }
    diff.assets.forEach { a ->
        section("version") {
            p {
                a(href = "/assets/${a.assetId}") { +a.path }
                +(" · " + a.changes.joinToString(", ") { it.name.lowercase() })
            }
            table("diff") { diffRows(a.fields, a.changes) }
        }
    }
}

private fun TABLE.diffRows(fields: List<FieldDiff>, changes: List<ChangeType>) = tbody {
    // An added file's every field is new; listing them all adds nothing.
    val shown = if (ChangeType.ADDED in changes) fields.filter { it.field == Fields.PATH } else fields
    shown.forEach { f ->
        tr {
            th { +f.field }
            td("old") { +show(f.field, f.old) }
            td { +"→" }
            td("new") { +show(f.field, f.new) }
        }
    }
}

private fun HTML.resolvePage(issues: List<IssueDto>, batches: List<Batch>, done: Long?) = page("Resolve") {
    h1 { +"Resolve" }
    if (done != null) {
        p("notice") { +"Settled in "; a(href = "/revisions/$done") { +"r$done" }; +". History can revert it in one step." }
    }
    if (issues.isEmpty()) p("empty") { +"Nothing waits for a decision." }
    if (batches.isNotEmpty()) {
        p("muted") {
            +"Entries the server can judge are grouped, each group with one choice for all of them. A group is settled in one "
            +"revision, which History can revert; untick any you'd rather decide yourself."
        }
        batches.forEach { batchCard(it) }
    }
    val batched = batches.flatMap { batch -> batch.items.map { it.issue.id } }.toSet()
    val rest = issues.filter { it.id !in batched }
    if (batches.isNotEmpty() && rest.isNotEmpty()) h2 { +"One by one" }
    rest.forEach { issueCard(it) }
}

private fun FlowContent.batchCard(batch: Batch) {
    val n = batch.items.size
    section("card batch") {
        h2 { +batch.kind.title; span("badge") { +n.toString() } }
        p("muted") { +batch.kind.help }
        form(action = "/resolve/batch", method = FormMethod.post) {
            hiddenInput(name = "batch") { value = batch.kind.name.lowercase() }
            details {
                summary { +"Show ${if (n == 1) "it" else "all $n"}" }
                table {
                    tbody {
                        batch.items.forEach { item ->
                            tr {
                                td {
                                    input(type = InputType.checkBox, name = "id") {
                                        value = item.issue.id.toString()
                                        checked = true
                                    }
                                }
                                td {
                                    a(href = "/assets/${item.issue.assetId}") { +item.issue.path.removePrefix(RepoPaths.MUSIC) }
                                    item.notes.forEach { div("muted") { +it } }
                                }
                                td { a(href = "/resolve/${item.issue.id}") { +"Decide" } }
                            }
                        }
                    }
                }
            }
            div("actions") {
                button(classes = "primary") { +batch.kind.action }
                batch.kind.alternative?.let { text ->
                    button {
                        name = "how"
                        value = "alternative"
                        +text
                    }
                }
            }
        }
    }
}

private fun FlowContent.issueCard(issue: IssueDto) {
    section("card issue") {
        h2 {
            +issueTitle(issue.kind)
            +" · "
            a(href = "/assets/${issue.assetId}") { +issue.path }
        }
        p("muted") { +"Raised in r${issue.rev} by ${issue.deviceName}. ${issueHelp(issue.kind)}" }
        val fields = (issue.fields.ifEmpty { issue.candidates.flatMap { it.fields.keys }.distinct() })
        form(action = "/resolve/${issue.id}", method = FormMethod.post) {
            table("candidates") {
                thead {
                    tr {
                        th { }
                        issue.candidates.forEachIndexed { i, c ->
                            th { +c.label; if (i == issue.provisional) span("badge") { +"in use" } }
                        }
                    }
                }
                tbody {
                    if (issue.kind != IssueKind.FIELD_CONFLICT) {
                        tr {
                            th { +"path" }
                            issue.candidates.forEach { c -> td { +(c.path ?: "") } }
                        }
                    }
                    if (issue.candidates.any { it.mediaHash != null }) {
                        tr {
                            th { +"media" }
                            issue.candidates.forEach { c -> td { code { +(c.mediaHash?.take(12) ?: "") } } }
                        }
                    }
                    orderedFields(fields).forEach { field ->
                        val values = issue.candidates.map { it.fields[field] }
                        tr(if (values.distinct().size > 1) "differs" else null) {
                            th { +field }
                            values.forEachIndexed { i, v ->
                                td {
                                    if (issue.kind in MERGEABLE && values.distinct().size > 1) {
                                        label("pick") {
                                            input(type = InputType.radio, name = "f.$field") {
                                                value = i.toString()
                                                checked = i == issue.provisional
                                            }
                                            +show(field, v)
                                        }
                                    } else {
                                        +show(field, v)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            div("actions") {
                choices(issue).forEach { (choice, text) ->
                    button(classes = if (choice == "keep") "primary" else null) {
                        name = "choice"
                        value = choice
                        +text
                    }
                }
            }
        }
    }
}

private val MERGEABLE = setOf(IssueKind.FIELD_CONFLICT, IssueKind.DUPLICATE)

private fun issueTitle(kind: IssueKind) = when (kind) {
    IssueKind.FIELD_CONFLICT -> "Conflicting edits"
    IssueKind.DUPLICATE -> "Duplicate"
    IssueKind.POSSIBLE_RENDITION -> "Same track, another format?"
    IssueKind.PATH_COLLISION -> "Two files, one path"
    IssueKind.MEDIA_CONFLICT -> "Replaced twice"
}

private fun issueHelp(kind: IssueKind) = when (kind) {
    IssueKind.FIELD_CONFLICT -> "Two devices changed the same fields differently."
    IssueKind.DUPLICATE -> "An import found this audio already in the library with other tags."
    IssueKind.POSSIBLE_RENDITION -> "Tags and length match a track in another format. Link them to keep one set of tags."
    IssueKind.PATH_COLLISION -> "The tags put two files at one path, so one was moved aside. Retag one of them to fix it."
    IssueKind.MEDIA_CONFLICT -> "Two devices replaced the audio differently."
}

/** Each button's choice, as the form sends it ("link:mp3" links keeping only the MP3), and its text. */
private fun choices(issue: IssueDto): List<Pair<String, String>> = when (issue.kind) {
    IssueKind.FIELD_CONFLICT -> listOf("keep" to "Keep current", "other" to "Use the other side", "merge" to "Merge as picked")
    IssueKind.DUPLICATE -> listOf("keep" to "Keep current", "other" to "Use the imported tags", "merge" to "Merge as picked", "both" to "Keep both")
    IssueKind.POSSIBLE_RENDITION -> listOf("keep" to "Keep separate", "link:mp3" to "Link, keeping only the MP3", "link" to "Link as renditions")
    IssueKind.PATH_COLLISION ->
        if (issue.path.startsWith(RepoPaths.MUSIC)) {
            listOf("keep" to "OK", "link:mp3" to "Same track: keep only the MP3", "link" to "Same track: keep both")
        } else {
            listOf("keep" to "OK")
        }
    IssueKind.MEDIA_CONFLICT -> listOf("keep" to "Keep current", "other" to "Use the other side")
}

/** [web]: the devices allowed into the web UI, from the Android app. */
private fun HTML.devicesPage(devices: List<Device>, pairing: Pairing?, head: Long, web: Set<String>) = page("Devices") {
    h1 { +"Devices" }
    if (pairing != null) {
        section("card pairing") {
            h2 { +"Pair a device" }
            val payload = "mediasync://pair?server=${enc(pairing.server)}&code=${pairing.code}"
            div("qr") { unsafe { +qrSvg(payload) } }
            p { +"Scan this in the Android app, or give the desktop client:" }
            p { +"Server "; code { +pairing.server } }
            p { +"Code "; code("code") { +pairing.code } }
            p("muted") { +"The code works once, for ${PAIRING_LIFETIME.inWholeMinutes} minutes." }
        }
    } else {
        form(action = "/devices/pair", method = FormMethod.post) { button(classes = "primary") { +"Pair a device" } }
    }
    devices.filter { it.kind != com.tlisonbee.mediasync.api.DeviceKind.WEB }.forEach { d ->
        section("card device") {
            h2 {
                +d.name
                span("muted") { +" · ${d.kind.name.lowercase()}" }
                if (d.revokedAt != null) span("badge") { +"revoked" }
            }
            p("muted") {
                +"Paired ${time(d.createdAt)} · "
                +(d.lastSeenAt?.let { "last seen ${time(it)}" } ?: "never seen")
                +(" · " + if (d.syncedRev >= head) "up to date" else "at r${d.syncedRev} of r$head")
            }
            if (d.id in web) {
                form(action = "/devices/${d.id}/web-access/revoke", method = FormMethod.post, classes = "web-access") {
                    span { +"Can open this web UI in the app, without the password." }
                    button(classes = "link") { +"Revoke web access" }
                }
            }
            if (d.revokedAt == null) {
                form(action = "/devices/${d.id}/subscription", method = FormMethod.post) {
                    label {
                        +"Subscription"
                        textArea(rows = "12", classes = "mono") {
                            name = "subscription"
                            +prettyJson.encodeToString(Subscription.serializer(), d.subscription)
                        }
                    }
                    div("actions") { button { +"Save subscription" } }
                }
                form(action = "/devices/${d.id}/revoke", method = FormMethod.post, classes = "delete") {
                    button(classes = "danger") { +"Revoke" }
                }
            }
        }
    }
}

private val prettyJson = kotlinx.serialization.json.Json(ApiJson) { prettyPrint = true }

private fun HTML.settingsPage(settings: Settings, saved: Boolean) = page("Settings") {
    h1 { +"Settings" }
    form(action = "/settings", method = FormMethod.post, classes = "card") {
        h2 { +"Music tag policy" }
        p("muted") { +"Only these fields are kept. Every other tag is removed from files at their next sync." }
        val fields = settings.tagPolicy.fields[AssetKind.AUDIO].orEmpty()
        TagField.MUSIC.forEach { field ->
            label("check") {
                input(type = InputType.checkBox, name = "field.$field") { checked = field in fields }
                +field
            }
        }
        label("check") {
            input(type = InputType.checkBox, name = "cover") {
                checked = TagPolicy.FRONT_COVER in settings.tagPolicy.pictures[AssetKind.AUDIO].orEmpty()
            }
            +"front cover"
        }
        if (saved) p("notice") { +"Saved." }
        div("actions") { button(classes = "primary") { +"Save" } }
    }
}

// ---- Formatting ----

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun time(millis: Long) = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

internal fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GiB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MiB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KiB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}

internal fun duration(ms: Long) =
    if (ms >= 3600_000) "%d:%02d:%02d".format(ms / 3600_000, ms / 60_000 % 60, ms / 1000 % 60) else "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

internal fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

private fun show(field: String, values: List<String>?): String = when {
    values == null -> "(none)"
    field == Fields.PICTURES -> values.joinToString { it.substringBefore(':') + " picture" }
    field == Fields.MEDIA -> values.joinToString { it.take(12) }
    else -> values.joinToString("; ")
}

private fun orderedTags(tags: Map<String, List<String>>) =
    tags.entries.sortedBy { TagField.MUSIC.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }.map { it.key to it.value }

private fun orderedFields(fields: List<String>) =
    fields.sortedBy { TagField.MUSIC.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }

/** A QR code as inline SVG, drawn dark on light whatever the page's colors. */
fun qrSvg(text: String): String {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val path = StringBuilder()
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (matrix[x, y]) path.append("M${x} ${y}h1v1h-1z")
        }
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" shape-rendering="crispEdges" role="img" aria-label="Pairing QR code">""" +
        """<rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
}
