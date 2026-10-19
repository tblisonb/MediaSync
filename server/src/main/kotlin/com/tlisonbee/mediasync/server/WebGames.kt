package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.Fields
import com.tlisonbee.mediasync.library.Platforms
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
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
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.progress
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Fields the game editor sets once for every file of a game. */
private val GAME_FIELDS = listOf(TagField.TITLE, TagField.REGION, TagField.YEAR, TagField.PUBLISHER, TagField.DEVELOPER, TagField.GENRE, TagField.PLAYERS)

private val GAME_LABELS = mapOf(
    TagField.TITLE to "Title", TagField.REGION to "Region", TagField.YEAR to "Year", TagField.PUBLISHER to "Publisher",
    TagField.DEVELOPER to "Developer", TagField.GENRE to "Genre", TagField.PLAYERS to "Players",
)

private val HOW_LABELS = mapOf(
    How.CHECKSUM to "matched by checksum", How.SERIAL to "matched by the disc's serial", How.SIBLING to "from the file it goes with",
    How.NAME to "by its name", How.UNKNOWN to "not found",
)

/** One game: the files in its folder, the one whose details stand for them, and their sizes. */
class Game(val folder: String, val files: List<Version>, val sizes: Map<String, Long>) {
    val size get() = sizes.values.sum()
    val main: Version = files.firstOrNull { it.tags[TagField.RELEASE] != null } ?: files.firstOrNull { it.tags[TagField.TITLE] != null } ?: files.first()
    val label get() = gameLabel(main)
    val platform get() = Platforms.byId(main.category)
}

/** The games under [folder], each a game folder, or the folder an unfiled file is in. */
fun Repository.games(folder: String = RepoPaths.GAMES.trimEnd('/')): List<Game> {
    val files = liveGames(folder)
    return files.groupBy { RepoPaths.gameFolder(it.path) ?: RepoPaths.parent(it.path) }.map { (f, inGame) ->
        Game(f, inGame.sortedBy { it.path }, inGame.associate { v -> v.assetId to (v.renditions.firstOrNull()?.let { media(it)?.size } ?: 0L) })
    }.sortedBy { it.label.lowercase() }
}

/** Devices with a pick rule for games, whether each keeps [game], and those that keep every game. */
private fun Repository.gameDevices(path: String): List<Pair<Device, Boolean>> =
    devices().filter { it.revokedAt == null && it.kind != DeviceKind.WEB }.mapNotNull { d ->
        val rules = d.subscription.rules.filter { it.covers(path, AssetKind.GAME) }
        if (rules.isEmpty()) null else d to rules.all { it.pick }
    }

