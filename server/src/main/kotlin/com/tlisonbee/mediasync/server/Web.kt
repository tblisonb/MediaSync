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
    post("/logout") {
        call.sessions.clear<WebSession>()
        call.respondRedirect("/login")
    }

    authenticate("web") {
        get("/") {
            val stats = io { repo.stats() }
            val devices = io { repo.devices() }
            val issues = io { repo.openIssueCount() }
            val recent = io { repo.revisions(null, 10).revisions }
            call.respondHtml { statusPage(stats, devices, issues, recent) }
        }

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
            call.respondHtml { resolvePage(issues) }
        }
        post("/resolve/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: badRequest("bad issue id")
            val form = call.receiveParameters()
            val choice = Choice.entries.firstOrNull { it.name.lowercase() == form["choice"] } ?: badRequest("pick a choice")
            val fields = form.names().filter { it.startsWith("f.") }
                .associate { it.removePrefix("f.") to (form[it]?.toIntOrNull() ?: 0) }.takeIf { it.isNotEmpty() }
            io { repo.resolve(repo.webDeviceId(), id, ResolveRequest(choice, fields)) }
            call.respondRedirect("/resolve")
        }

        get("/devices") {
            val devices = io { repo.devices() }
            val head = io { repo.head() }
            call.respondHtml { devicesPage(devices, pairing = null, head = head) }
        }
        post("/devices/pair") {
            val code = io { repo.newPairingCode() }
            val server = call.publicUrl()
            val devices = io { repo.devices() }
            val head = io { repo.head() }
            call.respondHtml { devicesPage(devices, Pairing(server, code), head = head) }
        }
        post("/devices/{id}/revoke") {
            io { repo.revoke(call.parameters["id"]!!) }
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

        // Cover art and other images for the pages above.
        get("/web/blobs/{hash}") {
            val hash = call.parameters["hash"]!!
            if (!BlobStore.isHash(hash)) return@get call.respond(HttpStatusCode.NotFound)
            val blob = io { repo.blob(hash) }?.takeIf { it.mime.startsWith("image/") } ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respond(LocalPathContent(repo.dir.blob(hash), ContentType.parse(blob.mime)))
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

private fun HTML.statusPage(stats: Stats, devices: List<Device>, issues: Long, recent: List<RevisionDto>) = page("Status") {
    h1 { +"Status" }
    div("tiles") {
        tile("Revision", "r${stats.head}")
        tile("Tracks", (stats.assets[AssetKind.AUDIO] ?: 0).toString())
        tile("Other files", stats.assets.filterKeys { it != AssetKind.AUDIO }.values.sum().toString())
        tile("Stored", "${bytes(stats.bytes)} in ${stats.blobs} blobs")
    }
    if (issues > 0) {
        p("notice") { a(href = "/resolve") { +"$issues ${if (issues == 1L) "entry needs" else "entries need"} a decision" } }
    }
    h2 { +"Devices" }
    val active = devices.filter { it.revokedAt == null && it.kind != com.tlisonbee.mediasync.api.DeviceKind.WEB }
    if (active.isEmpty()) p("empty") { +"No devices yet. "; a(href = "/devices") { +"Pair one" } }
    table {
        tbody {
            active.forEach { d ->
                tr {
                    td { +d.name }
                    td("muted") { +d.kind.name.lowercase() }
                    td { +(d.lastSeenAt?.let { "seen ${time(it)}" } ?: "never seen") }
                    td { +(if (d.syncedRev >= stats.head) "up to date" else "at r${d.syncedRev}") }
                }
            }
        }
    }
    h2 { +"Recent changes" }
    revisionList(recent)
    p { a(href = "/revisions") { +"All history" } }
}

private fun FlowContent.tile(label: String, value: String) = div("tile") {
    span("label") { +label }
    span("value") { +value }
}

private fun HTML.browsePage(folder: String, listing: Listing, formats: Map<String, List<String>>, artists: List<String>) = page(folder.ifEmpty { "Library" }) {
    h1 { breadcrumbs(folder) }
    if (listing.assets.any { it.kind == AssetKind.AUDIO && RepoPaths.isMusic(it.path) }) {
        p { a(href = "/album?path=" + enc(folder)) { +"Edit this album's tags" } }
    }
    if (listing.assets.any { it.kind == AssetKind.VIDEO }) {
        p { a(href = "/videos/edit?path=" + enc(folder)) { +"Edit these videos' details" } }
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
                a(href = "/assets/${v.assetId}") { +RepoPaths.name(v.path) }
                span("muted") { +(" " + formats[v.assetId].orEmpty().joinToString(" · ")) }
            }
        }
    }
    p { a(href = "/deleted") { +"Recently deleted" } }
}

internal fun FlowContent.breadcrumbs(folder: String) {
    a(href = "/browse") { +"Library" }
    var path = ""
    folder.split('/').filter { it.isNotEmpty() }.forEach { part ->
        path = if (path.isEmpty()) part else "$path/$part"
        +" / "
        a(href = "/browse?path=" + enc(path)) { +part }
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
        h2 { +"Renditions" }
        table {
            thead { tr { th { +"Format" }; th { +"Size" }; th { +"Length" }; th { +"Media hash" } } }
            tbody {
                dto.renditions.forEach { r ->
                    tr {
                        td { +r.format }
                        td { +bytes(r.size) }
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

private fun HTML.resolvePage(issues: List<IssueDto>) = page("Resolve") {
    h1 { +"Resolve" }
    if (issues.isEmpty()) p("empty") { +"Nothing waits for a decision." }
    issues.forEach { issue ->
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
                    choices(issue.kind).forEach { (choice, text) ->
                        button(classes = if (choice == Choice.KEEP) "primary" else null) {
                            name = "choice"
                            value = choice.name.lowercase()
                            +text
                        }
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

private fun choices(kind: IssueKind): List<Pair<Choice, String>> = when (kind) {
    IssueKind.FIELD_CONFLICT -> listOf(Choice.KEEP to "Keep current", Choice.OTHER to "Use the other side", Choice.MERGE to "Merge as picked")
    IssueKind.DUPLICATE -> listOf(Choice.KEEP to "Keep current", Choice.OTHER to "Use the imported tags", Choice.MERGE to "Merge as picked", Choice.BOTH to "Keep both")
    IssueKind.POSSIBLE_RENDITION -> listOf(Choice.KEEP to "Keep separate", Choice.LINK to "Link as renditions")
    IssueKind.PATH_COLLISION -> listOf(Choice.KEEP to "OK")
    IssueKind.MEDIA_CONFLICT -> listOf(Choice.KEEP to "Keep current", Choice.OTHER to "Use the other side")
}

private fun HTML.devicesPage(devices: List<Device>, pairing: Pairing?, head: Long) = page("Devices") {
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

private fun duration(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

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
