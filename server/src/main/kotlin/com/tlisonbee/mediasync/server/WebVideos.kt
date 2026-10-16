package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.Fields
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.io.readByteArray

/** Fields the video editor shows per video, after the category. */
private val VIDEO_FIELDS = listOf(TagField.SHOW, TagField.SEASON, TagField.EPISODE, TagField.TITLE, TagField.YEAR)

private val VIDEO_LABELS = mapOf(
    TagField.SHOW to "Show", TagField.SEASON to "Season", TagField.EPISODE to "Episode", TagField.TITLE to "Title", TagField.YEAR to "Year",
)

private val CATEGORY_LABELS = mapOf(RepoPaths.MOVIES to "Movie", RepoPaths.SHOWS to "Episode", RepoPaths.OTHER to "Other")

/** Subtitles and posters are small; anything bigger isn't one. */
private const val MAX_SIDECAR = 20L shl 20

/** What a video's page shows beside its fields: its sidecars, and which devices keep it. */
class VideoInfo(val sidecars: List<Pair<Version, String>>, val devices: List<Pair<Device, Boolean?>>)

/** Devices with a rule for [path]: whether they keep [assetId] where they pick, null where they keep everything. */
fun Repository.videoDevices(path: String, assetId: String? = null): List<Pair<Device, Boolean?>> =
    devices().filter { it.revokedAt == null && it.kind != DeviceKind.WEB }.mapNotNull { d ->
        val rules = d.subscription.rules.filter { it.covers(path, AssetKind.VIDEO) }
        when {
            rules.isEmpty() -> null
            rules.any { !it.pick } -> d to null
            else -> d to (assetId != null && assetId in picks(d.id))
        }
    }

fun Route.videoRoutes(repo: Repository) {
    get("/videos/edit") {
        val folder = call.parameters["path"].orEmpty().trim('/')
        val videos = io { repo.db.read { repo.liveIn(folder) }.filter { it.kind == AssetKind.VIDEO } }.ifEmpty { notFound("no videos in $folder") }
        val devices = io { repo.videoDevices(videos.first().path).filter { it.second != null }.map { it.first } }
        call.respondHtml { videoEditPage(folder, videos, devices) }
    }
    post("/videos/edit") {
        val form = call.receiveParameters()
        val revs = form.names().filter { it.startsWith("rev.") }
            .associate { it.removePrefix("rev.") to (form[it]?.toLongOrNull() ?: badRequest("bad revision")) }
        val videos = io { revs.keys.map { repo.headVersion(it) ?: notFound("no asset $it") } }
        val edits = videos.associate { v ->
            val changes = mutableMapOf<String, List<String>>()
            form["${v.assetId}.category"]?.let { if (it != v.category) changes[Fields.CATEGORY] = listOf(it) }
            for (field in VIDEO_FIELDS) {
                val value = form["${v.assetId}.$field"]?.trim() ?: continue
                if (value != v.tags[field].orEmpty().joinToString("; ")) changes[field] = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            }
            v.assetId to changes
        }
        io { repo.editTags(repo.webDeviceId(), "Edited ${if (videos.size == 1) videoLabel(videos.single()) else "${videos.size} videos"}", edits, revs) }
        val moved = videos.firstOrNull()?.let { io { repo.headVersion(it.assetId) } }?.path?.let(RepoPaths::parent) ?: form["folder"].orEmpty()
        call.respondRedirect("/browse?path=" + enc(moved))
    }
    // Keep or remove every video in a folder (a season, say) on one device.
    post("/videos/pick") {
        val form = call.receiveParameters()
        val folder = form["folder"].orEmpty()
        val device = form["device"] ?: badRequest("which device?")
        val ids = io { repo.db.read { repo.liveIn(folder) }.filter { it.kind == AssetKind.VIDEO }.map { it.assetId } }
        io { if (form["keep"] == "yes") repo.setPicks(device, ids, emptyList()) else repo.setPicks(device, emptyList(), ids) }
        call.respondRedirect("/videos/edit?path=" + enc(folder))
    }
    post("/assets/{id}/picks") {
        val id = call.parameters["id"]!!
        val form = call.receiveParameters()
        val video = io { repo.headVersion(id) } ?: notFound("no asset $id")
        for ((device, picked) in io { repo.videoDevices(video.path) }) {
            if (picked == null) continue
            val keep = form["keep.${device.id}"] != null
            io { if (keep) repo.setPicks(device.id, listOf(id), emptyList()) else repo.setPicks(device.id, emptyList(), listOf(id)) }
        }
        call.respondRedirect("/assets/$id")
    }
    post("/assets/{id}/sidecars") {
        val id = call.parameters["id"]!!
        val video = io { repo.headVersion(id) }?.takeIf { it.kind == AssetKind.VIDEO && !it.deleted } ?: notFound("no video $id")
        var name: String? = null
        var bytes: ByteArray? = null
        var language = ""
        call.receiveMultipart(formFieldLimit = MAX_SIDECAR).forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    name = part.originalFileName
                    bytes = part.provider().readRemaining().readByteArray()
                }
                is PartData.FormItem -> if (part.name == "language") language = part.value.trim()
                else -> Unit
            }
            part.dispose()
        }
        val data = bytes?.takeIf { it.isNotEmpty() } ?: badRequest("choose a file")
        val format = BlobStore.formatOf(name ?: "")
        val suffix = when (format) {
            in SUBTITLE_FORMATS -> "." + RepoPaths.segment(language.lowercase().ifEmpty { "und" }).replace('.', '_')
            in POSTER_FORMATS -> "-poster"
            else -> badRequest("a subtitle (${SUBTITLE_FORMATS.joinToString()}) or a poster (${POSTER_FORMATS.joinToString()})")
        }
        val media = io { repo.blobs.putBytes(data, name ?: "upload.$format") }
        io { repo.addSidecar(repo.webDeviceId(), video, suffix, media) }
        call.respondRedirect("/assets/$id")
    }
    post("/assets/{id}/delete") {
        val id = call.parameters["id"]!!
        val back = call.receiveParameters()["back"]?.takeIf { it.startsWith("/assets/") }
        io { repo.deleteAsset(repo.webDeviceId(), id) }
        call.respondRedirect(back ?: "/assets/$id")
    }
}

