package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.PhotoDates
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.server.html.respondHtml
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.video
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Longer side of a photo's thumbnail, in pixels: sharp at the grid's size on a high-density screen. */
private const val THUMB_SIZE = 320

/** Files on one page of the grid. */
private const val PAGE_SIZE = 120

/** What browsers show in an img: anything else (HEIC, raw) gets its thumbnail and a download. */
private val SHOWN = setOf("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp", "image/svg+xml")

/** One file in the grid: its version, and the blob its thumbnail is made from. */
private class Tile(val version: Version, val blob: String?)

/** A folder below the one shown: how many images, videos and other files it holds, and its newest image's blob. */
private class SubFolder(val path: String, val images: Int, val videos: Int, val others: Int, val cover: String?)

/** A file in the viewer, with its neighbours in its folder and where it stands among them. */
private class Viewed(val version: Version, val blob: BlobRow?, val format: String?, val previous: Version?, val next: Version?, val index: Int, val count: Int)

private fun Repository.blobOf(v: Version): String? = v.renditions.firstOrNull()?.let { media(it)?.baseBlob }

/** The thumbnail of an image blob, made and kept the first time; null if Java can't decode it (HEIC, raw). */
fun Repository.thumbnail(hash: String): Path? {
    val file = dir.thumb(hash)
    if (Files.exists(file)) return file
    blob(hash)?.takeIf { it.mime.startsWith("image/") } ?: return null
    val bytes = Images.preview(dir.blob(hash), THUMB_SIZE) ?: return null
    Files.createDirectories(file.parent)
    val tmp = Files.createTempFile(file.parent, ".thumb", ".tmp")
    Files.write(tmp, bytes)
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    return file
}

internal fun viewerUrl(assetId: String) = "/images/view?id=$assetId"

private fun inPhotos(folder: String) = "$folder/".startsWith(RepoPaths.PHOTOS)

fun Route.imageRoutes(repo: Repository) {
    get("/images") {
        val folder = call.parameters["path"].orEmpty().trim('/').ifEmpty { RepoPaths.IMAGES.trimEnd('/') }
        val page = call.parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val (files, folders) = io {
            repo.db.read {
                val (here, below) = repo.liveUnder(folder).partition { RepoPaths.parent(it.path) == folder }
                // Years newest first and Undated last, as images/photos holds; other folders by name.
                val folders = below.groupBy { it.path.removePrefix("$folder/").substringBefore('/') }.map { (name, inside) ->
                    val cover = inside.filter { it.kind == AssetKind.IMAGE }.maxByOrNull { it.path }
                    SubFolder(
                        "$folder/$name", inside.count { it.kind == AssetKind.IMAGE }, inside.count { it.kind == AssetKind.VIDEO },
                        inside.count { it.kind != AssetKind.IMAGE && it.kind != AssetKind.VIDEO }, cover?.let { repo.blobOf(it) },
                    )
                }.sortedWith(
                    compareBy<SubFolder> { RepoPaths.name(it.path) == RepoPaths.UNDATED }
                        .thenBy { RepoPaths.name(it.path).toIntOrNull()?.let { year -> -year } ?: Int.MAX_VALUE }
                        .thenBy { RepoPaths.name(it.path).lowercase() },
                )
                here.map { v -> Tile(v, repo.blobOf(v)) } to folders
            }
        }
        if (files.isEmpty() && folders.isEmpty()) notFound("nothing in $folder")
        call.respondHtml { imagesPage(folder, files, folders, page, call.parameters["done"], call.parameters["error"]) }
    }
    // Where Photos was before it took in the rest of images/.
    get("/photos") {
        call.respondRedirect("/images" + (call.request.queryString().takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""))
    }
    get("/images/view") {
        val id = call.parameters["id"] ?: badRequest("which file?")
        val viewed = io {
            repo.db.read {
                val v = repo.headVersion(id)?.takeIf { !it.deleted } ?: notFound("no file $id")
                val siblings = repo.liveIn(RepoPaths.parent(v.path))
                val i = siblings.indexOfFirst { it.assetId == id }
                val media = v.renditions.firstOrNull()?.let { repo.media(it) }
                Viewed(v, media?.let { repo.blob(it.baseBlob) }, media?.format, siblings.getOrNull(i - 1), siblings.getOrNull(i + 1), i + 1, siblings.size)
            }
        }
        call.respondHtml { viewerPage(viewed) }
    }
    post("/photos/date") {
        val form = call.receiveParameters()
        val folder = form["folder"].orEmpty()
        val page = form["page"]?.toIntOrNull() ?: 1
        val back = "/images?path=${enc(folder)}&page=$page"
        val revs = form.names().filter { it.startsWith("rev.") }
            .associate { it.removePrefix("rev.") to (form[it]?.toLongOrNull() ?: badRequest("bad revision")) }
        val which = form["which"]
        val chosen = if (which == "page") revs.keys.toList() else form.getAll("asset").orEmpty().filter { it in revs }
        if (chosen.isEmpty()) return@post call.respondRedirect("$back&error=" + enc("Tick some photos first."))
        val typed = form["date"].orEmpty().trim()
        val date = if (which == "clear") null else PhotoDates.normalize(typed) ?: return@post call.respondRedirect(
            "$back&error=" + enc("“$typed” isn't a date MediaSync can file by: use a year, a month or a day (2019, 2019-07 or 2019-07-14)."),
        )
        val n = chosen.size
        val photos = if (n == 1) "1 photo" else "$n photos"
        val message = if (date == null) "Removed the date from $photos" else "Dated $photos $date"
        io { repo.editTags(repo.webDeviceId(), message, chosen.associateWith { mapOf(TagField.DATE to listOfNotNull(date)) }, revs.filterKeys { it in chosen }) }
        call.respondRedirect("$back&done=" + enc(message))
    }
}

