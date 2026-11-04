package com.tlisonbee.mediasync.player.tidal

import com.tlisonbee.mediasync.player.library.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * The TIDAL tracks the player has seen, by ID, kept on disk (the newest few thousand), so the
 * playback service can fill in a TIDAL item a screen sends and a saved queue comes back with its
 * TIDAL tracks.
 */
class TidalTracks(private val file: File) {
    private val tracks = object : LinkedHashMap<String, Track>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?) = size > KEEP
    }
    private var loaded = false
    private var dirty = false

    @Synchronized
    fun get(path: String): Track? {
        load()
        return tracks[path]
    }

    @Synchronized
    fun remember(list: Collection<Track>) {
        load()
        for (t in list) if (t.path.startsWith(PREFIX)) tracks[t.path] = t
        dirty = true
    }

    /** Writes what changed since the last save. */
    @Synchronized
    fun save() {
        if (!dirty) return
        dirty = false
        val json = buildJsonArray {
            for (t in tracks.values) add(
                buildJsonObject {
                    put("path", JsonPrimitive(t.path))
                    put("title", JsonPrimitive(t.title))
                    put("artists", JsonArray(t.artists.map(::JsonPrimitive)))
                    put("album", JsonPrimitive(t.album))
                    put("albumArtist", JsonPrimitive(t.albumArtist))
                    put("date", JsonPrimitive(t.date))
                    t.trackNumber?.let { put("track", JsonPrimitive(it)) }
                    t.discNumber?.let { put("disc", JsonPrimitive(it)) }
                    t.durationMs?.let { put("duration", JsonPrimitive(it)) }
                    t.cover?.let { put("cover", JsonPrimitive(it)) }
                },
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val array = runCatching { Json.parseToJsonElement(file.readText()) as JsonArray }.getOrNull() ?: return
        for (e in array) {
            val o = e as? JsonObject ?: continue
            val path = o.string("path") ?: continue
            tracks[path] = Track(
                path = path, size = 0, mtime = 0,
                title = o.string("title").orEmpty(),
                artists = (o["artists"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                album = o.string("album").orEmpty(),
                albumArtist = o.string("albumArtist").orEmpty(),
                date = o.string("date").orEmpty(),
                genre = "",
                trackNumber = o["track"]?.jsonPrimitive?.intOrNull,
                discNumber = o["disc"]?.jsonPrimitive?.intOrNull,
                durationMs = o["duration"]?.jsonPrimitive?.longOrNull,
                cover = o.string("cover"),
            )
        }
    }

    companion object {
        /** TIDAL tracks' IDs in the player: "tidal:58990486". */
        const val PREFIX = "tidal:"
        private const val KEEP = 5000

        fun idOf(path: String) = path.removePrefix(PREFIX)

        fun isTidal(path: String) = path.startsWith(PREFIX)
    }
}
