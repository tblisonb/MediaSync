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
import kotlinx.html.id
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

/** The galleries, a folder at a time: images/ (photos by year, with dating) and videos/. */
internal enum class Gallery(val root: String, val href: String) {
    IMAGES(RepoPaths.IMAGES, "/images"),
    VIDEOS(RepoPaths.VIDEOS, "/videos"),
    ;

    fun url(folder: String) = "$href?path=${enc(folder)}"

    /** Where a crumb of the path goes: this gallery's page for a folder in it, the Library above it. */
    fun crumb(folder: String) = if ("$folder/".startsWith(root)) url(folder) else "/browse?path=" + enc(folder)

    companion object {
        fun of(path: String): Gallery? = entries.firstOrNull { "$path/".startsWith(it.root) }
    }
}

/** One file in the grid: its version, the blob its thumbnail is made from, and a video's length. */
private class Tile(val version: Version, val blob: String?, val durationMs: Long?)

/**
 * A folder below the one shown: how many images, videos and other files it holds, the file its tile
 * shows, and [only], its one video when that's all it holds (a film), for the tile to open straight away.
 */
private class SubFolder(val path: String, val images: Int, val videos: Int, val others: Int, val cover: Tile?, val only: Tile?)

/** A file in the viewer, with its neighbours in its folder, where it stands among them, and the page to go back [up] to. */
private class Viewed(
    val version: Version,
    val blob: BlobRow?,
    val format: String?,
    val previous: Version?,
    val next: Version?,
    val index: Int,
    val count: Int,
    val up: String,
    val probe: Probe? = null,
)

private fun Repository.tile(v: Version): Tile {
    val media = v.renditions.firstOrNull()?.let { media(it) }
    return Tile(v, media?.baseBlob, media?.durationMs)
}

/**
 * The thumbnail of an image or video blob, made and kept the first time; null if Java can't decode
 * the image (HEIC, raw) or ffmpeg the video.
 */
fun Repository.thumbnail(hash: String): Path? {
    val file = dir.thumb(hash)
    if (Files.exists(file)) return file
    val mime = blob(hash)?.mime ?: return null
    if (mime.startsWith("video/")) return videoThumbnail(hash)
    if (!mime.startsWith("image/")) return null
    val bytes = Images.preview(dir.blob(hash), THUMB_SIZE) ?: return null
    Files.createDirectories(file.parent)
    val tmp = Files.createTempFile(file.parent, ".thumb", ".tmp")
    Files.write(tmp, bytes)
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    return file
}

internal fun viewerUrl(assetId: String) = "/view?id=$assetId"

private fun inPhotos(folder: String) = "$folder/".startsWith(RepoPaths.PHOTOS)