private fun HTML.imagesPage(folder: String, files: List<Tile>, folders: List<SubFolder>, page: Int, done: String?, error: String?) = page(RepoPaths.name(folder)) {
    h1 { breadcrumbs(folder) }
    done?.let { p("notice") { +"$it." } }
    error?.let { p("notice error") { +it } }
    if (inPhotos(folder)) {
        p("muted") {
            +"Photos are filed as images/photos/{year}/{date} {name}, by the date the camera recorded or their name gives, "
            +"and images/photos/Undated/ without one. A date can be a year, a month or a day: 2019, 2019-07 or 2019-07-14."
        }
    }
    if (folders.isNotEmpty()) {
        div("photo-grid") {
            folders.forEach { f ->
                val name = RepoPaths.name(f.path)
                a(href = "/images?path=${enc(f.path)}", classes = "photo folder") {
                    if (f.cover != null) img(alt = name, src = "/web/thumbs/${f.cover}") { attributes["loading"] = "lazy" } else span("no-thumb") { +name }
                    span("name") { +name }
                    span("date muted") {
                        val noun = if (inPhotos(f.path)) "photo" else "image"
                        +listOfNotNull(
                            f.images.takeIf { it > 0 || f.videos + f.others == 0 }?.let { "%,d %s".format(it, if (it == 1) noun else noun + "s") },
                            f.videos.takeIf { it > 0 }?.let { "%,d %s".format(it, if (it == 1) "video" else "videos") },
                            f.others.takeIf { it > 0 }?.let { "%,d other %s".format(it, if (it == 1) "file" else "files") },
                        ).joinToString(" · ")
                    }
                }
            }
        }
    }
    if (files.isNotEmpty()) {
        val pages = (files.size + PAGE_SIZE - 1) / PAGE_SIZE
        val current = page.coerceAtMost(pages)
        val shown = files.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE)
        if (inPhotos(folder)) datingGrid(folder, shown, current) else gallery(shown)
        if (pages > 1) {
            p("pages") {
                (1..pages).forEach { n ->
                    if (n == current) span("current") { +"$n" } else a(href = "/images?path=${enc(folder)}&page=$n") { +"$n" }
                    +" "
                }
            }
        }
    }
}

private fun FlowContent.preview(tile: Tile) {
    val v = tile.version
    if (v.kind == AssetKind.IMAGE && tile.blob != null) {
        img(alt = RepoPaths.name(v.path), src = "/web/thumbs/${tile.blob}") { attributes["loading"] = "lazy" }
    } else {
        span("no-thumb") { +(if (v.kind == AssetKind.VIDEO) "Video" else "No preview") }
    }
}

