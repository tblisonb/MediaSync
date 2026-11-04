package com.tlisonbee.mediasync.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tlisonbee.mediasync.player.tidal.Tidal
import com.tlisonbee.mediasync.player.tidal.TidalItem
import com.tlisonbee.mediasync.player.tidal.TidalList
import com.tlisonbee.mediasync.player.tidal.TidalSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One TIDAL list as the screens show it: what's arrived so far, and whether more is coming. */
data class TidalListState(val items: List<TidalItem> = emptyList(), val loading: Boolean = false, val error: String? = null, val loadedAt: Long = 0)

/** Which of your TIDAL lists the TIDAL tab shows. */
enum class TidalTab(val label: String, val list: TidalList) {
    TRACKS("Tracks", TidalList.FAVORITE_TRACKS),
    ALBUMS("Albums", TidalList.FAVORITE_ALBUMS),
    ARTISTS("Artists", TidalList.FAVORITE_ARTISTS),
    PLAYLISTS("Playlists", TidalList.PLAYLISTS),
}

/**
 * The TIDAL tab's state: its lists (fetched page by page, kept for the session and fetched again
 * after [STALE_MS] or on request) and search.
 */
class TidalBrowser(private val scope: CoroutineScope, val tidal: Tidal) {
    var tab by mutableStateOf(TidalTab.TRACKS)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var search by mutableStateOf<TidalSearch?>(null)
    var searchError by mutableStateOf<String?>(null)
    private var searchJob: Job? = null

    private val lists = mutableStateMapOf<String, TidalListState>()

    private fun key(list: TidalList, id: String?) = "$list/${id.orEmpty()}"

    fun state(list: TidalList, id: String? = null): TidalListState = lists[key(list, id)] ?: TidalListState()

    /**
     * Shows a list: from memory, else from the disk cache at once; then, unless it was checked in
     * the last minute (or [force]), asks TIDAL whether it changed and fetches it again only if so.
     */
    fun load(list: TidalList, id: String? = null, force: Boolean = false) {
        val k = key(list, id)
        val now = lists[k]
        if (now != null && (now.loading || (!force && now.error == null && System.currentTimeMillis() - now.loadedAt < CHECK_MS))) return
        lists[k] = (now ?: TidalListState()).copy(loading = true, error = null)
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { tidal.cache.read(list, id) }
                if (now == null && cached != null) lists[k] = TidalListState(cached.items, loading = true)
                val fresh = withContext(Dispatchers.IO) {
                    val version = runCatching { tidal.catalog.version(list, id) }.getOrNull()
                    val upToDate = !force && cached != null && when (list) {
                        TidalList.ALBUM_TRACKS -> true
                        TidalList.ARTIST_ALBUMS -> System.currentTimeMillis() - cached.fetched < DAY_MS
                        else -> version != null && version == cached.version
                    }
                    if (upToDate) {
                        cached
                    } else {
                        val pages = tidal.catalog.allDocuments(list, id) { sofar -> scope.launch { lists[k] = TidalListState(sofar, loading = true) } }
                        tidal.cache.write(list, id, version, pages)
                    }
                }
                lists[k] = TidalListState(fresh!!.items, loading = false, loadedAt = System.currentTimeMillis())
            } catch (e: Exception) {
                lists[k] = (lists[k] ?: TidalListState()).copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** After an edit: the list is checked with TIDAL when it's next shown (now, if it's on screen). */
    fun invalidate(list: TidalList, id: String? = null) {
        val k = key(list, id)
        lists[k]?.let { lists[k] = it.copy(loadedAt = 0) }
    }

    /** Takes entries out of a list on screen at once, before TIDAL is asked again. */
    fun drop(list: TidalList, id: String?, gone: (TidalItem) -> Boolean) {
        val k = key(list, id)
        lists[k]?.let { lists[k] = it.copy(items = it.items.filterNot(gone), loadedAt = 0) }
    }

    /** The IDs of your favorite tracks, as far as they've been loaded. */
    fun favoriteIds(kind: TidalItem.Kind): Set<String> {
        val list = when (kind) {
            TidalItem.Kind.TRACK -> TidalList.FAVORITE_TRACKS
            TidalItem.Kind.ALBUM -> TidalList.FAVORITE_ALBUMS
            TidalItem.Kind.ARTIST -> TidalList.FAVORITE_ARTISTS
            TidalItem.Kind.PLAYLIST -> TidalList.PLAYLISTS
        }
        return state(list).items.mapTo(HashSet()) { it.id }
    }

    /** Your own playlists (the ones you can add to), as far as they've been loaded. */
    fun ownPlaylists(): List<TidalItem> = state(TidalList.PLAYLISTS).items.filter { tidal.account.user in it.owners }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) {
            search = null
            return
        }
        searchJob?.cancel()
        searching = true
        searchError = null
        searchJob = scope.launch {
            try {
                search = withContext(Dispatchers.IO) { tidal.catalog.search(q) }
            } catch (e: Exception) {
                searchError = e.message ?: e.javaClass.simpleName
            } finally {
                searching = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        query = ""
        search = null
        searchError = null
        searching = false
    }

    companion object {
        /** How long a list is trusted before TIDAL is asked whether it changed. */
        const val CHECK_MS = 60_000L
        const val DAY_MS = 24 * 3600_000L
    }
}