fun Route.gameRoutes(repo: Repository) {
    get("/games") {
        val games = io { repo.games() }
        val idents = io { repo.gameIdents() }
        val status = io { repo.gameDbStatus() }
        call.respondHtml { gamesPage(games, idents, status, repo.gameJob.get()) }
    }
    get("/games/platform") {
        val folder = call.parameters["folder"].orEmpty().trim('/')
        val games = io { repo.games(folder) }.ifEmpty { notFound("no games in $folder") }
        val idents = io { repo.gameIdents() }
        val devices = io { repo.gameDevices("$folder/x").filter { it.second }.map { (d, _) -> d to (folder in repo.folderPicks(d.id)) } }
        call.respondHtml { platformPage(folder, games, idents, devices) }
    }
    get("/games/game") {
        val folder = call.parameters["folder"].orEmpty().trim('/')
        val game = io { repo.games(folder).firstOrNull { it.folder == folder } } ?: notFound("no game in $folder")
        val idents = io { repo.gameIdents() }
        val query = call.parameters["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val platform = call.parameters["platform"] ?: game.main.category
        val results = if (query != null && platform != null) io { GameIndex.open(repo.gameDb)?.use { it.search(platform, query) } } else null
        val devices = io {
            val ids = game.files.map { it.assetId }
            repo.gameDevices(game.main.path).map { (d, picks) ->
                val keeps = when {
                    !picks -> Keeps.EVERYTHING
                    repo.folderPicks(d.id).any { game.folder.startsWith("$it/") } -> Keeps.PLATFORM
                    repo.picks(d.id).any { it in ids } -> Keeps.PICKED
                    else -> Keeps.NOT_PICKED
                }
                d to keeps
            }
        }
        val dbReady = io { repo.gameDbStatus() }.ready
        call.respondHtml { gamePage(game, idents, query, platform, results, devices, dbReady) }
    }
    post("/games/game") {
        val form = call.receiveParameters()
        val folder = form["folder"].orEmpty()
        val game = io { repo.games(folder).firstOrNull { it.folder == folder } } ?: notFound("no game in $folder")
        val revs = game.files.associate { it.assetId to (form["rev.${it.assetId}"]?.toLongOrNull() ?: badRequest("reload the page")) }
        val platform = form["platform"]?.takeIf { it.isNotEmpty() }
        val edits = game.files.associate { file ->
            val changes = mutableMapOf<String, List<String>>()
            if (platform != null && platform != file.category) changes[Fields.CATEGORY] = listOf(platform)
            for (field in GAME_FIELDS) {
                val value = form[field]?.trim() ?: continue
                val shown = game.main.tags[field].orEmpty().joinToString("; ")
                if (value != shown) changes[field] = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            }
            file.assetId to changes
        }
        io { repo.editTags(repo.webDeviceId(), "Edited ${game.label}", edits, revs) }
        call.respondRedirect(io { repo.gameUrl(game.files.first().assetId) })
    }
    // Sets a game's details to a game chosen from a database search.
    post("/games/game/use") {
        val form = call.receiveParameters()
        val folder = form["folder"].orEmpty()
        val game = io { repo.games(folder).firstOrNull { it.folder == folder } } ?: notFound("no game in $folder")
        val meta = io { GameIndex.open(repo.gameDb)?.use { it.meta(form["platform"].orEmpty(), form["title"].orEmpty()) } } ?: badRequest("that game isn't in the database")
        io { repo.applyGame(repo.webDeviceId(), game.files, meta) }
        call.respondRedirect(io { repo.gameUrl(game.files.first().assetId) })
    }
    post("/games/identify") {
        val form = call.receiveParameters()
        val folder = form["folder"]
        val ids = io {
            when {
                folder != null -> repo.games(folder).firstOrNull { it.folder == folder }?.files?.map { it.assetId }.orEmpty()
                // The files nothing was found for: worth another try with newer databases.
                else -> repo.gameIdents().filterValues { it == How.UNKNOWN || it == How.NAME }.keys
            }
        }
        io { repo.startIdentifying(repo.webDeviceId(), ids) }
        call.respondRedirect(if (folder != null) "/games/game?folder=" + enc(folder) else "/games")
    }
    get("/games/identify/progress") {
        val job = repo.gameJob.get()
        call.respond(job?.let { ConversionProgress(it.finished, it.done, it.total, gameProgressText(it)) } ?: ConversionProgress(true, 0, 0, ""))
    }
    post("/games/db/update") {
        io { repo.requestGameDbUpdate() }
        call.respondRedirect("/games")
    }
    post("/games/picks") {
        val form = call.receiveParameters()
        val folder = form["folder"].orEmpty()
        val back = form["back"]?.takeIf { it.startsWith("/games") } ?: "/games"
        for (device in io { repo.gameDevices("$folder/x").filter { it.second }.map { it.first } }) {
            val keep = form["keep.${device.id}"] != null
            io { if (keep) repo.setFolderPicks(device.id, listOf(folder), emptyList()) else repo.setFolderPicks(device.id, emptyList(), listOf(folder)) }
        }
        call.respondRedirect(back)
    }
}

/** Where a game file's game page is, wherever identification has filed it since. */
fun Repository.gameUrl(assetId: String): String {
    val v = headVersion(assetId) ?: return "/games"
    return "/games/game?folder=" + enc(RepoPaths.gameFolder(v.path) ?: RepoPaths.parent(v.path))
}

/** Where the game databases stand. */
class GameDbStatus(val builtAt: Long?, val dumps: Long, val games: Long, val requested: Boolean, val error: String?) {
    val ready get() = builtAt != null
}

fun Repository.gameDbStatus(): GameDbStatus {
    val index = GameIndex.open(gameDb)
    val error = gameDb.error.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it).trim() }
    return index?.use { GameDbStatus(it.builtAt, it.dumps, it.games, Files.exists(gameDb.request), error) }
        ?: GameDbStatus(null, 0, 0, Files.exists(gameDb.request), error)
}

