package com.tlisonbee.mediasync.player.tidal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Your TIDAL lists as TIDAL sent them, on disk per account (as the fooyin plugin's cache), so they
 * show at once; [TidalCatalog.version] then says whether they changed.
 */
class TidalCache(private val dir: File, private val account: TidalSession) {
    class Entry(val version: String?, val fetched: Long, val pages: List<JsonObject>) {
        val items: List<TidalItem> by lazy { pages.flatMap { TidalCatalog.parsePage(it).items } }
    }

    private fun file(list: TidalList, id: String?): File? {
        val user = account.user.ifEmpty { return null }
        val name = when (list) {
            TidalList.FAVORITE_TRACKS -> "favorite-tracks"
            TidalList.FAVORITE_ALBUMS -> "favorite-albums"
            TidalList.FAVORITE_ARTISTS -> "favorite-artists"
            TidalList.PLAYLISTS -> "playlists"
            TidalList.ALBUM_TRACKS -> "album-" + safe(id)
            TidalList.PLAYLIST_TRACKS -> "playlist-" + safe(id)
            TidalList.ARTIST_ALBUMS -> "artist-" + safe(id)
        }
        return File(File(dir, safe(user)), "$name.json")
    }

    private fun safe(s: String?) = s.orEmpty().replace(Regex("[^A-Za-z0-9-]"), "_")

    fun read(list: TidalList, id: String?): Entry? {
        val f = file(list, id)?.takeIf { it.isFile } ?: return null
        val json = runCatching { Json.parseToJsonElement(f.readText()) as JsonObject }.getOrNull() ?: return null
        if (json["format"]?.jsonPrimitive?.intOrNull != FORMAT) return null
        f.setLastModified(System.currentTimeMillis())
        return Entry(
            json.string("version"), json["fetched"]?.jsonPrimitive?.longOrNull ?: 0,
            (json["pages"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty(),
        )
    }

    fun write(list: TidalList, id: String?, version: String?, pages: List<JsonObject>): Entry {
        val entry = Entry(version, System.currentTimeMillis(), pages)
        val f = file(list, id) ?: return entry
        f.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            version?.let { put("version", JsonPrimitive(it)) }
            put("fetched", JsonPrimitive(entry.fetched))
            put("pages", JsonArray(pages))
        }
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(f)
        return entry
    }

    fun remove(list: TidalList, id: String?) {
        file(list, id)?.delete()
    }

    /** Deletes lists not opened for a month. */
    fun prune() {
        val old = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        dir.walk().filter { it.isFile && it.lastModified() < old }.forEach { it.delete() }
    }

    private companion object {
        const val FORMAT = 1
    }
}
