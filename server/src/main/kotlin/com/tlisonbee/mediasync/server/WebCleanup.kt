package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.http.Parameters
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
import kotlinx.html.h3
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.img
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
        val leftovers = io { repo.leftovers() }
        val options = artOptions(call.parameters)
        val art = io { repo.artPlans(options) }
        val incomplete = io { repo.incompleteAlbums() }
        call.respondHtml {
            cleanupPage(variants, strays, leftovers, repo.settings.deleteClutter, options, art, incomplete, call.parameters["done"]?.toLongOrNull())
        }
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
    post("/cleanup/leftovers") {
        val formats = call.receiveParameters().getAll("format").orEmpty().toSet()
        val rev = io { repo.deleteLeftovers(repo.webDeviceId(), formats) }
        call.respondRedirect(rev?.let { "/cleanup?done=$it" } ?: "/cleanup")
    }
    post("/cleanup/clutter") {
        val on = call.receiveParameters()["on"] == "true"
        io { repo.updateSettings(repo.settings.copy(deleteClutter = on)) }
        call.respondRedirect("/cleanup#leftovers")
    }
    post("/cleanup/art") {
        val options = artOptions(call.receiveParameters())
        val rev = io { repo.tidyAlbumArt(repo.webDeviceId(), options) }
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

/** The album art form's choices; the page opens with copies removed and names and formats left alone. */
private fun artOptions(params: Parameters) = ArtOptions(
    dedupe = params["art"] == null || params["dedupe"] != null,
    coverName = params["name"]?.takeIf { it in COVER_NAMES },
    png = params["png"] != null,
)

private fun HTML.cleanupPage(
    variants: List<NameVariants>,
    strays: StrayPlan,
    leftovers: List<Leftovers>,
    deleteClutter: Boolean,
    artOptions: ArtOptions,
    art: List<ArtPlan>,
    incomplete: List<Pair<String, Int>>,
    done: Long?,
) = page("Cleanup") {
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

    leftoverSection(leftovers, deleteClutter)
    albumArtSection(artOptions, art)

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

private fun FlowContent.leftoverSection(leftovers: List<Leftovers>, deleteClutter: Boolean) {
    h2 { id = "leftovers"; +"Leftover files" }
    p("muted") {
        +"Files in the music folder that aren't tracks or pictures. The ticked kinds are ones nothing uses; "
        +"the others may be worth keeping (a .cue sheet, a rip log, a booklet). Deleting is one revision, which History can revert."
    }
    if (leftovers.isEmpty()) {
        p("empty") { +"Nothing but tracks and pictures." }
    } else {
        form(action = "/cleanup/leftovers", method = FormMethod.post) {
            table("leftovers") {
                thead { tr { th { }; th { +"Kind" }; th { +"Files" } } }
                tbody {
                    leftovers.forEach { kind ->
                        tr {
                            td {
                                input(type = InputType.checkBox, name = "format") {
                                    value = kind.format
                                    checked = kind.junk
                                    attributes["aria-label"] = ".${kind.format} files"
                                }
                            }
                            td { +".${kind.format}" }
                            td {
                                details {
                                    summary { +"${kind.paths.size} ${if (kind.paths.size == 1) "file" else "files"}" }
                                    ul("list") { kind.paths.take(200).forEach { li { +it.removePrefix(RepoPaths.MUSIC) } } }
                                    if (kind.paths.size > 200) p("muted") { +"And ${kind.paths.size - 200} more." }
                                }
                            }
                        }
                    }
                }
            }
            div("actions") { button(classes = "primary") { +"Delete the ticked kinds" } }
        }
    }

    h3 { +"On devices" }
    p("muted") {
        +"Devices never sync desktop.ini, Thumbs.db, Windows Media Player’s AlbumArtSmall.jpg and AlbumArt_{…}.jpg, "
        +".DS_Store or macOS’s ._ files, so they aren’t in the library and the list above can’t show them. "
        +"With this on, every device deletes the ones in its synced folders each time it looks for changes "
        +"(their apps need updating first). Those files can’t be brought back, but nothing uses them."
    }
    form(action = "/cleanup/clutter", method = FormMethod.post, classes = "actions") {
        hiddenInput(name = "on") { value = (!deleteClutter).toString() }
        if (deleteClutter) {
            span { +"Devices delete them. " }
            button { +"Stop deleting them" }
        } else {
            button(classes = "primary") { +"Delete them on every device" }
        }
    }
}

private fun FlowContent.albumArtSection(options: ArtOptions, plans: List<ArtPlan>) {
    h2 { id = "art"; +"Album art" }
    p("muted") {
        +"Pictures directly in each album’s folder, compared by what they show: copies of one picture under other names "
        +"or at smaller sizes come down to the one with the most pixels. The front cover is the picture the tracks carry, "
        +"else the one named folder, cover or front, else the only one."
    }
    form(action = "/cleanup/art", method = FormMethod.post, classes = "card art-options") {
        hiddenInput(name = "art") { value = "1" }
        label("check") {
            input(type = InputType.checkBox, name = "dedupe") { checked = options.dedupe }
            +"Keep only the best copy of each picture"
        }
        p { +"Name the front cover" }
        div("choices") {
            (listOf(null) + COVER_NAMES).forEach { name ->
                label("pick") {
                    input(type = InputType.radio, name = "name") {
                        value = name.orEmpty()
                        checked = options.coverName == name
                    }
                    +(name?.let { "$it.jpg or $it.png" } ?: "Leave the names")
                }
            }
        }
        label("check") {
            input(type = InputType.checkBox, name = "png") { checked = options.png }
            +"Convert JPEG pictures to PNG"
        }
        p("muted") { +"PNG is lossless, but a JPEG converted to it looks no better than before and takes several times the space." }
        div("actions") {
            button {
                attributes["formaction"] = "/cleanup#art"
                attributes["formmethod"] = "get"
                +"Preview"
            }
            button(classes = "primary") { +"Apply" }
        }
    }

    val changing = plans.filter { it.changes }
    val deletes = changing.sumOf { it.deleted.size }
    val renames = changing.sumOf { p -> p.kept.count { it.path != it.slot.version.path } }
    val converts = changing.sumOf { p -> p.kept.count { it.png } }
    if (changing.isEmpty()) {
        p("empty") { +"With these choices, every album’s art is already tidy." }
    } else {
        p { +"${changing.size} ${if (changing.size == 1) "album changes" else "albums change"}: $deletes deleted, $renames renamed, $converts converted." }
        details {
            summary { +"Show them" }
            table("art") {
                thead { tr { th { +"Album" }; th { +"Keeps" }; th { +"Deletes" } } }
                tbody {
                    changing.take(300).forEach { plan ->
                        tr {
                            td { a(href = "/browse?path=" + enc(plan.album.folder)) { +plan.album.folder.removePrefix(RepoPaths.MUSIC) } }
                            td {
                                plan.kept.filter { it.changes || it.cover }.forEach { kept ->
                                    div("art-file") {
                                        artThumb(kept.best)
                                        span {
                                            +artLabel(kept.best)
                                            val name = RepoPaths.name(kept.path) + "." + (if (kept.png) "png" else kept.best.format)
                                            if (name != kept.best.name) +" → $name"
                                            if (kept.cover) span("muted") { +" front cover" }
                                        }
                                    }
                                }
                            }
                            td {
                                // A best copy that hands its image to the file at the cover's name shows under
                                // Keeps; the image that file had is what goes.
                                plan.deleted.filter { d -> plan.kept.none { it.best === d } }.forEach { art ->
                                    div("art-file") { artThumb(art); span { +artLabel(art) } }
                                }
                                plan.kept.filter { it.slot !== it.best }.forEach { kept ->
                                    div("art-file") { artThumb(kept.slot); span { +artLabel(kept.slot); span("muted") { +" replaced" } } }
                                }
                            }
                        }
                    }
                }
            }
            if (changing.size > 300) p("muted") { +"And ${changing.size - 300} more." }
        }
    }
    val unclear = plans.filter { it.album.cover == null && it.album.groups.size > 1 }
    if (options.coverName != null && unclear.isNotEmpty()) {
        details {
            summary { +"${unclear.size} albums whose front cover isn’t clear keep their pictures’ names" }
            ul("list") { unclear.take(200).forEach { li { a(href = "/browse?path=" + enc(it.album.folder)) { +it.album.folder.removePrefix(RepoPaths.MUSIC) } } } }
        }
    }
}

private fun FlowContent.artThumb(art: Art) = img(src = "/web/blobs/${art.blob}", alt = "") {
    attributes["loading"] = "lazy"
    width = "48"
    height = "48"
}

private fun artLabel(art: Art) = "${art.name} (${art.info?.let { "${it.width}×${it.height}" } ?: "unreadable"}, ${bytes(art.size)})"

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