fun Route.imageRoutes(repo: Repository) {
    for (gallery in Gallery.entries) {
        get(gallery.href) {
            val folder = call.parameters["path"].orEmpty().trim('/').ifEmpty { gallery.root.trimEnd('/') }
            val page = call.parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val (files, folders) = io {
                repo.db.read {
                    val (here, below) = repo.liveUnder(folder).partition { RepoPaths.parent(it.path) == folder }
                    // Years newest first and Undated last, as images/photos holds; other folders by name.
                    val folders = below.groupBy { it.path.removePrefix("$folder/").substringBefore('/') }.map { (name, inside) ->
                        val images = inside.filter { it.kind == AssetKind.IMAGE }
                        val videos = inside.filter { it.kind == AssetKind.VIDEO }
                        val cover = images.maxByOrNull { it.path } ?: videos.minByOrNull { it.path }
                        val only = videos.singleOrNull()
                            ?.takeIf { gallery == Gallery.VIDEOS && images.isEmpty() && RepoPaths.parent(it.path) == "$folder/$name" }
                        SubFolder(
                            "$folder/$name", images.size, videos.size, inside.size - images.size - videos.size,
                            cover?.let { repo.tile(it) }, only?.let { repo.tile(it) },
                        )
                    }.sortedWith(
                        compareBy<SubFolder> { RepoPaths.name(it.path) == RepoPaths.UNDATED }
                            .thenBy { RepoPaths.name(it.path).toIntOrNull()?.let { year -> -year } ?: Int.MAX_VALUE }
                            .thenBy { RepoPaths.name(it.path).lowercase() },
                    )
                    here.map { repo.tile(it) } to folders
                }
            }
            if (files.isEmpty() && folders.isEmpty()) notFound("nothing in $folder")
            call.respondHtml { galleryPage(gallery, folder, files, folders, page, call.parameters["done"], call.parameters["error"]) }
        }
    }
    // Where Photos was before it took in the rest of images/.
    get("/photos") {
        call.respondRedirect("/images" + (call.request.queryString().takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""))
    }
    // Where the viewer was before it showed videos/ too.
    get("/images/view") {
        call.respondRedirect("/view" + (call.request.queryString().takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""))
    }
    get("/view") {
        val id = call.parameters["id"] ?: badRequest("which file?")
        val viewed = io {
            repo.db.read {
                val v = repo.headVersion(id)?.takeIf { !it.deleted } ?: notFound("no file $id")
                val folder = RepoPaths.parent(v.path)
                val gallery = Gallery.of(v.path)
                val inFolder = repo.liveIn(folder)
                // A video steps to the next video, past the subtitles and posters beside it.
                val siblings = if (gallery == Gallery.VIDEOS) inFolder.filter { it.kind == AssetKind.VIDEO || it.assetId == id } else inFolder
                val i = siblings.indexOfFirst { it.assetId == id }
                val media = v.renditions.firstOrNull()?.let { repo.media(it) }
                // A film's tile opens it straight from the folder of films, so that's where it goes back to.
                val film = gallery == Gallery.VIDEOS && siblings.size == 1 && inFolder.none { it.kind == AssetKind.IMAGE } &&
                    "$folder/" != gallery.root
                val up = when {
                    gallery == null -> "/browse?path=" + enc(folder)
                    film -> gallery.url(RepoPaths.parent(folder))
                    else -> gallery.url(folder)
                }
                Viewed(v, media?.let { repo.blob(it.baseBlob) }, media?.format, siblings.getOrNull(i - 1), siblings.getOrNull(i + 1), i + 1, siblings.size, up)
            }
        }
        val probe = viewed.blob?.takeIf { it.mime.startsWith("video/") }?.let { io { repo.probe(it.hash) } }
        call.respondHtml { viewerPage(viewed, probe) }
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

private fun HTML.galleryPage(
    gallery: Gallery,
    folder: String,
    files: List<Tile>,
    folders: List<SubFolder>,
    page: Int,
    done: String?,
    error: String?,
) = page(RepoPaths.name(folder)) {
    h1 { breadcrumbs(folder, gallery::crumb) }
    done?.let { p("notice") { +"$it." } }
    error?.let { p("notice error") { +it } }
    if (inPhotos(folder)) {
        p("muted") {
            +"Photos are filed as images/photos/{year}/{date} {name}, by the date the camera recorded or their name gives, "
            +"and images/photos/Undated/ without one. A date can be a year, a month or a day: 2019, 2019-07 or 2019-07-14."
        }
    }
    val grid = if (gallery == Gallery.VIDEOS) "photo-grid videos" else "photo-grid"
    if (folders.isNotEmpty()) div(grid) { folders.forEach { folderTile(gallery, it) } }
    // Videos' subtitles and posters go with them; the Library lists them.
    val (shown, beside) = if (gallery == Gallery.VIDEOS) files.partition { it.version.kind == AssetKind.VIDEO } else files to emptyList()
    if (shown.isNotEmpty()) {
        val pages = (shown.size + PAGE_SIZE - 1) / PAGE_SIZE
        val current = page.coerceAtMost(pages)
        val onPage = shown.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE)
        if (inPhotos(folder)) datingGrid(folder, onPage, current) else tiles(grid, onPage)
        if (pages > 1) {
            p("pages") {
                (1..pages).forEach { n ->
                    if (n == current) span("current") { +"$n" } else a(href = gallery.url(folder) + "&page=$n") { +"$n" }
                    +" "
                }
            }
        }
    }
    if (beside.isNotEmpty()) {
        p("muted small") {
            +"Also here: ${plural(beside.size, "other file", "other files")}, in "
            a(href = "/browse?path=" + enc(folder)) { +"the Library" }
            +"."
        }
    }
    script(src = "/static/previews.js") { }
}

private fun FlowContent.folderTile(gallery: Gallery, f: SubFolder) {
    val name = RepoPaths.name(f.path)
    a(href = f.only?.let { viewerUrl(it.version.assetId) } ?: gallery.url(f.path), classes = "photo folder") {
        f.cover?.let { preview(it, name) } ?: span("no-thumb") { +name }
        span("name") { +name }
        if (f.only == null) {
            span("date muted") {
                val noun = if (inPhotos(f.path)) "photo" else "image"
                +listOfNotNull(
                    f.images.takeIf { it > 0 || f.videos + f.others == 0 }?.let { plural(it, noun, noun + "s") },
                    f.videos.takeIf { it > 0 }?.let { plural(it, "video", "videos") },
                    f.others.takeIf { it > 0 }?.let { plural(it, "other file", "other files") },
                ).joinToString(" · ")
            }
        }
    }
}

/** A file's thumbnail; a video's moves through stills from across it while the pointer is on it (previews.js). */
private fun FlowContent.preview(tile: Tile, alt: String = RepoPaths.name(tile.version.path)) {
    when {
        tile.blob == null -> span("no-thumb") { +"No preview" }
        tile.version.kind == AssetKind.IMAGE -> img(alt = alt, src = "/web/thumbs/${tile.blob}") { attributes["loading"] = "lazy" }
        tile.version.kind == AssetKind.VIDEO -> span("thumb") {
            img(alt = alt, src = "/web/thumbs/${tile.blob}") {
                attributes["loading"] = "lazy"
                attributes["data-frames"] = "/web/frames/${tile.blob}"
            }
            span("length") { +(listOfNotNull("▶", tile.durationMs?.takeIf { it > 0 }?.let(::duration)).joinToString(" ")) }
        }
        else -> span("no-thumb") { +"No preview" }
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

/** Anything else: each opens in the viewer. */
private fun FlowContent.tiles(grid: String, shown: List<Tile>) {
    div(grid) {
        shown.forEach { tile ->
            a(href = viewerUrl(tile.version.assetId), classes = "photo folder") {
                preview(tile)
                span("name") { +RepoPaths.name(tile.version.path) }
            }
        }
    }
}

private fun HTML.viewerPage(viewed: Viewed, probe: Probe?) = page(RepoPaths.name(viewed.version.path)) {
    val v = viewed.version
    val name = RepoPaths.name(v.path)
    val folder = RepoPaths.parent(v.path)
    val blob = viewed.blob
    val file = name + (viewed.format?.let { ".$it" } ?: "")
    h1 {
        Gallery.of(v.path)?.let { breadcrumbs(folder, it::crumb) } ?: breadcrumbs(folder)
        +" / $name"
    }
    div("viewer-bar") {
        viewed.previous?.let { a(href = viewerUrl(it.assetId)) { attributes["rel"] = "prev"; +"← Previous" } } ?: span("muted") { +"← Previous" }
        span("muted num") { +"${viewed.index} of ${viewed.count}" }
        viewed.next?.let { a(href = viewerUrl(it.assetId)) { attributes["rel"] = "next"; +"Next →" } } ?: span("muted") { +"Next →" }
        span("spacer") { }
        a(href = viewed.up) { attributes["rel"] = "up"; +"Back to the folder" }
        a(href = "/assets/${v.assetId}") { +"Details" }
        if (blob != null && (blob.mime.startsWith("image/") || blob.mime.startsWith("video/"))) {
            a(href = "/web/blobs/${blob.hash}") { attributes["download"] = file; +"Download" }
        }
    }
    val doubts = probe?.doubts().orEmpty()
    if (doubts.isNotEmpty()) {
        p("notice warn") {
            id = "doubts"
            +"This video may not play fully here: ${doubts.joinToString("; ")}. Download it to watch it in a player such as VLC."
        }
    }
    p("notice error") {
        id = "unplayable"
        attributes["hidden"] = ""
        +"This browser can't play this video. Download it to watch it in a player such as VLC."
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
                if (probe != null) attributes["poster"] = "/web/thumbs/${blob.hash}"
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
            probe?.seconds?.takeIf { it > 0 }?.let { duration((it * 1000).toLong()) },
            probe?.width?.let { w -> probe.height?.let { h -> "$w×$h" } },
            probe?.video?.let { Probe.codecName(it) },
            probe?.audio?.distinct()?.joinToString(", ") { Probe.codecName(it) }?.ifEmpty { null },
            viewed.format?.uppercase(),
            blob?.let { bytes(it.size) },
        ).joinToString(" · ")
        when {
            blob?.mime in SHOWN -> +" · click the image to open it at full size; ← and → step through the folder"
            blob?.mime?.startsWith("video/") == true && viewed.count > 1 -> +" · ← and → step through the folder, unless the video has the focus"
        }
    }
    script(src = "/static/viewer.js") { }
}
