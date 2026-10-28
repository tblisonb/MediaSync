package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.ChangeType
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.RevisionDto
import com.tlisonbee.mediasync.library.RepoPaths
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.img
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.html.unsafe
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The library's top folders, as the home page sums them up. */
enum class Area(val label: String, val href: String) {
    MUSIC("Music", "/browse?path=music"),
    IMAGES("Images", "/images"),
    VIDEOS("Videos", "/videos"),
    GAMES("Games", "/games"),
    OTHER("Other", "/browse"),
    ;

    companion object {
        fun of(path: String) = when {
            path.startsWith(RepoPaths.MUSIC) -> MUSIC
            path.startsWith(RepoPaths.IMAGES) -> IMAGES
            path.startsWith(RepoPaths.VIDEOS) -> VIDEOS
            path.startsWith(RepoPaths.GAMES) -> GAMES
            else -> OTHER
        }
    }
}

/** A live asset as the home page needs it: no tags beyond a title, so the whole library reads quickly. */
class HomeAsset(
    val assetId: String,
    val path: String,
    val kind: AssetKind,
    /** The revision that first had it. */
    val added: Long,
    val size: Long,
    val durationMs: Long,
    /** Its first rendition's file, for an image's thumbnail. */
    val blob: String?,
    /** Its first embedded picture, for a track's cover. */
    val picture: String?,
    val title: String?,
)

/** A thumbnail to click; a video's has its [length], and moves on hover. */
class Thumb(val blob: String, val href: String, val caption: String, val detail: String? = null, val video: Boolean = false, val length: String? = null)

class MusicCard(val tracks: Int, val albums: Int, val artists: Int, val hours: Long, val recent: List<Thumb>)

/** All of images/: [photos] and [clips] are images/photos/'s, [otherFolders] the rest's file counts. */
class ImagesCard(val images: Int, val photos: Int, val clips: Int, val years: IntRange?, val latest: List<Thumb>, val otherFolders: List<Pair<String, Int>>)

class VideosCard(val videos: Int, val hours: Long, val recent: List<Thumb>)

class GamesCard(val games: Int, val files: Int, val platforms: List<Triple<String, String, Int>>, val unknown: Int)

class DeviceCard(val name: String, val kind: DeviceKind, val lastSeenAt: Long?, val behind: Long, val areas: List<String>)

/** Something to look at, and where to do it. */
class Attention(val text: String, val href: String?)

/** One line of recent activity: a revision, or a run of a device's pushes that gave no message. */
class Activity(val text: String, val device: String, val at: Long, val newest: Long, val oldest: Long)

class Home(
    val head: Long,
    val sizes: Map<Area, Long>,
    val stored: Long,
    val free: Long?,
    val music: MusicCard,
    val images: ImagesCard,
    val videos: VideosCard,
    val games: GamesCard,
    val devices: List<DeviceCard>,
    val attention: List<Attention>,
    val backupAt: Long?,
    val activity: List<Activity>,
)

private const val DAY_MS = 24 * 3600_000L

/** A device that hasn't synced for this long, while changes wait for it, is worth a mention. */
private const val QUIET_DAYS = 3

/** A backup snapshot older than this means the nightly backup isn't running. */
private const val BACKUP_DAYS = 2

fun Repository.liveSummary(): List<HomeAsset> = db.read {
    db.query(
        """
        SELECT h.asset_id, h.path, v.kind,
          (SELECT MIN(a.rev) FROM asset_version a WHERE a.asset_id = h.asset_id) AS added,
          (SELECT COALESCE(SUM(b.size), 0) FROM json_each(v.renditions) j JOIN media m ON m.media_hash = j.value JOIN blob b ON b.hash = m.base_blob) AS size,
          (SELECT COALESCE(MAX(m.duration_ms), 0) FROM json_each(v.renditions) j JOIN media m ON m.media_hash = j.value) AS duration,
          (SELECT m.base_blob FROM media m WHERE m.media_hash = json_extract(v.renditions, '$[0]')) AS blob,
          json_extract(v.pictures, '$[0].blob') AS picture,
          json_extract(v.tags, '$.title[0]') AS title
        FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.deleted = 0
        """.trimIndent(),
    ) {
        HomeAsset(
            assetId = it.getString("asset_id"),
            path = it.getString("path"),
            kind = AssetKind.entries.first { k -> k.apiName == it.getString("kind") },
            added = it.getLong("added"),
            size = it.getLong("size"),
            durationMs = it.getLong("duration"),
            blob = it.getString("blob"),
            picture = it.getString("picture"),
            title = it.getString("title"),
        )
    }
}

