package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

/** Fields the album editor sets once for every track. */
private val ALBUM_FIELDS = listOf(TagField.ALBUM_ARTIST, TagField.ALBUM, TagField.DATE, TagField.GENRE)

/** Fields it shows per track. */
private val TRACK_FIELDS = listOf(TagField.DISC_NUMBER, TagField.TRACK_NUMBER, TagField.TITLE, TagField.ARTIST)

private val LABELS = mapOf(
    TagField.ALBUM_ARTIST to "Album artist", TagField.ALBUM to "Album", TagField.DATE to "Year",
    TagField.GENRE to "Genre", TagField.DISC_NUMBER to "Disc", TagField.TRACK_NUMBER to "#",
    TagField.TITLE to "Title", TagField.ARTIST to "Artist",
)

fun Route.cleanupRoutes(repo: Repository) {
    get("/cleanup") {
        val variants = io { repo.nameVariants() }
        val strays = io { repo.strayPlan() }
        val incomplete = io { repo.incompleteAlbums() }
        call.respondHtml { cleanupPage(variants, strays, incomplete, call.parameters["done"]?.toLongOrNull()) }
    }
    post("/cleanup/rename") {
        val form = call.receiveParameters()
        val to = form["custom"]?.takeIf { it.isNotBlank() } ?: form["to"] ?: badRequest("pick a spelling")
        val from = form.getAll("from").orEmpty().toSet()
        val rev = io { repo.rename(repo.webDeviceId(), form["field"].orEmpty(), from, to, form["artistKey"]?.takeIf { it.isNotEmpty() }) }
        call.respondRedirect(rev?.let { "/cleanup?done=$it" } ?: "/cleanup")
    }
    post("/cleanup/strays") {
        val rev = io { repo.tidyStrays(repo.webDeviceId()) }
        call.respondRedirect(rev?.let { "/cleanup?done=$it" } ?: "/cleanup")
    }

    get("/album") {
        val folder = call.parameters["path"].orEmpty().trim('/')
        val tracks = io { albumTracks(repo, folder) }
        call.respondHtml { albumPage(folder, tracks) }
    }
    post("/album") {
        val form = call.receiveParameters()
        val folder = form["folder"].orEmpty()
        val revs = form.names().filter { it.startsWith("rev.") }
            .associate { it.removePrefix("rev.") to (form[it]?.toLongOrNull() ?: badRequest("bad revision")) }
        val tracks = io { revs.keys.map { repo.headVersion(it) ?: notFound("no asset $it") } }
        val edits = tracks.associate { track ->
            val changes = mutableMapOf<String, List<String>>()
            for (field in ALBUM_FIELDS) {
                val value = form["all.$field"] ?: continue
                val shown = common(tracks, field)
                // A field with several values shows empty: leaving it so keeps them.
                if (value.trim() == (shown ?: "").trim() || (shown == null && value.isBlank())) continue
                changes[field] = parse(value)
            }
            for (field in TRACK_FIELDS) {
                val value = form["${track.assetId}.$field"] ?: continue
                if (value.trim() != shown(track.tags[field])) changes[field] = parse(value)
            }
            track.assetId to changes
        }
        val album = form["all.${TagField.ALBUM}"]?.takeIf { it.isNotBlank() } ?: RepoPaths.name(folder)
        io { repo.editTags(repo.webDeviceId(), "Edited album “${album.trim()}”", edits, revs) }
        // Renaming the album or its artist moves the folder.
        val moved = tracks.firstOrNull()?.let { io { repo.headVersion(it.assetId) } }?.path?.let(RepoPaths::parent) ?: folder
        call.respondRedirect("/browse?path=" + enc(moved))
    }
}

private fun albumTracks(repo: Repository, folder: String): List<Version> =
    repo.db.read { repo.liveIn(folder) }.filter { it.kind == AssetKind.AUDIO }
        .ifEmpty { notFound("no tracks in $folder") }

/** The value every track shares, or null if they differ. */
private fun common(tracks: List<Version>, field: String): String? =
    tracks.map { shown(it.tags[field]) }.distinct().singleOrNull()

private fun shown(values: List<String>?) = values.orEmpty().joinToString("; ")

/** "A; B" is two values, as the pages show them. */
private fun parse(text: String) = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }

private fun HTML.cleanupPage(variants: List<NameVariants>, strays: StrayPlan, incomplete: List<Pair<String, Int>>, done: Long?) = page("Cleanup") {
    h1 { +"Cleanup" }
    if (done != null) {
        p("notice") {
            +"Saved as "
            a(href = "/revisions/$done") { +"r$done" }
            +". Open it to see every change, or revert it there."
        }
    }

    h2 { +"Names spelled more than one way" }
    p("muted") {
        +"These differ only in case, accents, punctuation, a leading “The” or “&” for “and”. Pick the right spelling, "
        +"or type another, and every track using the others is retagged. Tracks move to the matching folder."
    }
    if (variants.isEmpty()) p("empty") { +"Every artist and album is spelled one way." }
    variants.forEach { group -> variantForm(group) }

    h2 { +"Files left in old folders" }
    p("muted") {
        +"Cover scans and other files that aren't tracks keep the folder they came from, while tracks follow their tags. "
        +"These ${strays.moves.size} files can go to where their album's tracks are now."
    }
    if (strays.moves.isEmpty()) {
        p("empty") { +"Nothing to move." }
    } else {
        form(action = "/cleanup/strays", method = FormMethod.post) {
            button(classes = "primary") { +"Move ${strays.moves.size} files" }
        }
        details {
            summary { +"Show them" }
            table("moves") {
                thead { tr { th { +"From" }; th { +"To" } } }
                tbody {
                    strays.moves.take(500).forEach { m ->
                        tr {
                            td { +m.from.removePrefix(RepoPaths.MUSIC) }
                            td { +m.to.removePrefix(RepoPaths.MUSIC) }
                        }
                    }
                }
            }
            if (strays.moves.size > 500) p("muted") { +"And ${strays.moves.size - 500} more." }
        }
    }
    if (strays.unplaced.isNotEmpty()) {
        details {
            summary { +"${strays.unplaced.size} files whose tracks went to several albums stay where they are" }
            ul("list") { strays.unplaced.take(200).forEach { li { +it.removePrefix(RepoPaths.MUSIC) } } }
        }
    }

    h2 { +"Albums with missing tags" }
    p("muted") { +"Tracks without an artist, album or title. Open an album to fill them in." }
    if (incomplete.isEmpty()) p("empty") { +"Every track has an artist, album and title." }
    ul("list") {
        incomplete.forEach { (folder, count) ->
            li {
                a(href = "/album?path=" + enc(folder)) { +folder.removePrefix(RepoPaths.MUSIC) }
                span("muted") { +" $count ${if (count == 1) "track" else "tracks"}" }
            }
        }
    }
}

private fun FlowContent.variantForm(group: NameVariants) = form(action = "/cleanup/rename", method = FormMethod.post, classes = "card variant") {
    hiddenInput(name = "field") { value = group.field }
    group.artistKey?.let { key -> hiddenInput(name = "artistKey") { value = key } }
    p("muted") { +(if (group.field == TagField.ALBUM) "Album by ${group.artist}" else "Artist") }
    group.spellings.forEachIndexed { i, s ->
        hiddenInput(name = "from") { value = s.value }
        label("pick") {
            input(type = InputType.radio, name = "to") {
                value = s.value
                checked = i == 0
            }
            span("name") { +s.value }
            span("muted") { +" ${s.tracks} ${if (s.tracks == 1) "track" else "tracks"}" }
        }
    }
    div("actions") {
        input(type = InputType.text, name = "custom") {
            placeholder = "Or type another spelling"
            attributes["aria-label"] = "Another spelling"
        }
        button(classes = "primary") { +"Use this spelling" }
    }
}

/** On an artist's folder: rename the artist everywhere. */
internal fun FlowContent.renameArtistForm(name: String) = form(action = "/cleanup/rename", method = FormMethod.post, classes = "card") {
    hiddenInput(name = "field") { value = TagField.ARTIST }
    hiddenInput(name = "from") { value = name }
    hiddenInput(name = "to") { value = name }
    label {
        +"Rename “$name” on every track"
        input(type = InputType.text, name = "custom") { value = name }
    }
    div("actions") { button { +"Rename" } }
}

private fun HTML.albumPage(folder: String, tracks: List<Version>) = page(RepoPaths.name(folder)) {
    h1 { breadcrumbs(folder) }
    form(action = "/album", method = FormMethod.post, classes = "card album") {
        hiddenInput(name = "folder") { value = folder }
        h2 { +"Whole album" }
        div("album-fields") {
            ALBUM_FIELDS.forEach { field ->
                val shared = common(tracks, field)
                label {
                    +LABELS.getValue(field)
                    input(type = InputType.text, name = "all.$field") {
                        value = shared.orEmpty()
                        if (shared == null) placeholder = "Several values; leave empty to keep them"
                    }
                }
            }
        }
        h2 { +"Tracks" }
        table("tracks") {
            thead { tr { TRACK_FIELDS.forEach { th(classes = "f-$it") { +LABELS.getValue(it) } } } }
            tbody {
                tracks.sortedWith(compareBy({ number(it, TagField.DISC_NUMBER) }, { number(it, TagField.TRACK_NUMBER) }, { it.path })).forEach { t ->
                    tr {
                        TRACK_FIELDS.forEachIndexed { i, field ->
                            td(classes = "f-$field") {
                                if (i == 0) hiddenInput(name = "rev.${t.assetId}") { value = t.rev.toString() }
                                input(type = InputType.text, name = "${t.assetId}.$field") {
                                    value = shown(t.tags[field])
                                    attributes["aria-label"] = "${LABELS.getValue(field)}, ${RepoPaths.name(t.path)}"
                                }
                            }
                        }
                    }
                }
            }
        }
        p("muted") { +"Separate several values with “;”. Saving makes one revision, which History can revert." }
        div("actions") { button(classes = "primary") { +"Save" } }
    }
}

private fun number(v: Version, field: String) = v.tags[field]?.firstOrNull()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