/** "12 of 40 files checked (30%)", and the same once it's saving or done. */
internal fun gameProgressText(job: GameJob): String {
    val failed = job.failed.size.takeIf { it > 0 }?.let { ", $it couldn’t be read" }.orEmpty()
    val percent = if (job.total == 0) 100 else job.done * 100 / job.total
    return when {
        job.finished -> "Finished: ${job.matched} of ${job.total} files found in the databases$failed."
        job.done == job.total -> "All ${job.total} files checked$failed. Saving what was found…"
        else -> "${job.done} of ${job.total} files checked ($percent%)$failed. Large disc images take a few seconds each."
    }
}

private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun FlowContent.identification(job: GameJob?) {
    if (job == null || job.finished) return
    section("notice conversion") {
        attributes["data-progress"] = "/games/identify/progress"
        p { strong { +"Identifying games." } }
        div("progress") {
            progress {
                attributes["value"] = job.done.toString()
                attributes["max"] = job.total.coerceAtLeast(1).toString()
                attributes["aria-label"] = "Game files checked"
            }
            p("progress-text") { +gameProgressText(job) }
        }
    }
    script(src = "/static/progress.js") { }
}

private fun FlowContent.databases(status: GameDbStatus) {
    section("card") {
        h2 { +"Game databases" }
        when {
            status.requested -> p { +"Downloading the databases now; it takes a minute or two. Reload this page to see when they're in." }
            status.ready -> p {
                +"From ${DAY.format(Instant.ofEpochMilli(status.builtAt!!).atZone(ZoneId.systemDefault()))}: "
                +"%,d known dumps, %,d games. They update every week.".format(status.dumps, status.games)
            }
            else -> p { +"Not downloaded yet. Games are identified once they are." }
        }
        status.error?.let { p("error") { +"The last download failed: $it" } }
        p("muted") {
            +"Dumps are matched against the No-Intro and Redump lists from the libretro database (the ones RetroArch uses); "
            +"the year, publisher, developer and genre come from the LaunchBox games database. Downloading them is the only thing "
            +"MediaSync does on the internet, and it sends nothing about your library."
        }
        if (!status.requested) {
            form(action = "/games/db/update", method = FormMethod.post) { button { +(if (status.ready) "Update now" else "Download now") } }
        }
    }
}

private fun HTML.gamesPage(games: List<Game>, idents: Map<String, String>, status: GameDbStatus, job: GameJob?) = page("Games") {
    h1 { +"Games" }
    identification(job)
    if (games.isEmpty()) {
        p("empty") {
            +"No games yet. Sync a folder of disc images or ROMs with games/ (on a PC: mediasync-desktop add-folder games/ ~/Games), "
            +"and they're identified as they arrive."
        }
    }
    val byPlatform = games.groupBy { it.platform }
    table {
        if (byPlatform.isNotEmpty()) thead { tr { th { +"Platform" }; th { +"Games" }; th { +"Size" } } }
        tbody {
            byPlatform.filterKeys { it != null }.toSortedMap(compareBy { it!!.name }).forEach { (platform, inPlatform) ->
                tr {
                    td { a(href = "/games/platform?folder=" + enc("games/${RepoPaths.segment(platform!!.name)}")) { +platform.name } }
                    td { +inPlatform.size.toString() }
                    td { +bytes(inPlatform.sumOf { it.size }) }
                }
            }
        }
    }
    byPlatform[null]?.let { unknown ->
        h2 { +"Platform unknown" }
        p("muted") { +"Open one to choose its platform; then it's filed and looked up." }
        table {
            tbody {
                unknown.forEach { g ->
                    tr {
                        td { a(href = "/games/game?folder=" + enc(g.folder)) { +g.label } }
                        td("muted") { +g.folder }
                        td { +bytes(g.size) }
                    }
                }
            }
        }
    }
    val notFound = idents.values.count { it == How.UNKNOWN || it == How.NAME }
    if (notFound > 0 && status.ready && (job == null || job.finished)) {
        form(action = "/games/identify", method = FormMethod.post, classes = "actions") {
            span("muted") { +"$notFound ${if (notFound == 1) "file wasn't" else "files weren't"} found in the databases by checksum or serial. " }
            button { +"Try them again" }
        }
    }
    databases(status)
}

