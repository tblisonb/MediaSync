package com.tlisonbee.mediasync.player.library

import java.io.File

/**
 * Playlists as `.m3u8` files (UTF-8 M3U), which fooyin and most players read. Entries are written
 * relative to the playlist's folder, so a playlist in `Music/Playlists/` works on any device that
 * has the same library, whatever its music folder is called.
 */
object M3u {
    /** One entry of a playlist: its path made absolute, and its `#EXTINF` length and title ("Artist - Title"). */
    data class Entry(val path: String, val title: String?, val durationSec: Int?)

    fun parse(text: String, playlistFolder: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var title: String? = null
        var duration: Int? = null
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substring(8)
                    duration = info.substringBefore(',').trim().toIntOrNull()?.takeIf { it >= 0 }
                    title = info.substringAfter(',', "").trim().ifEmpty { null }
                }
                line.startsWith("#") -> {}
                else -> {
                    val tidal = TIDAL_LINE.find(line)?.groupValues?.get(1)
                    entries += Entry(if (tidal != null) TIDAL_PREFIX + tidal else resolve(line, playlistFolder), title, duration)
                    title = null
                    duration = null
                }
            }
        }
        return entries
    }

    /** An entry's path made absolute: relative ones from the playlist's folder, `\` as `/`, `file://` URLs. */
    fun resolve(entry: String, playlistFolder: String): String {
        var p = entry.replace('\\', '/')
        if (p.startsWith("file:")) p = runCatching { java.net.URI(p).path }.getOrNull() ?: p.removePrefix("file://")
        val file = if (p.startsWith("/")) File(p) else File(playlistFolder, p)
        return file.toPath().normalize().toString()
    }

    fun write(entries: List<Entry>, playlistFolder: String): String = buildString {
        append("#EXTM3U\n")
        for (e in entries) {
            if (e.title != null || e.durationSec != null) append("#EXTINF:").append(e.durationSec ?: -1).append(',').append(e.title.orEmpty()).append('\n')
            append(if (e.path.startsWith(TIDAL_PREFIX)) "tidal://track/" + e.path.removePrefix(TIDAL_PREFIX) else relative(e.path, playlistFolder)).append('\n')
        }
    }

    /** A TIDAL track known only from a playlist line: "Artist - Title" and its length. */
    private fun fromExtinf(entry: Entry): Track {
        val info = entry.title.orEmpty()
        val artist = info.substringBefore(" - ", "").trim()
        val title = if (" - " in info) info.substringAfter(" - ").trim() else info.ifEmpty { entry.path }
        return Track(
            path = entry.path, size = 0, mtime = 0, title = title, artists = listOfNotNull(artist.ifEmpty { null }), album = "",
            albumArtist = "", date = "", genre = "", trackNumber = null, discNumber = null, durationMs = entry.durationSec?.let { it * 1000L }, cover = null,
        )
    }

    fun entry(track: Track) = Entry(track.path, "${track.artist} - ${track.title}", track.durationMs?.let { (it / 1000).toInt() })

    /** [path] relative to [folder] ("../Boston/Third Stage/05 My Destination.mp3"). */
    fun relative(path: String, folder: String): String = runCatching {
        File(folder).toPath().normalize().relativize(File(path).toPath().normalize()).toString()
    }.getOrDefault(path)

    /** TIDAL tracks in playlists, as the fooyin plugin writes them too: `tidal://track/58990486`. */
    private val TIDAL_LINE = Regex("""^tidal://track/(\d+)""")
    private const val TIDAL_PREFIX = "tidal:"

    /**
     * The track an entry means: the file at its path, else (after MediaSync moved the file for a
     * changed tag) a track whose "artist - title" matches its `#EXTINF` title. A TIDAL entry is the
     * TIDAL track [tidal] knows, or one made from its `#EXTINF` line.
     */
    fun match(entry: Entry, library: LibraryData, tidal: (String) -> Track? = { null }): Track? {
        library.byPath[entry.path]?.let { return it }
        if (entry.path.startsWith(TIDAL_PREFIX)) return tidal(entry.path) ?: fromExtinf(entry)
        val title = entry.title?.let(::fold) ?: return null
        return library.tracks.firstOrNull { fold("${it.artist} - ${it.title}") == title }
    }
}