fun Repository.home(now: Long = System.currentTimeMillis()): Home {
    val assets = liveSummary()
    val head = head()
    val byArea = assets.groupBy { Area.of(it.path) }
    val games = gamesCard()
    val devices = devices().filter { it.revokedAt == null && it.kind != DeviceKind.WEB }.map { d ->
        DeviceCard(d.name, d.kind, d.lastSeenAt, (head - d.syncedRev).coerceAtLeast(0), d.subscription.rules.map { areaName(it.prefix) }.distinct())
    }
    val snapshot = dir.snapshots.resolve("repo.db")
    val backupAt = if (Files.exists(snapshot)) Files.getLastModifiedTime(snapshot).toMillis() else null

    val attention = buildList {
        val issues = openIssueCount()
        if (issues > 0) add(Attention(plural(issues, "entry needs", "entries need") + " a decision", "/resolve"))
        devices.filter { it.behind > 0 && (it.lastSeenAt == null || now - it.lastSeenAt > QUIET_DAYS * DAY_MS) }.forEach { d ->
            val text = d.lastSeenAt?.let { "${d.name} hasn't synced in ${(now - it) / DAY_MS} days" } ?: "${d.name} hasn't synced yet"
            add(Attention(text, "/devices"))
        }
        val undated = byArea[Area.IMAGES].orEmpty().count { it.path.startsWith(RepoPaths.PHOTOS + RepoPaths.UNDATED + "/") }
        if (undated > 0) add(Attention(plural(undated, "photo has", "photos have") + " no date", "/images?path=" + enc(RepoPaths.PHOTOS + RepoPaths.UNDATED)))
        if (games.unknown > 0) add(Attention(plural(games.unknown, "game needs", "games need") + " a platform", "/games"))
        if (backupAt != null && now - backupAt > BACKUP_DAYS * DAY_MS) add(Attention("The last backup started ${(now - backupAt) / DAY_MS} days ago", null))
    }

    return Home(
        head = head,
        sizes = Area.entries.associateWith { area -> byArea[area].orEmpty().sumOf { it.size } },
        stored = db.long("SELECT COALESCE(SUM(size), 0) FROM blob"),
        free = runCatching { Files.getFileStore(dir.root).usableSpace }.getOrNull(),
        music = musicCard(byArea[Area.MUSIC].orEmpty()),
        images = imagesCard(byArea[Area.IMAGES].orEmpty()),
        videos = videosCard(byArea[Area.VIDEOS].orEmpty()),
        games = games,
        devices = devices,
        attention = attention,
        backupAt = backupAt,
        activity = activity(revisions(null, 100).revisions),
    )
}

private fun musicCard(music: List<HomeAsset>): MusicCard {
    val tracks = music.filter { it.kind == AssetKind.AUDIO }
    // music/{artist}/{album}/…: an album's discs share its folder.
    fun album(path: String) = path.split('/').take(3).joinToString("/")
    val albums = tracks.groupBy { album(it.path) }
    val art = music.filter { it.kind == AssetKind.IMAGE && it.blob != null }.groupBy { album(it.path) }
    val recent = albums.entries.sortedByDescending { (_, inAlbum) -> inAlbum.maxOf { it.added } }.take(6).map { (folder, inAlbum) ->
        val pictures = art[folder].orEmpty().sortedBy { pic -> COVER_NAMES.indexOf(RepoPaths.name(pic.path).substringBeforeLast('.').lowercase()).let { if (it < 0) 99 else it } }
        val cover = pictures.firstOrNull()?.blob ?: inAlbum.firstNotNullOfOrNull { it.picture }
        val parts = folder.split('/')
        Thumb(cover.orEmpty(), "/browse?path=" + enc(folder), parts.getOrElse(2) { parts.last() }, parts.getOrNull(1))
    }
    return MusicCard(
        tracks = tracks.size,
        albums = albums.size,
        artists = tracks.map { it.path.split('/').getOrElse(1) { "" } }.distinct().size,
        hours = tracks.sumOf { it.durationMs } / 3600_000,
        recent = recent,
    )
}

