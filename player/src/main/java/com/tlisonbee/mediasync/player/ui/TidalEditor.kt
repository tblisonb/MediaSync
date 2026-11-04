package com.tlisonbee.mediasync.player.ui

import com.tlisonbee.mediasync.player.library.Track
import com.tlisonbee.mediasync.player.tidal.TidalItem
import com.tlisonbee.mediasync.player.tidal.TidalList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Changes to your TIDAL playlists and favorites, from any track's menu. Your own files are found on
 * TIDAL first ([com.tlisonbee.mediasync.player.tidal.TidalCatalog.findTrack]); the message says
 * which weren't. The lists an edit changes are checked with TIDAL again.
 */
class TidalEditor(private val scope: CoroutineScope, private val browser: TidalBrowser, private val message: (String) -> Unit) {
    private val catalog get() = browser.tidal.catalog

    private fun name(tracks: List<Track>) = if (tracks.size == 1) "“${tracks[0].title}”" else "${tracks.size} tracks"

    /** TIDAL's IDs for [tracks], and the ones it doesn't have. */
    private fun ids(tracks: List<Track>): Pair<List<String>, List<Track>> {
        val found = tracks.map { it to catalog.findTrack(it) }
        return found.mapNotNull { it.second } to found.filter { it.second == null }.map { it.first }
    }

    private fun missing(gone: List<Track>) =
        if (gone.isEmpty()) "" else " TIDAL doesn't have " + gone.take(3).joinToString { "“${it.title}”" } + (if (gone.size > 3) " and ${gone.size - 3} more" else "") + "."

    private fun run(work: suspend () -> String) {
        scope.launch {
            message(
                try {
                    work()
                } catch (e: Exception) {
                    "TIDAL: ${e.message ?: e.javaClass.simpleName}"
                },
            )
        }
    }

    fun addToPlaylist(tracks: List<Track>, playlist: TidalItem) = run {
        val (ids, gone) = withContext(Dispatchers.IO) { ids(tracks) }
        if (ids.isNotEmpty()) withContext(Dispatchers.IO) { catalog.addToPlaylist(playlist.id, ids) }
        browser.invalidate(TidalList.PLAYLIST_TRACKS, playlist.id)
        browser.invalidate(TidalList.PLAYLISTS)
        (if (ids.isEmpty()) "Nothing added to ${playlist.title}." else "Added ${name(tracks.filter { it !in gone })} to ${playlist.title}.") + missing(gone)
    }

    fun createPlaylist(name: String, tracks: List<Track>) = run {
        val (ids, gone) = withContext(Dispatchers.IO) { ids(tracks) }
        val id = withContext(Dispatchers.IO) { catalog.createPlaylist(name).also { if (ids.isNotEmpty()) catalog.addToPlaylist(it, ids) } }
        browser.invalidate(TidalList.PLAYLISTS)
        browser.invalidate(TidalList.PLAYLIST_TRACKS, id)
        "Made the TIDAL playlist $name" + (if (ids.isNotEmpty()) " with ${name(tracks.filter { it !in gone })}." else ".") + missing(gone)
    }

    fun removeFromPlaylist(playlist: String, title: String, entries: List<TidalItem>) = run {
        withContext(Dispatchers.IO) { catalog.removeFromPlaylist(playlist, entries) }
        val gone = entries.mapNotNull { it.playlistItemId }.toSet()
        browser.drop(TidalList.PLAYLIST_TRACKS, playlist) { it.playlistItemId in gone }
        browser.invalidate(TidalList.PLAYLISTS)
        browser.load(TidalList.PLAYLIST_TRACKS, playlist)
        "Removed ${if (entries.size == 1) "“${entries[0].title}”" else "${entries.size} tracks"} from $title."
    }

    fun favoriteTracks(tracks: List<Track>, add: Boolean) = run {
        val (ids, gone) = withContext(Dispatchers.IO) { ids(tracks) }
        if (ids.isNotEmpty()) withContext(Dispatchers.IO) { catalog.setFavorite(TidalItem.Kind.TRACK, ids, add) }
        if (!add) browser.drop(TidalList.FAVORITE_TRACKS, null) { it.id in ids } else browser.invalidate(TidalList.FAVORITE_TRACKS)
        (if (add) "Added ${name(tracks.filter { it !in gone })} to your TIDAL favorites." else "Removed ${name(tracks)} from your TIDAL favorites.") + missing(gone)
    }

    fun favorite(item: TidalItem, add: Boolean) = run {
        withContext(Dispatchers.IO) { catalog.setFavorite(item.kind, listOf(item.id), add) }
        val list = when (item.kind) {
            TidalItem.Kind.TRACK -> TidalList.FAVORITE_TRACKS
            TidalItem.Kind.ALBUM -> TidalList.FAVORITE_ALBUMS
            TidalItem.Kind.ARTIST -> TidalList.FAVORITE_ARTISTS
            TidalItem.Kind.PLAYLIST -> TidalList.PLAYLISTS
        }
        if (!add) browser.drop(list, null) { it.id == item.id } else browser.invalidate(list)
        if (add) "Added ${item.title} to your TIDAL favorites." else "Removed ${item.title} from your TIDAL favorites."
    }
}
