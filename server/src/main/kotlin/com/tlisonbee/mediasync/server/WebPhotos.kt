package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.PhotoDates
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
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
import kotlinx.html.span
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Longer side of a photo's thumbnail, in pixels: sharp at the grid's size on a high-density screen. */
private const val THUMB_SIZE = 320

/** Photos on one page of the grid. */
private const val PAGE_SIZE = 120

/** One photo in the grid: its version, and the blob its thumbnail is made from. */
private class Photo(val version: Version, val blob: String?)

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

fun Route.photoRoutes(repo: Repository) {
    get("/photos") {
        val folder = call.parameters["path"].orEmpty().trim('/').ifEmpty { RepoPaths.PHOTOS.trimEnd('/') }
        val page = call.parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val photos = io {
            repo.db.read {
                repo.liveIn(folder).filter { RepoPaths.isPhoto(it.kind, it.path) }.map { v ->
                    Photo(v, v.renditions.firstOrNull()?.let { repo.media(it)?.baseBlob })
                }
            }
        }.ifEmpty { notFound("no photos in $folder") }
        call.respondHtml { photosPage(folder, photos, page, call.parameters["done"], call.parameters["error"]) }
    }
    post("/photos/date") {
        val form = call.receiveParameters()
        val folder = form["folder"].orEmpty()
        val page = form["page"]?.toIntOrNull() ?: 1
        val back = "/photos?path=${enc(folder)}&page=$page"
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

private fun HTML.photosPage(folder: String, photos: List<Photo>, page: Int, done: String?, error: String?) = page(RepoPaths.name(folder)) {
    val pages = (photos.size + PAGE_SIZE - 1) / PAGE_SIZE
    val current = page.coerceAtMost(pages)
    val shown = photos.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE)
    h1 { breadcrumbs(folder) }
    done?.let { p("notice") { +"$it." } }
    error?.let { p("notice error") { +it } }
    p("muted") {
        +"Photos are filed as photos/{year}/{date} {name}, by the date the camera recorded or their name gives, "
        +"and photos/Undated/ without one. A date can be a year, a month or a day: 2019, 2019-07 or 2019-07-14."
    }
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
            shown.forEach { photo ->
                val v = photo.version
                val name = RepoPaths.name(v.path)
                label("photo") {
                    hiddenInput(name = "rev.${v.assetId}") { value = v.rev.toString() }
                    input(type = InputType.checkBox, name = "asset") {
                        value = v.assetId
                        attributes["aria-label"] = name
                    }
                    if (v.kind == AssetKind.IMAGE && photo.blob != null) {
                        img(alt = name, src = "/web/thumbs/${photo.blob}") { attributes["loading"] = "lazy" }
                    } else {
                        span("no-thumb") { +(if (v.kind == AssetKind.VIDEO) "Video" else "No preview") }
                    }
                    span("name") { +name }
                    span("date muted") { +(v.tags[TagField.DATE]?.firstOrNull() ?: "No date") }
                }
            }
        }
    }
    if (pages > 1) {
        p("pages") {
            (1..pages).forEach { n ->
                if (n == current) span("current") { +"$n" } else a(href = "/photos?path=${enc(folder)}&page=$n") { +"$n" }
                +" "
            }
        }
    }
}