/** Photos with boxes to tick for dating; each opens in the viewer from its corner. */
private fun FlowContent.datingGrid(folder: String, shown: List<Tile>, current: Int) {
    form(action = "/photos/date", method = FormMethod.post, classes = "card photos") {
        hiddenInput(name = "folder") { value = folder }
        hiddenInput(name = "page") { value = current.toString() }
        div("actions date-bar") {
            input(type = InputType.text, name = "date") {
                placeholder = "2019, 2019-07 or 2019-07-14"
                attributes["aria-label"] = "Date"
                attributes["inputmode"] = "numeric"
            }
            button(type = ButtonType.submit, name = "which", classes = "primary") { value = "selected"; +"Date the ticked photos" }
            button(type = ButtonType.submit, name = "which") { value = "page"; +"Date all ${shown.size} on this page" }
            button(type = ButtonType.submit, name = "which") { value = "clear"; +"Remove the ticked photos' date" }
        }
        div("photo-grid") {
            shown.forEach { tile ->
                val v = tile.version
                val name = RepoPaths.name(v.path)
                label("photo") {
                    hiddenInput(name = "rev.${v.assetId}") { value = v.rev.toString() }
                    input(type = InputType.checkBox, name = "asset") {
                        value = v.assetId
                        attributes["aria-label"] = name
                    }
                    a(href = viewerUrl(v.assetId), classes = "open") {
                        attributes["title"] = "View $name"
                        attributes["aria-label"] = "View $name"
                        +"⤢"
                    }
                    preview(tile)
                    span("name") { +name }
                    span("date muted") { +(v.tags[TagField.DATE]?.firstOrNull() ?: "No date") }
                }
            }
        }
    }
}

/** Anything else in images/: each opens in the viewer. */
private fun FlowContent.gallery(shown: List<Tile>) {
    div("photo-grid") {
        shown.forEach { tile ->
            val name = RepoPaths.name(tile.version.path)
            a(href = viewerUrl(tile.version.assetId), classes = "photo folder") {
                preview(tile)
                span("name") { +name }
            }
        }
    }
}

private fun HTML.viewerPage(viewed: Viewed) = page(RepoPaths.name(viewed.version.path)) {
    val v = viewed.version
    val name = RepoPaths.name(v.path)
    val folder = RepoPaths.parent(v.path)
    val blob = viewed.blob
    val file = name + (viewed.format?.let { ".$it" } ?: "")
    h1 {
        breadcrumbs(folder)
        +" / $name"
    }
    div("viewer-bar") {
        viewed.previous?.let { a(href = viewerUrl(it.assetId)) { attributes["rel"] = "prev"; +"← Previous" } } ?: span("muted") { +"← Previous" }
        span("muted num") { +"${viewed.index} of ${viewed.count}" }
        viewed.next?.let { a(href = viewerUrl(it.assetId)) { attributes["rel"] = "next"; +"Next →" } } ?: span("muted") { +"Next →" }
        span("spacer") { }
        a(href = "/images?path=${enc(folder)}") { attributes["rel"] = "up"; +"Back to the folder" }
        a(href = "/assets/${v.assetId}") { +"Details" }
        if (blob != null && (blob.mime.startsWith("image/") || blob.mime.startsWith("video/"))) {
            a(href = "/web/blobs/${blob.hash}") { attributes["download"] = file; +"Download" }
        }
    }
    div("viewer") {
        when {
            blob == null -> p("empty") { +"This file has no contents on the server." }
            blob.mime in SHOWN -> a(href = "/web/blobs/${blob.hash}") {
                attributes["title"] = "Open at full size"
                img(alt = name, src = "/web/blobs/${blob.hash}")
            }
            blob.mime.startsWith("video/") -> video {
                attributes["src"] = "/web/blobs/${blob.hash}"
                attributes["controls"] = ""
                attributes["preload"] = "metadata"
            }
            blob.mime.startsWith("image/") -> div("unshown") {
                img(alt = name, src = "/web/thumbs/${blob.hash}")
                p("muted") { +"Browsers can't show ${blob.mime.removePrefix("image/").uppercase()} images at full size: this is a preview. Download it to see the original." }
            }
            else -> p("empty") { +"No preview for this kind of file." }
        }
    }
    p("muted small") {
        +listOfNotNull(
            v.tags[TagField.DATE]?.firstOrNull(),
            viewed.format?.uppercase(),
            blob?.let { bytes(it.size) },
        ).joinToString(" · ")
        if (blob?.mime in SHOWN) +" · click the image to open it at full size; ← and → step through the folder"
    }
    script(src = "/static/viewer.js") { }
}