private fun imagesCard(images: List<HomeAsset>): ImagesCard {
    val photos = images.filter { RepoPaths.isPhoto(it.kind, it.path) }
    // images/photos/{year}/…
    val years = photos.mapNotNull { it.path.removePrefix(RepoPaths.PHOTOS).substringBefore('/').toIntOrNull() }
    val latest = photos.filter { it.kind == AssetKind.IMAGE && it.blob != null && !it.path.startsWith(RepoPaths.PHOTOS + RepoPaths.UNDATED + "/") }
        .sortedByDescending { it.path }.take(6)
        .map { Thumb(it.blob!!, viewerUrl(it.assetId), RepoPaths.name(it.path)) }
    val others = images.filter { !it.path.startsWith(RepoPaths.PHOTOS) }
        .groupingBy { it.path.removePrefix(RepoPaths.IMAGES).substringBefore('/') }.eachCount()
        .toList().sortedByDescending { it.second }
    return ImagesCard(
        images = images.count { it.kind == AssetKind.IMAGE },
        photos = photos.count { it.kind == AssetKind.IMAGE },
        clips = photos.count { it.kind == AssetKind.VIDEO },
        years = if (years.isEmpty()) null else years.min()..years.max(),
        latest = latest,
        otherFolders = others,
    )
}

private fun videosCard(files: List<HomeAsset>): VideosCard {
    val videos = files.filter { it.kind == AssetKind.VIDEO }
    val recent = videos.sortedWith(compareByDescending<HomeAsset> { it.added }.thenBy { it.path }).take(3).map {
        val title = it.title ?: RepoPaths.name(it.path)
        Thumb(it.blob.orEmpty(), viewerUrl(it.assetId), title, video = true, length = it.durationMs.takeIf { ms -> ms > 0 }?.let(::duration))
    }
    return VideosCard(videos.size, videos.sumOf { it.durationMs } / 3600_000, recent)
}

private fun Repository.gamesCard(): GamesCard {
    val games = games()
    val platforms = games.filter { it.platform != null }.groupBy { it.platform!! }.map { (platform, inPlatform) ->
        Triple(platform.name, "/games/platform?folder=" + enc("games/${RepoPaths.segment(platform.name)}"), inPlatform.size)
    }.sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.first })
    return GamesCard(games.size, games.sumOf { it.files.size }, platforms, games.count { it.platform == null })
}

/** What a subscription rule's folder is called on the home page. */
private fun areaName(prefix: String): String = when {
    prefix == RepoPaths.PHOTOS || prefix.startsWith(RepoPaths.PHOTOS) -> "Photos"
    prefix.startsWith(RepoPaths.IMAGES) && prefix.length > RepoPaths.IMAGES.length ->
        prefix.removePrefix(RepoPaths.IMAGES).trimEnd('/').replaceFirstChar { it.uppercase() }
    else -> Area.of(prefix).takeIf { it != Area.OTHER }?.label ?: prefix.trimEnd('/').ifEmpty { "Everything" }
}

/**
 * Recent revisions, newest first, as lines to read: a device's pushes in a row that day, which
 * carry no message, become one line ("desktop added 412 tracks"); a revision with its own message
 * keeps it. Revisions that changed no files are left out.
 */