private fun HTML.platformPage(folder: String, games: List<Game>, idents: Map<String, String>, devices: List<Pair<Device, Boolean>>) =
    page(RepoPaths.name(folder)) {
        h1 { a(href = "/games") { +"Games" }; +" / ${RepoPaths.name(folder)}" }
        table {
            thead { tr { th { +"Game" }; th { +"Year" }; th { +"Publisher" }; th { +"Genre" }; th { +"Files" }; th { +"Size" } } }
            tbody {
                games.forEach { g ->
                    tr {
                        td {
                            a(href = "/games/game?folder=" + enc(g.folder)) { +g.label }
                            if (g.files.none { idents[it.assetId] in How.MATCHED }) span("muted") { +" · not matched" }
                        }
                        td { +g.main.tags[TagField.YEAR].orEmpty().joinToString() }
                        td { +g.main.tags[TagField.PUBLISHER].orEmpty().joinToString() }
                        td { +g.main.tags[TagField.GENRE].orEmpty().joinToString(", ") }
                        td { +g.files.size.toString() }
                        td { +bytes(g.size) }
                    }
                }
            }
        }
        if (devices.isNotEmpty()) {
            h2 { +"On devices that pick" }
            form(action = "/games/picks", method = FormMethod.post) {
                hiddenInput(name = "folder") { value = folder }
                hiddenInput(name = "back") { value = "/games/platform?folder=" + enc(folder) }
                devices.forEach { (d, whole) ->
                    label("check") {
                        input(type = InputType.checkBox, name = "keep.${d.id}") { checked = whole }
                        +"Keep every ${RepoPaths.name(folder)} game on ${d.name}, including ones added later"
                    }
                }
                div("actions") { button { +"Save" } }
            }
        }
    }

