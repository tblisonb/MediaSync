package com.tlisonbee.mediasync.player.library

import java.io.File
import java.text.Normalizer

/** One audio file in the library, with the tags the player uses. */
data class Track(
    /** The absolute path, which is also its ID everywhere (media IDs, playlists, the index). */
    val path: String,
    val size: Long,
    val mtime: Long,
    val title: String,
    val artists: List<String>,
    val album: String,
    val albumArtist: String,
    val date: String,
    val genre: String,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long?,
    /** The key of its cover in [Covers], or null without one. */
    val cover: String?,
    val gain: Gains = Gains.NONE,
) {
    val artist: String get() = artists.joinToString(", ").ifEmpty { UNKNOWN_ARTIST }

    /** Who the album is filed under: its album artist, else its (first) artist. */
    val albumArtistOrArtist: String get() = albumArtist.ifEmpty { artists.firstOrNull() ?: "" }.ifEmpty { UNKNOWN_ARTIST }

    val albumOrUnknown: String get() = album.ifEmpty { UNKNOWN_ALBUM }

    val year: String get() = Regex("""\d{4}""").find(date)?.value ?: ""

    val folder: String get() = path.substringBeforeLast('/')

    val fileName: String get() = path.substringAfterLast('/')

    val albumKey: String get() = albumKey(albumArtistOrArtist, albumOrUnknown)

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"

        fun albumKey(artist: String, album: String) = sortKey(artist) + "\u0000" + sortKey(album)

        /** The leading number of a track or disc tag ("3", "03", "3/12"). */
        fun number(value: String?): Int? = value?.trim()?.let { Regex("""^\d+""").find(it)?.value?.toIntOrNull() }

        /** The title of an untagged file: its name without the extension. */
        fun titleOf(file: File) = file.nameWithoutExtension
    }
}

/** ReplayGain values in dB and peaks (1 = full scale), when the file has them. */
data class Gains(val trackGain: Float?, val trackPeak: Float?, val albumGain: Float?, val albumPeak: Float?) {
    companion object {
        val NONE = Gains(null, null, null, null)
    }
}

/**
 * How names sort and match: case and accents ignored, and a leading "The" ignored for sorting
 * ("The Beatles" under B).
 */
fun sortKey(text: String): String {
    val folded = fold(text)
    return if (folded.startsWith("the ") && folded.length > 4) folded.substring(4) else folded
}

/** Lowercase without accents, for sorting and searching. */
fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "").lowercase().trim()

class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: String,
    /** In disc and track order. */
    val tracks: List<Track>,
) {
    val cover: String? get() = tracks.firstNotNullOfOrNull { it.cover }
    val durationMs: Long get() = tracks.sumOf { it.durationMs ?: 0 }
    val discs: Int get() = tracks.mapNotNull { it.discNumber }.distinct().size
}

class Artist(
    val name: String,
    /** Albums filed under the artist, oldest first. */
    val albums: List<Album>,
    /** Their tracks on other artists' albums (compilations, guest spots). */
    val appearsOn: List<Track>,
) {
    val trackCount: Int get() = albums.sumOf { it.tracks.size } + appearsOn.size

    /** Every track, album by album, then the ones on other albums. */
    val tracks: List<Track> get() = albums.flatMap { it.tracks } + appearsOn
}

/** A playlist file in the library (`.m3u` or `.m3u8`). */
data class PlaylistFile(val path: String, val mtime: Long) {
    val name: String get() = path.substringAfterLast('/').substringBeforeLast('.')
}

/** Everything the screens show, worked out once from the tracks. */
class LibraryData(all: List<Track>) {
    /** By title. */
    val tracks: List<Track> = all.sortedWith(compareBy<Track>({ sortKey(it.title) }, { sortKey(it.artist) }, { it.path }))

    val byPath: Map<String, Track> = tracks.associateBy { it.path }

    val albums: List<Album> = tracks.groupBy { it.albumKey }.map { (key, list) ->
        val sorted = list.sortedWith(trackOrder)
        val first = sorted.first()
        Album(key, first.albumOrUnknown, first.albumArtistOrArtist, sorted.map { it.year }.filter { it.isNotEmpty() }.minOrNull() ?: "", sorted)
    }.sortedWith(compareBy({ sortKey(it.title) }, { sortKey(it.artist) }))

    val albumsByKey: Map<String, Album> = albums.associateBy { it.key }

    val artists: List<Artist> = run {
        val filed = albums.groupBy { sortKey(it.artist) }
        // Track artists who aren't the album's artist, by their key.
        val guests = HashMap<String, MutableList<Track>>()
        val names = HashMap<String, String>()
        for (t in tracks) {
            val owner = sortKey(t.albumArtistOrArtist)
            for (a in t.artists) {
                val k = sortKey(a)
                if (k.isEmpty() || k == owner) continue
                guests.getOrPut(k) { mutableListOf() } += t
                names.putIfAbsent(k, a)
            }
        }
        (filed.keys + guests.keys).map { k ->
            val own = filed[k].orEmpty().sortedWith(compareBy({ it.year.ifEmpty { "9999" } }, { sortKey(it.title) }))
            Artist(
                name = own.firstOrNull()?.artist ?: names.getValue(k),
                albums = own,
                appearsOn = guests[k].orEmpty().sortedWith(compareBy<Track>({ it.year }, { sortKey(it.album) }).then(trackOrder)),
            )
        }.sortedBy { sortKey(it.name) }
    }

    val artistsByKey: Map<String, Artist> = artists.associateBy { sortKey(it.name) }

    fun artist(name: String): Artist? = artistsByKey[sortKey(name)]

    /** The folders and tracks directly inside [folder]. */
    fun folder(folder: String): FolderListing {
        val prefix = folder.trimEnd('/') + "/"
        val subfolders = sortedMapOf<String, Int>(compareBy(::fold))
        val here = mutableListOf<Track>()
        for (t in tracks) {
            if (!t.path.startsWith(prefix)) continue
            val rest = t.path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) here += t else subfolders.merge(rest.substring(0, slash), 1, Int::plus)
        }
        return FolderListing(
            subfolders.map { (name, count) -> Subfolder(prefix + name, name, count) },
            here.sortedWith(compareBy({ fold(it.fileName) }, { it.path })),
        )
    }

    // Folded once, on the first search.
    private val trackText by lazy { tracks.map { fold("${it.title} ${it.artist} ${it.album} ${it.albumArtist}") } }
    private val albumText by lazy { albums.map { fold("${it.title} ${it.artist}") } }
    private val artistText by lazy { artists.map { fold(it.name) } }

    /** Artists, albums and tracks matching every word of [query], in title, artist or album. */
    fun search(query: String): SearchResults {
        val words = fold(query).split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        fun <T> List<T>.matching(text: List<String>) = filterIndexed { i, _ -> words.all { it in text[i] } }
        return SearchResults(artists.matching(artistText), albums.matching(albumText), tracks.matching(trackText))
    }

    companion object {
        val EMPTY = LibraryData(emptyList())

        val trackOrder: Comparator<Track> = compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: Int.MAX_VALUE }, { fold(it.fileName) })
    }
}

class Subfolder(val path: String, val name: String, val trackCount: Int)

class FolderListing(val folders: List<Subfolder>, val tracks: List<Track>)

class SearchResults(val artists: List<Artist>, val albums: List<Album>, val tracks: List<Track>) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}