private fun Repository.activity(revisions: List<RevisionDto>): List<Activity> {
    val runs = mutableListOf<MutableList<RevisionDto>>()
    for (r in revisions) {
        val own = r.message != describe(r.changes)
        if (!own && r.changes.isEmpty()) continue
        val last = runs.lastOrNull()
        val joins = last != null && !own && last.none { it.message != describe(it.changes) } &&
            last.last().deviceId == r.deviceId && day(last.last().createdAt) == day(r.createdAt)
        if (joins) last!!.add(r) else runs.add(mutableListOf(r))
    }
    return runs.map { run ->
        val first = run.first()
        val text = if (run.size == 1 && first.message != describe(first.changes)) {
            first.message
        } else {
            val counts = run.flatMap { it.changes.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
            val added = if (counts.keys == setOf(ChangeType.ADDED)) addedNouns(run.last().rev, first.rev) else null
            "${first.deviceName} " + (added?.let { "added $it" } ?: describe(counts).replaceFirstChar { it.lowercase() })
        }
        Activity(text, first.deviceName, first.createdAt, first.rev, run.last().rev)
    }
}

/** "412 tracks, 12 photos": what revisions [from]..[to] added, by what it is. */
private fun Repository.addedNouns(from: Long, to: Long): String? {
    val counts = db.query("SELECT path, kind FROM asset_version WHERE rev BETWEEN ? AND ?", from, to) {
        noun(it.getString(1), AssetKind.entries.first { k -> k.apiName == it.getString(2) })
    }.groupingBy { it }.eachCount()
    if (counts.isEmpty()) return null
    return counts.entries.sortedByDescending { it.value }.joinToString(", ") { (noun, n) -> "%,d %s".format(n, if (n == 1) noun else noun + "s") }
}

private fun noun(path: String, kind: AssetKind) = when {
    kind == AssetKind.AUDIO -> "track"
    kind == AssetKind.GAME -> "game file"
    kind == AssetKind.VIDEO && path.startsWith(RepoPaths.PHOTOS) -> "video clip"
    kind == AssetKind.VIDEO -> "video"
    kind == AssetKind.IMAGE && path.startsWith(RepoPaths.PHOTOS) -> "photo"
    kind == AssetKind.IMAGE && path.startsWith(RepoPaths.MUSIC) -> "cover"
    kind == AssetKind.IMAGE -> "picture"
    else -> "file"
}

private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun plural(n: Number, one: String, many: String) = "%,d %s".format(n, if (n.toLong() == 1L) one else many)

private fun count(n: Int) = "%,d".format(n)

/** "5 min ago", "3 h ago", "2 days ago"; a date after a month. */
internal fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - millis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 2 * 24 * 60 -> "yesterday"
        minutes < 30 * 24 * 60 -> "${minutes / (24 * 60)} days ago"
        else -> DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
private val DAY_HEADING = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

fun Route.homeRoutes(repo: Repository) {
    get("/") {
        val home = io { repo.home() }
        call.respondHtml { homePage(home) }
    }
}

private fun HTML.homePage(home: Home) = page("Home") {
    h1("visually-hidden") { +"Home" }
    if (home.attention.isEmpty()) {
        p("all-good") { +"Everything's in order: nothing to resolve, and every device is keeping up." }
    } else {
        section("attention") {
            h2("visually-hidden") { +"Needs attention" }
            ul {
                home.attention.forEach { item ->
                    li { if (item.href != null) a(href = item.href) { +item.text } else +item.text }
                }
            }
        }
    }
    div("home") {
        div("areas") {
            musicCard(home.music, home.sizes.getValue(Area.MUSIC))
            imagesCard(home.images, home.sizes.getValue(Area.IMAGES))
            videosCard(home.videos, home.sizes.getValue(Area.VIDEOS))
            gamesCard(home.games, home.sizes.getValue(Area.GAMES))
        }
        div("side") {
            devices(home)
            storage(home)
        }
        activity(home.activity)
    }
    script(src = "/static/previews.js") { }
}

private fun FlowContent.areaCard(area: Area, size: Long, headline: String, content: FlowContent.() -> Unit) = section("area-card ${area.name.lowercase()}") {
    div("area-head") {
        h2 { a(href = area.href) { +area.label } }
        span("muted num") { +bytes(size) }
    }
    p("headline") { +headline }
    content()
}

private fun FlowContent.thumbs(thumbs: List<Thumb>, classes: String = "thumbs") {
    if (thumbs.isEmpty()) return
    ul(classes) {
        thumbs.forEach { t ->
            li {
                a(href = t.href) {
                    attributes["title"] = listOfNotNull(t.caption, t.detail, t.length).joinToString(" · ")
                    if (t.blob.isNotEmpty()) {
                        img(alt = t.caption, src = "/web/thumbs/${t.blob}") {
                            attributes["loading"] = "lazy"
                            if (t.video) attributes["data-frames"] = "/web/frames/${t.blob}"
                        }
                        t.length?.let { span("length") { +it } }
                    } else {
                        span("no-thumb") { +t.caption.take(1) }
                    }
                }
            }
        }
    }
}

private fun FlowContent.musicCard(m: MusicCard, size: Long) = areaCard(Area.MUSIC, size, plural(m.tracks, "track", "tracks")) {
    if (m.tracks == 0) return@areaCard p("empty") { +"No music yet." }
    p("muted") { +"${plural(m.albums, "album", "albums")} · ${plural(m.artists, "artist", "artists")} · ${plural(m.hours, "hour", "hours")}" }
    if (m.recent.isNotEmpty()) p("caption") { +"Recently added" }
    thumbs(m.recent)
}

private fun FlowContent.imagesCard(ph: ImagesCard, size: Long) = areaCard(Area.IMAGES, size, plural(ph.images, "image", "images")) {
    if (ph.images + ph.clips == 0 && ph.otherFolders.isEmpty()) return@areaCard p("empty") { +"No images yet." }
    p("muted") {
        +listOfNotNull(
            ph.photos.takeIf { it > 0 }?.let { plural(it, "photo", "photos") },
            ph.clips.takeIf { it > 0 }?.let { plural(it, "video clip", "video clips") },
            ph.years?.let { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" },
        ).joinToString(" · ")
    }
    thumbs(ph.latest)
    if (ph.otherFolders.isNotEmpty()) {
        p("muted small") {
            +"Also: "
            ph.otherFolders.forEachIndexed { i, (folder, n) ->
                if (i > 0) +" · "
                a(href = "/images?path=" + enc(RepoPaths.IMAGES + folder)) { +folder }
                +" ${count(n)}"
            }
        }
    }
}

private fun FlowContent.videosCard(v: VideosCard, size: Long) = areaCard(Area.VIDEOS, size, plural(v.videos, "video", "videos")) {
    if (v.videos == 0) return@areaCard p("empty") { +"No videos yet." }
    if (v.hours > 0) p("muted") { +plural(v.hours, "hour", "hours") }
    p("caption") { +"Recently added" }
    thumbs(v.recent, "thumbs wide")
    ul("plain") { v.recent.forEach { t -> li { a(href = t.href) { +t.caption } } } }
}

private fun FlowContent.gamesCard(g: GamesCard, size: Long) = areaCard(Area.GAMES, size, plural(g.games, "game", "games")) {
    if (g.games == 0) return@areaCard p("empty") { +"No games yet." }
    p("muted") { +"${plural(g.files, "file", "files")} on ${plural(g.platforms.size, "platform", "platforms")}" }
    ul("plain platforms") {
        g.platforms.forEach { (name, href, n) -> li { a(href = href) { +name }; span("muted num") { +count(n) } } }
        if (g.unknown > 0) li { a(href = "/games") { +"Platform unknown" }; span("muted num") { +count(g.unknown) } }
    }
}

private fun FlowContent.devices(home: Home) = section("side-card") {
    h2 { a(href = "/devices") { +"Devices" } }
    if (home.devices.isEmpty()) return@section p("empty") { +"No devices yet. "; a(href = "/devices") { +"Pair one" } }
    ul("devices") {
        home.devices.forEach { d ->
            li {
                div("device-head") {
                    span("name") { +d.name }
                    if (d.behind == 0L) span("state ok") { +"✓ up to date" }
                    else span("state behind") { +"${plural(d.behind, "revision", "revisions")} behind" }
                }
                p("muted small") {
                    +(d.lastSeenAt?.let { "${d.kind.name.lowercase()} · seen ${ago(it)}" } ?: "${d.kind.name.lowercase()} · never seen")
                }
                if (d.areas.isNotEmpty()) div("chips") { d.areas.forEach { span("chip") { +it } } }
            }
        }
    }
}

/** The live library split by area: an SVG, since the CSP allows no inline styles to size a bar. */
private fun FlowContent.storage(home: Home) = section("side-card") {
    h2 { +"Storage" }
    val parts = Area.entries.map { it to home.sizes.getValue(it) }.filter { it.second > 0 }
    val total = parts.sumOf { it.second }
    if (total == 0L) return@section p("empty") { +"Nothing stored yet." }
    p("headline") { +bytes(total) }
    div("storage-bar") {
        var x = 0.0
        val rects = parts.joinToString("") { (area, size) ->
            val w = size * 1000.0 / total
            val rect = """<rect class="seg-${area.name.lowercase()}" x="%.2f" y="0" width="%.2f" height="12"><title>%s: %s</title></rect>"""
                .format(x, w, area.label, bytes(size))
            x += w
            rect
        }
        unsafe { +"""<svg viewBox="0 0 1000 12" preserveAspectRatio="none" role="img" aria-label="Library by area">$rects</svg>""" }
    }
    ul("legend") {
        parts.forEach { (area, size) ->
            li {
                span("swatch seg-${area.name.lowercase()}") { }
                span { +area.label }
                span("muted num") { +bytes(size) }
            }
        }
    }
    p("muted small") {
        +"${bytes(home.stored)} stored, with old versions"
        home.free?.let { +" · ${bytes(it)} free" }
    }
    p("muted small") { +(home.backupAt?.let { "Last backup started ${ago(it)}" } ?: "No backups yet") }
}

private fun FlowContent.activity(activity: List<Activity>) = section("activity") {
    h2 { a(href = "/revisions") { +"Recent activity" } }
    if (activity.isEmpty()) return@section p("empty") { +"Nothing yet." }
    val today = LocalDate.now()
    activity.take(15).groupBy { day(it.at) }.forEach { (date, entries) ->
        div("day") {
            p("day-name") {
                +when (date) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> DAY_HEADING.format(date)
                }
            }
            ul("plain") {
                entries.forEach { e ->
                    li {
                        span { +e.text }
                        span("muted small when") {
                            +"${CLOCK.format(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()))} · "
                            a(href = "/revisions/${e.newest}") { +(if (e.oldest == e.newest) "r${e.newest}" else "r${e.oldest}–r${e.newest}") }
                        }
                    }
                }
            }
        }
    }
    p { a(href = "/revisions") { +"All history" } }
}