private fun HTML.gamePage(
    game: Game,
    idents: Map<String, String>,
    query: String?,
    platform: String?,
    results: List<GameMeta>?,
    devices: List<Pair<Device, Keeps>>,
    dbReady: Boolean,
) = page(game.label) {
    h1 {
        a(href = "/games") { +"Games" }
        game.platform?.let { p -> +" / "; a(href = "/games/platform?folder=" + enc(RepoPaths.parent(game.folder))) { +p.name } }
        +" / ${game.label}"
    }
    form(action = "/games/game", method = FormMethod.post, classes = "card") {
        hiddenInput(name = "folder") { value = game.folder }
        game.files.forEach { f -> hiddenInput(name = "rev.${f.assetId}") { value = f.rev.toString() } }
        div("album-fields") {
            label {
                +"Platform"
                select {
                    name = "platform"
                    option { value = ""; selected = game.platform == null; +"Unknown" }
                    Platforms.ALL.sortedBy { it.name }.forEach { p -> option { value = p.id; selected = p == game.platform; +p.name } }
                }
            }
            GAME_FIELDS.forEach { field ->
                label {
                    +GAME_LABELS.getValue(field)
                    input(type = InputType.text, name = field) { value = game.main.tags[field].orEmpty().joinToString("; ") }
                }
            }
        }
        p("muted") { +"These apply to every file of the game; it's filed as games/{platform}/{title} ({region})/. Separate several values with “;”." }
        div("actions") { button(classes = "primary") { +"Save" } }
    }

    h2 { +"Files" }
    table {
        thead { tr { th { +"File" }; th { +"Disc" }; th { +"Serial" }; th { +"Found" }; th { +"Size" } } }
        tbody {
            game.files.forEach { f ->
                tr {
                    td {
                        a(href = "/assets/${f.assetId}") { +RepoPaths.name(f.path) }
                        f.tags[TagField.RELEASE]?.firstOrNull()?.let { span("muted") { +" · $it" } }
                    }
                    td { +f.tags[TagField.DISC_NUMBER].orEmpty().joinToString() }
                    td { +f.tags[TagField.SERIAL].orEmpty().joinToString() }
                    td { +(idents[f.assetId]?.let { HOW_LABELS[it] } ?: "not checked yet") }
                    td { +bytes(game.sizes[f.assetId] ?: 0) }
                }
            }
        }
    }
    form(action = "/games/identify", method = FormMethod.post, classes = "actions") {
        hiddenInput(name = "folder") { value = game.folder }
        button { +"Identify again" }
    }

    h2 { +"Find it in the database" }
    if (!dbReady) {
        p("muted") { +"The game databases haven't been downloaded yet. "; a(href = "/games") { +"Games" } }
    } else {
        form(action = "/games/game", method = FormMethod.get, classes = "actions") {
            hiddenInput(name = "folder") { value = game.folder }
            select {
                name = "platform"
                attributes["aria-label"] = "Platform"
                Platforms.ALL.sortedBy { it.name }.forEach { p -> option { value = p.id; selected = p.id == platform; +p.name } }
            }
            input(type = InputType.text, name = "q") {
                value = query ?: game.main.tags[TagField.TITLE]?.firstOrNull().orEmpty()
                attributes["aria-label"] = "Title"
            }
            button { +"Search" }
        }
        results?.let { found ->
            if (found.isEmpty()) p("empty") { +"No game by that title on that platform." }
            table {
                tbody {
                    found.forEach { m ->
                        tr {
                            td { strong { +m.title } }
                            td { +listOfNotNull(m.year, m.publisher, m.genres.joinToString(", ").ifEmpty { null }).joinToString(" · ") }
                            td {
                                form(action = "/games/game/use", method = FormMethod.post) {
                                    hiddenInput(name = "folder") { value = game.folder }
                                    hiddenInput(name = "platform") { value = m.platform }
                                    hiddenInput(name = "title") { value = m.title }
                                    button(classes = "small") { +"Use this" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    h2 { +"On devices" }
    if (devices.isEmpty()) p("empty") { +"No device syncs games." }
    devices.filter { it.second == Keeps.EVERYTHING }.forEach { (d, _) -> p("muted") { +"${d.name} keeps every game." } }
    devices.filter { it.second == Keeps.PLATFORM }.forEach { (d, _) -> p("muted") { +"${d.name} keeps every ${game.platform?.name ?: ""} game." } }
    val pickers = devices.filter { it.second == Keeps.PICKED || it.second == Keeps.NOT_PICKED }
    if (pickers.isNotEmpty()) {
        form(action = "/games/picks", method = FormMethod.post) {
            hiddenInput(name = "folder") { value = game.folder }
            hiddenInput(name = "back") { value = "/games/game?folder=" + enc(game.folder) }
            pickers.forEach { (d, keeps) ->
                label("check") {
                    input(type = InputType.checkBox, name = "keep.${d.id}") { checked = keeps == Keeps.PICKED }
                    +"Keep on ${d.name}"
                }
            }
            div("actions") { button { +"Save" } }
        }
    }
}

/** Whether a device keeps a game: all its games, its platform's, this one picked, or not. */
private enum class Keeps { EVERYTHING, PLATFORM, PICKED, NOT_PICKED }