/** On a video's page: its subtitles and poster, and which devices keep it. */
internal fun FlowContent.videoSections(video: Version, info: VideoInfo) {
    p { a(href = "/videos/edit?path=" + enc(RepoPaths.parent(video.path))) { +"Edit this video's details" } }

    h2 { +"Subtitles and poster" }
    if (info.sidecars.isEmpty()) p("empty") { +"None yet." }
    ul("list") {
        info.sidecars.forEach { (file, suffix) ->
            li {
                a(href = "/assets/${file.assetId}") { +(RepoPaths.name(file.path)) }
                span("muted") { +(" " + if (file.kind == AssetKind.IMAGE) "poster" else "subtitles, ${suffix.removePrefix(".")}") }
                form(action = "/assets/${file.assetId}/delete", method = FormMethod.post, classes = "inline") {
                    hiddenInput(name = "back") { value = "/assets/${video.assetId}" }
                    button(classes = "small") { +"Remove" }
                }
            }
        }
    }
    form(action = "/assets/${video.assetId}/sidecars", method = FormMethod.post, encType = FormEncType.multipartFormData, classes = "card upload") {
        label {
            +"Add subtitles (.srt, .ass, .vtt…) or a poster (.jpg, .png)"
            input(type = InputType.file, name = "file") {
                required = true
                accept = (SUBTITLE_FORMATS + POSTER_FORMATS).joinToString(",") { ".$it" }
            }
        }
        label {
            +"Subtitle language, e.g. en (leave empty if unknown)"
            input(type = InputType.text, name = "language") { attributes["maxlength"] = "16" }
        }
        div("actions") { button(type = ButtonType.submit) { +"Add" } }
    }

    h2 { +"On devices" }
    val pickers = info.devices.filter { it.second != null }
    info.devices.filter { it.second == null }.forEach { (d, _) -> p("muted") { +"${d.name} keeps every video." } }
    if (info.devices.isEmpty()) p("empty") { +"No device subscribes to videos." }
    if (pickers.isNotEmpty()) {
        form(action = "/assets/${video.assetId}/picks", method = FormMethod.post) {
            pickers.forEach { (d, picked) ->
                label("check") {
                    input(type = InputType.checkBox, name = "keep.${d.id}") { checked = picked == true }
                    +"Keep on ${d.name}"
                }
            }
            div("actions") { button { +"Save" } }
        }
    }
}

private fun HTML.videoEditPage(folder: String, videos: List<Version>, pickers: List<Device>) = page(RepoPaths.name(folder)) {
    h1 { breadcrumbs(folder) }
    form(action = "/videos/edit", method = FormMethod.post, classes = "card videos") {
        hiddenInput(name = "folder") { value = folder }
        p("muted") {
            +"Movies are filed as videos/movies/Title (Year)/, episodes as videos/shows/Show/Season NN/Show - SnnEnn - Title; "
            +"other videos keep their place. Separate several values with “;”."
        }
        table("tracks") {
            thead { tr { th { +"Kind" }; VIDEO_FIELDS.forEach { th(classes = "f-$it") { +VIDEO_LABELS.getValue(it) } } } }
            tbody {
                videos.sortedWith(compareBy({ number(it, TagField.SEASON) }, { number(it, TagField.EPISODE) }, { it.path })).forEach { v ->
                    tr {
                        td {
                            hiddenInput(name = "rev.${v.assetId}") { value = v.rev.toString() }
                            select {
                                name = "${v.assetId}.category"
                                attributes["aria-label"] = "Kind, ${RepoPaths.name(v.path)}"
                                RepoPaths.VIDEO_CATEGORIES.forEach { c ->
                                    option { value = c; selected = c == (v.category ?: RepoPaths.OTHER); +CATEGORY_LABELS.getValue(c) }
                                }
                            }
                        }
                        VIDEO_FIELDS.forEach { field ->
                            td(classes = "f-$field") {
                                input(type = InputType.text, name = "${v.assetId}.$field") {
                                    value = v.tags[field].orEmpty().joinToString("; ")
                                    attributes["aria-label"] = "${VIDEO_LABELS.getValue(field)}, ${RepoPaths.name(v.path)}"
                                }
                            }
                        }
                    }
                }
            }
        }
        div("actions") { button(classes = "primary") { +"Save" } }
    }
    if (pickers.isNotEmpty()) {
        h2 { +"On devices that pick" }
        p("muted") { +"Keep every video here on a device, or remove them all from it." }
        pickers.forEach { d ->
            div("actions") {
                listOf("yes" to "Keep all on ${d.name}", "no" to "Remove all from ${d.name}").forEach { (keep, text) ->
                    form(action = "/videos/pick", method = FormMethod.post) {
                        hiddenInput(name = "folder") { value = folder }
                        hiddenInput(name = "device") { value = d.id }
                        hiddenInput(name = "keep") { value = keep }
                        button { +text }
                    }
                }
            }
        }
    }
}

private fun number(v: Version, field: String) = v.tags[field]?.firstOrNull()?.toIntOrNull() ?: 0
