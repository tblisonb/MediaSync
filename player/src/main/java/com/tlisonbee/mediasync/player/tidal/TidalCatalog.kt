package com.tlisonbee.mediasync.player.tidal

import com.tlisonbee.mediasync.player.library.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

/** Something in a TIDAL list: a track (with [track] to play), an album, an artist or a playlist. */
data class TidalItem(
    val kind: Kind,
    val id: String,
    val title: String,
    /** The artists; for a playlist, "Mix" or "TIDAL" for those TIDAL makes. */
    val artist: String = "",
    val album: String = "",
    /** A year, a type ("EP"), a track count: the second line's extra. */
    val info: String = "",
    val durationMs: Long? = null,
    val cover: String? = null,
    val track: Track? = null,
    /** A playlist entry's own ID, which removing it needs. */
    val playlistItemId: String? = null,
    /** A playlist's owners' user IDs (none for mixes). */
    val owners: List<String> = emptyList(),
    val isrc: String? = null,
) {
    enum class Kind { TRACK, ALBUM, ARTIST, PLAYLIST }
}

class TidalSearch(val tracks: List<TidalItem>, val albums: List<TidalItem>, val artists: List<TidalItem>, val playlists: List<TidalItem>)

class TidalPage(val items: List<TidalItem>, val nextCursor: String?)

/** The lists the TIDAL tab shows. */
enum class TidalList { FAVORITE_TRACKS, FAVORITE_ALBUMS, FAVORITE_ARTISTS, PLAYLISTS, ALBUM_TRACKS, PLAYLIST_TRACKS, ARTIST_ALBUMS }

/**
 * TIDAL's official API (v2, JSON:API) with the account login: search, favorites, playlists,
 * albums and artists, and the edits. Blocking; call it off the main thread. What was learned about
 * the API is in the fooyin plugin's docs/design.md.
 */
