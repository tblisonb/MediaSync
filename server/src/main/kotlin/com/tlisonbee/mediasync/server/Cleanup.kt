package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Fields
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import java.text.Normalizer
import java.util.Locale

/**
 * Library cleanup: names spelled several ways, edits to many tracks at once, and files left behind
 * in folders their tracks moved out of. Every change is one revision, so it can be reverted.
 */

/** One way a name is spelled, and how many tracks use it. */
data class Spelling(val value: String, val tracks: Int)

/**
 * Spellings that differ only in case, accents, punctuation, a leading "The" or "&" for "and". For
 * an album, [artistKey] is the album artist's [nameKey]: two artists' "Greatest Hits" stay apart.
 */
data class NameVariants(val field: String, val artistKey: String?, val artist: String?, val spellings: List<Spelling>)

/** The fields an artist's name appears in. */
val ARTIST_FIELDS = listOf(TagField.ARTIST, TagField.ALBUM_ARTIST)

/** What [NameVariants] compares: "The Killers", "Killers" and "KILLERS" all give "killers". */
fun nameKey(name: String): String {
    var s = Normalizer.normalize(name, Normalizer.Form.NFKD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    s = s.lowercase(Locale.ROOT).trim()
    s = s.replace(Regex("""\s*[&+]\s*"""), " and ").removePrefix("the ")
    return s.filter { it.isLetterOrDigit() }.ifEmpty { s }
}

/** Artist names (across artist and album artist), then album names within an artist. */
fun Repository.nameVariants(): List<NameVariants> = db.read {
    val tracks = liveTracks()
    val artists = LinkedHashMap<String, MutableMap<String, Int>>()
    val albums = LinkedHashMap<Pair<String, String>, MutableMap<String, Int>>()
    for (track in tracks) {
        for (name in ARTIST_FIELDS.flatMap { track.tags[it].orEmpty() }.distinct()) {
            artists.getOrPut(nameKey(name)) { LinkedHashMap() }.merge(name, 1, Int::plus)
        }
        val artist = albumArtist(track) ?: continue
        for (album in track.tags[TagField.ALBUM].orEmpty()) {
            albums.getOrPut(nameKey(artist) to nameKey(album)) { LinkedHashMap() }.merge(album, 1, Int::plus)
        }
    }
    fun spellings(counts: Map<String, Int>) = counts.map { (value, n) -> Spelling(value, n) }.sortedByDescending { it.tracks }
    val artistGroups = artists.filterValues { it.size > 1 }.map { (_, counts) -> NameVariants(TagField.ARTIST, null, null, spellings(counts)) }
    val albumGroups = albums.filterValues { it.size > 1 }.map { (key, counts) ->
        val artist = artists[key.first]?.maxByOrNull { it.value }?.key
        NameVariants(TagField.ALBUM, key.first, artist, spellings(counts))
    }
    (artistGroups + albumGroups).sortedBy { (it.artist ?: it.spellings.first().value).lowercase(Locale.ROOT) }
}

private fun albumArtist(v: Version) =
    (v.tags[TagField.ALBUM_ARTIST] ?: v.tags[TagField.ARTIST])?.firstOrNull()

/**
 * Replaces the spellings [from] with [to]: for an artist in both artist fields of every track,
 * for an album only on the tracks of the artist [artistKey] names.
 */
fun Repository.rename(deviceId: String, field: String, from: Set<String>, to: String, artistKey: String? = null): Long? {
    val name = to.trim()
    if (name.isEmpty()) badRequest("give the new name")
    if (from.isEmpty()) badRequest("pick the names to replace")
    val fields = when (field) {
        TagField.ARTIST -> ARTIST_FIELDS
        TagField.ALBUM -> listOf(TagField.ALBUM)
        else -> badRequest("only artists and albums can be renamed here")
    }
    val quoted = from.filter { it != name }.joinToString(", ") { "“$it”" }
    return write {
        val scope = RevisionScope(this, deviceId, "Renamed ${if (field == TagField.ALBUM) "album" else "artist"} $quoted to “$name”")
        for (track in liveTracks()) {
            if (artistKey != null && albumArtist(track)?.let(::nameKey) != artistKey) continue
            val changed = fields.associateWith { f -> track.tags[f].orEmpty().map { if (it in from) name else it }.distinct() }
                .filter { (f, values) -> values != track.tags[f].orEmpty() }
            if (changed.isNotEmpty()) scope.write(track.copy(tags = policy.filter(track.kind, track.tags + changed)))
        }
        scope.refreshAlbums()
        scope.finish()
    }
}

/**
 * Sets fields on several assets in one revision. [edits] maps an asset to the fields that change;
 * an empty list removes the field. [expectedRevs] guards against edits made since the form was
 * shown: if any asset moved on, nothing is written.
 */
fun Repository.editTags(deviceId: String, message: String?, edits: Map<String, Map<String, List<String>>>, expectedRevs: Map<String, Long> = emptyMap()): Long? = write {
    val scope = RevisionScope(this, deviceId, message)
    for ((assetId, fields) in edits) {
        val head = headVersion(assetId)?.takeIf { !it.deleted } ?: notFound("no asset $assetId")
        expectedRevs[assetId]?.let { if (it != head.rev) throw RequestException("${RepoPaths.name(head.path)} changed since the page was opened; reload it", 409) }
        if (fields.isEmpty()) continue
        val tags = head.tags.toMutableMap()
        var category = head.category
        for ((field, values) in fields) {
            when {
                field == Fields.CATEGORY -> {
                    category = values.firstOrNull()?.also { if (it !in RepoPaths.VIDEO_CATEGORIES) badRequest("no category $it") }
                    if (head.kind != AssetKind.VIDEO) badRequest("only videos have a category")
                }
                values.isEmpty() -> tags.remove(field)
                else -> tags[field] = values
            }
        }
        scope.write(head.copy(tags = policy.filter(head.kind, tags), category = category))
    }
    scope.refreshAlbums()
    scope.finish()
}

/** A file left where its album used to be, and where its tracks are now. */
data class StrayMove(val assetId: String, val from: String, val to: String)

data class StrayPlan(val moves: List<StrayMove>, val unplaced: List<String>)

/**
 * Files that aren't tracks and sit outside their album's folder: a track's folder follows its
 * tags, but a cover scan keeps the path it was imported with. Each is matched to the tracks
 * imported from its folder (or the nearest folder above it that held tracks), and goes where
 * those tracks are now. Folders whose tracks went to several places are left alone.
 */
fun Repository.strayPlan(): StrayPlan = db.read {
    val sources = db.query(
        """
        SELECT i.source_path, h.path FROM asset_import i JOIN asset_head h ON h.asset_id = i.asset_id
        JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.deleted = 0 AND v.kind = ?
        """.trimIndent(),
        AssetKind.AUDIO.apiName,
    ) { it.getString(1) to it.getString(2) }
    // Every tail of two or more folder names a track was imported from → where such tracks are now.
    val now = HashMap<String, MutableSet<String>>()
    for ((source, path) in sources) {
        if (!RepoPaths.isMusic(path)) continue
        val folders = source.replace('\\', '/').split('/').dropLast(1).filter { it.isNotEmpty() }.map { RepoPaths.segment(it) }
        for (n in 2..folders.size) {
            now.getOrPut(folderKey(folders.takeLast(n))) { HashSet() } += RepoPaths.parent(path)
        }
    }
    val moves = mutableListOf<StrayMove>()
    val unplaced = mutableListOf<String>()
    val extras = db.query(
        """
        SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.deleted = 0 AND v.kind != ? AND h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
        """.trimIndent(),
        AssetKind.AUDIO.apiName, RepoPaths.MUSIC, "music0",
    ) { it.toVersion() }
    for (extra in extras) {
        val folders = RepoPaths.parent(extra.path).removePrefix(RepoPaths.MUSIC).split('/')
        val match = (folders.size downTo 2).firstNotNullOfOrNull { n -> now[folderKey(folders.take(n))]?.let { n to it } } ?: continue
        val (n, targets) = match
        val target = targets.singleOrNull()
        if (target == null) {
            unplaced += extra.path
            continue
        }
        val to = target + "/" + extra.path.removePrefix(RepoPaths.MUSIC).split('/').drop(n).joinToString("/")
        if (RepoPaths.parent(to) != RepoPaths.parent(extra.path)) moves += StrayMove(extra.assetId, extra.path, to)
    }
    StrayPlan(moves, unplaced)
}

private fun folderKey(folders: List<String>) = folders.joinToString("/").lowercase(Locale.ROOT)

/** Carries out [strayPlan] as one revision. */
fun Repository.tidyStrays(deviceId: String): Long? {
    val plan = strayPlan()
    if (plan.moves.isEmpty()) return null
    return write {
        val scope = RevisionScope(this, deviceId, "Moved ${plan.moves.size} files to their albums' folders")
        for (move in plan.moves) {
            val head = headVersion(move.assetId)?.takeIf { !it.deleted && it.path == move.from } ?: continue
            scope.moveExtra(head, move.to)
        }
        scope.finish()
    }
}

/** The album artists of the tracks under [folder] (an artist's folder holds one, unless names differ only in characters a path can't hold). */
fun Repository.artistsIn(folder: String): List<String> = db.read {
    liveUnder(folder).filter { it.kind == AssetKind.AUDIO }.mapNotNull(::albumArtist).distinct().sorted()
}

/** Album folders holding tracks without an artist, album or title, with how many there are. */
fun Repository.incompleteAlbums(): List<Pair<String, Int>> = db.read {
    liveTracks().filter { t -> listOf(TagField.ARTIST, TagField.ALBUM, TagField.TITLE).any { t.tags[it].isNullOrEmpty() } }
        .groupingBy { RepoPaths.parent(it.path) }.eachCount().toList().sortedBy { it.first.lowercase(Locale.ROOT) }
}