class TidalCatalog(private val account: TidalSession) {
    private fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: JsonObject? = null): JsonObject {
        val token = account.accessToken()
        val url = API + path + "?" + TidalHttp.encodeForm(query + ("countryCode" to account.countryCode))
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/vnd.api+json")
            if (body != null) {
                put("Content-Type", "application/vnd.api+json")
                // The same key on every retry, so a change TIDAL already made isn't made twice.
                put("Idempotency-Key", UUID.randomUUID().toString())
            }
        }
        var attempt = 0
        while (true) {
            val result = TidalHttp.send(method, url, headers, body?.toString()?.toByteArray())
            if (result.status == 429 && attempt < 3) {
                Thread.sleep(2000L * ++attempt)
                continue
            }
            if (!result.ok) throw IOException(describe(result))
            return if (result.body.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(result.text).jsonObject
        }
    }

    fun search(query: String): TidalSearch = parseSearch(
        request(
            "GET", "/searchResults",
            mapOf("filter[query]" to query, "include" to "tracks,tracks.artists,tracks.albums,tracks.albums.coverArt,albums,albums.artists,albums.coverArt,artists,playlists,playlists.coverArt"),
        ),
    )

    fun page(list: TidalList, id: String?, cursor: String?): TidalPage = parsePage(pageDocument(list, id, cursor))

    /** One page of a list as TIDAL sent it, for the cache. */
    fun pageDocument(list: TidalList, id: String?, cursor: String?): JsonObject {
        val (path, include, sort) = when (list) {
            TidalList.FAVORITE_TRACKS -> Triple("/userCollectionTracks/me/relationships/items", "items,items.artists,items.albums,items.albums.coverArt", "-addedAt")
            TidalList.FAVORITE_ALBUMS -> Triple("/userCollectionAlbums/me/relationships/items", "items,items.artists,items.coverArt", "-addedAt")
            TidalList.FAVORITE_ARTISTS -> Triple("/userCollectionArtists/me/relationships/items", "items", "-addedAt")
            TidalList.PLAYLISTS -> Triple("/userCollectionPlaylists/me/relationships/items", "items,items.owners,items.coverArt", "-lastModifiedAt")
            TidalList.ALBUM_TRACKS -> Triple("/albums/${TidalHttp.enc(id!!)}/relationships/items", "items,items.artists,items.albums,items.albums.coverArt", null)
            TidalList.PLAYLIST_TRACKS -> Triple("/playlists/${TidalHttp.enc(id!!)}/relationships/items", "items,items.artists,items.albums,items.albums.coverArt", null)
            TidalList.ARTIST_ALBUMS -> Triple("/artists/${TidalHttp.enc(id!!)}/relationships/albums", "albums,albums.artists,albums.coverArt", null)
        }
        val query = buildMap {
            put("include", include)
            sort?.let { put("sort", it) }
            cursor?.let { put("page[cursor]", it) }
        }
        return request("GET", path, query)
    }

    /** Every page of a list as TIDAL sent it; [onPage] sees the items so far. */
    fun allDocuments(list: TidalList, id: String? = null, onPage: (List<TidalItem>) -> Unit = {}): List<JsonObject> {
        val docs = mutableListOf<JsonObject>()
        val items = mutableListOf<TidalItem>()
        var cursor: String? = null
        do {
            val doc = pageDocument(list, id, cursor)
            docs += doc
            val page = parsePage(doc)
            items += page.items
            onPage(items.toList())
            cursor = page.nextCursor
        } while (cursor != null)
        return docs
    }

    /**
     * What a list's version is now, to tell whether a cached copy is still right; null for lists
     * without one (albums don't change; an artist's albums are fetched again after a day).
     */
    fun version(list: TidalList, id: String?): String? {
        val path = when (list) {
            TidalList.FAVORITE_TRACKS -> "/userCollectionTracks/me"
            TidalList.FAVORITE_ALBUMS -> "/userCollectionAlbums/me"
            TidalList.FAVORITE_ARTISTS -> "/userCollectionArtists/me"
            TidalList.PLAYLISTS -> "/userCollectionPlaylists/me"
            TidalList.PLAYLIST_TRACKS -> "/playlists/${TidalHttp.enc(id!!)}"
            TidalList.ALBUM_TRACKS, TidalList.ARTIST_ALBUMS -> return null
        }
        val attrs = request("GET", path).obj("data")?.obj("attributes")
        var version = "${attrs?.string("lastModifiedAt")}/${attrs?.get("numberOfItems") ?: attrs?.get("numberOfTrackItems")}"
        if (list == TidalList.PLAYLISTS) {
            // Each playlist's track count shows in the list, and an edited playlist comes first: the first page counts too.
            version += ";" + page(list, null, null).items.joinToString(";") { "${it.id}@${it.info}" }.hashCode()
        }
        return version
    }

    /** Every page of a list. */
    fun all(list: TidalList, id: String? = null, onPage: (List<TidalItem>) -> Unit = {}): List<TidalItem> {
        val items = mutableListOf<TidalItem>()
        var cursor: String? = null
        do {
            val page = page(list, id, cursor)
            items += page.items
            onPage(items.toList())
            cursor = page.nextCursor
        } while (cursor != null)
        return items
    }

    // Edits. TIDAL takes at most 50 items a request.

    fun addToPlaylist(playlistId: String, trackIds: List<String>) =
        change("POST", "/playlists/${TidalHttp.enc(playlistId)}/relationships/items", trackIds.map { resource(it, "tracks") })

    fun removeFromPlaylist(playlistId: String, entries: List<TidalItem>) = change(
        "DELETE", "/playlists/${TidalHttp.enc(playlistId)}/relationships/items",
        entries.map { e ->
            val itemId = e.playlistItemId ?: throw IOException("TIDAL didn't give this playlist's entries IDs; open it again and retry")
            buildJsonObject {
                put("id", e.id)
                put("type", "tracks")
                put("meta", buildJsonObject { put("itemId", itemId) })
            }
        },
    )

    fun setFavorite(kind: TidalItem.Kind, ids: List<String>, favorite: Boolean) {
        val (collection, type) = when (kind) {
            TidalItem.Kind.TRACK -> "userCollectionTracks" to "tracks"
            TidalItem.Kind.ALBUM -> "userCollectionAlbums" to "albums"
            TidalItem.Kind.ARTIST -> "userCollectionArtists" to "artists"
            TidalItem.Kind.PLAYLIST -> "userCollectionPlaylists" to "playlists"
        }
        change(if (favorite) "POST" else "DELETE", "/$collection/me/relationships/items", ids.map { resource(it, type) })
    }

    /** Makes an unlisted playlist (TIDAL has no private ones); returns its ID. */
    fun createPlaylist(name: String): String {
        val body = buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("type", "playlists")
                    put("attributes", buildJsonObject { put("name", name); put("accessType", "UNLISTED") })
                },
            )
        }
        return request("POST", "/playlists", body = body).obj("data")?.string("id") ?: throw IOException("TIDAL didn't return the new playlist")
    }

    private fun resource(id: String, type: String) = buildJsonObject { put("id", id); put("type", type) }

    private fun change(method: String, path: String, data: List<JsonObject>) {
        for (chunk in data.chunked(50)) request(method, path, body = buildJsonObject { put("data", JsonArray(chunk)) })
    }

    /**
     * The TIDAL track for one of your files, or null: by its artist and title in TIDAL's search,
     * taking only a track with the same title, the artist among its artists and the same length
     * (within 3 s), so a live version or a cover isn't taken in its place; the same album first.
     */
    fun findTrack(track: Track): String? {
        if (TidalTracks.isTidal(track.path)) return TidalTracks.idOf(track.path)
        val title = comparable(track.title)
        val artist = comparable(track.artists.firstOrNull() ?: track.albumArtist)
        if (title.isEmpty() || artist.isEmpty()) return null
        val found = search("${track.artists.firstOrNull() ?: track.albumArtist} ${track.title}").tracks.filter { c ->
            comparable(c.title) == title && comparable(c.artist).contains(artist) &&
                (track.durationMs == null || c.durationMs == null || kotlin.math.abs(c.durationMs - track.durationMs) <= 3000)
        }
        val album = comparable(track.album)
        return (found.firstOrNull { album.isNotEmpty() && comparable(it.album) == album } ?: found.firstOrNull())?.id
    }

    /** The account's user ID: playlists it owns are the ones it can edit. */
    val userId: String get() = account.user

    companion object {
        const val API = "https://openapi.tidal.com/v2"

        private fun describe(result: HttpResult): String {
            val detail = runCatching {
                ((Json.parseToJsonElement(result.text).jsonObject["errors"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("detail")
            }.getOrNull()
            return "TIDAL said HTTP ${result.status}" + (detail?.let { ": $it" } ?: "")
        }

        fun parseSearch(doc: JsonObject): TidalSearch {
            val none = TidalSearch(emptyList(), emptyList(), emptyList(), emptyList())
            val data = doc["data"] as? JsonArray ?: return none
            val relationships = (data.firstOrNull() as? JsonObject)?.obj("relationships") ?: return none
            val included = Included(doc)
            fun relation(name: String) = items(relationships.obj(name)?.get("data") as? JsonArray ?: JsonArray(emptyList()), included)
            return TidalSearch(relation("tracks"), relation("albums"), relation("artists"), relation("playlists"))
        }

        fun parsePage(doc: JsonObject): TidalPage = TidalPage(
            items(doc["data"] as? JsonArray ?: JsonArray(emptyList()), Included(doc)),
            doc.obj("links")?.obj("meta")?.string("nextCursor")?.takeIf { it.isNotEmpty() },
        )

        /** Text as compared when matching tracks: letters and digits only, case and accents folded. */
        fun comparable(text: String): String =
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD).filter { it.isLetterOrDigit() }.lowercase()

        /** `PT3M59S` in milliseconds. */
        fun durationMs(iso: String?): Long? {
            val m = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$""").matchEntire(iso ?: return null) ?: return null
            fun g(i: Int) = m.groupValues[i].toDoubleOrNull() ?: 0.0
            return ((g(1) * 86400 + g(2) * 3600 + g(3) * 60 + g(4)) * 1000).toLong()
        }

        /**
         * A cover key for an artwork on TIDAL's image server: "t" and the image's ID, which
         * [coverUrl] turns back into an address. Null for artwork elsewhere.
         */
        fun coverKey(artwork: JsonObject?): String? {
            val files = artwork?.obj("attributes")?.get("files") as? JsonArray ?: return null
            val href = files.mapNotNull { (it as? JsonObject)?.string("href") }.firstOrNull() ?: return null
            val path = Regex("""^https://resources\.tidal\.com/images/([0-9a-fA-F/]+)/\d+x\d+\.jpg$""").matchEntire(href)?.groupValues?.get(1) ?: return null
            val hex = path.replace("/", "")
            return if (hex.length == 32) COVER_PREFIX + hex.lowercase() else null
        }

        const val COVER_PREFIX = "t"

        /** The 640-pixel image for a key from [coverKey]. */
        fun coverUrl(key: String): String? {
            val hex = key.removePrefix(COVER_PREFIX).takeIf { key.startsWith(COVER_PREFIX) && it.length == 32 } ?: return null
            val path = listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20)).joinToString("/")
            return "https://resources.tidal.com/images/$path/640x640.jpg"
        }

        private fun items(links: JsonArray, included: Included): List<TidalItem> = included.resolve(links).mapNotNull { r ->
            when (r.string("type")) {
                "tracks" -> trackItem(r, included)
                "albums" -> albumItem(r, included)
                "artists" -> TidalItem(TidalItem.Kind.ARTIST, r.string("id")!!, r.attrs()?.string("name").orEmpty())
                "playlists" -> playlistItem(r, included)
                else -> null // videos
            }
        }

        private fun names(artists: List<JsonObject>) = artists.mapNotNull { it.attrs()?.string("name") }

        private fun trackItem(r: JsonObject, included: Included): TidalItem {
            val attrs = r.attrs()
            val id = r.string("id")!!
            val version = attrs?.string("version")?.takeIf { it.isNotBlank() }
            val title = attrs?.string("title").orEmpty() + (version?.let { " ($it)" } ?: "")
            val artists = names(included.related(r, "artists"))
            val album = included.related(r, "albums").firstOrNull()
            val albumAttrs = album?.attrs()
            val albumArtists = album?.let { names(included.related(it, "artists")) }.orEmpty().ifEmpty { artists.take(1) }
            val meta = r.obj("linkMeta")
            val cover = album?.let { coverKey(included.related(it, "coverArt").firstOrNull()) }
            val duration = durationMs(attrs?.string("duration"))
            val track = Track(
                path = TidalTracks.PREFIX + id,
                size = 0, mtime = 0,
                title = title,
                artists = artists,
                album = albumAttrs?.string("title").orEmpty(),
                albumArtist = albumArtists.firstOrNull().orEmpty(),
                date = albumAttrs?.string("releaseDate").orEmpty(),
                genre = "",
                trackNumber = meta?.get("trackNumber")?.jsonPrimitive?.intOrNull,
                discNumber = meta?.get("volumeNumber")?.jsonPrimitive?.intOrNull,
                durationMs = duration,
                cover = cover,
            )
            return TidalItem(
                TidalItem.Kind.TRACK, id, title, artists.joinToString(", "), track.album, durationMs = duration, cover = cover, track = track,
                playlistItemId = meta?.string("itemId"), isrc = attrs?.string("isrc"),
            )
        }

        private fun albumItem(r: JsonObject, included: Included): TidalItem {
            val attrs = r.attrs()
            val info = listOfNotNull(
                attrs?.string("releaseDate")?.take(4)?.takeIf { it.isNotEmpty() },
                attrs?.string("type")?.takeIf { it.isNotEmpty() && it != "ALBUM" }?.let { if (it == "EP") it else it.lowercase().replaceFirstChar(Char::uppercase) },
            ).joinToString(" · ")
            return TidalItem(
                TidalItem.Kind.ALBUM, r.string("id")!!, attrs?.string("title").orEmpty(), names(included.related(r, "artists")).joinToString(", "),
                info = info, cover = coverKey(included.related(r, "coverArt").firstOrNull()),
            )
        }

        private fun playlistItem(r: JsonObject, included: Included): TidalItem {
            val attrs = r.attrs()
            val type = attrs?.string("playlistType")
            val owners = (r.obj("relationships")?.obj("owners")?.get("data") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("id") }
            val count = attrs?.get("numberOfTrackItems")?.jsonPrimitive?.intOrNull
            return TidalItem(
                TidalItem.Kind.PLAYLIST, r.string("id")!!, attrs?.string("name").orEmpty(),
                artist = when (type) { "MIX" -> "Mix"; "EDITORIAL" -> "TIDAL"; else -> "" },
                info = count?.let { if (it == 1) "1 track" else "$it tracks" }.orEmpty(),
                cover = coverKey(included.related(r, "coverArt").firstOrNull()),
                owners = owners,
            )
        }
    }
}

/** A JSON:API document's `included` resources, by type and ID. */
private class Included(doc: JsonObject) {
    private val resources: Map<Pair<String?, String?>, JsonObject> =
        (doc["included"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.string("type") to it.string("id") }

    fun find(type: String?, id: String?) = resources[type to id]

    /** The resources a relationship of [resource] links to, as far as they're included. */
    fun related(resource: JsonObject, relationship: String): List<JsonObject> =
        (resource.obj("relationships")?.obj(relationship)?.get("data") as? JsonArray).orEmpty()
            .mapNotNull { link -> (link as? JsonObject)?.let { find(it.string("type"), it.string("id")) } }

    /** A list's resources in its order, each with its linkage's meta (track numbers, addedAt, itemId) as `linkMeta`. */
    fun resolve(links: JsonArray): List<JsonObject> = links.mapNotNull { link ->
        val linkage = link as? JsonObject ?: return@mapNotNull null
        val found = find(linkage.string("type"), linkage.string("id")) ?: return@mapNotNull null
        val meta = linkage["meta"]
        if (meta is JsonObject) JsonObject(found + ("linkMeta" to meta)) else found
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.attrs(): JsonObject? = obj("attributes")

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
