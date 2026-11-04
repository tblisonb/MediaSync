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

    /** Fetches a list unless it's fresh (or [force]). */
    fun load(list: TidalList, id: String? = null, force: Boolean = false) {
        val k = key(list, id)
        val now = lists[k]
        if (now != null && (now.loading || (!force && now.error == null && System.currentTimeMillis() - now.loadedAt < STALE_MS))) return
        lists[k] = (now ?: TidalListState()).copy(loading = true, error = null)
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    tidal.catalog.all(list, id) { sofar -> scope.launch { lists[k] = TidalListState(sofar, loading = true) } }
                }
                lists[k] = TidalListState(items, loading = false, loadedAt = System.currentTimeMillis())
            } catch (e: Exception) {
                lists[k] = (lists[k] ?: TidalListState()).copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Forgets a list, so it's fetched again when next shown (after an edit). */
    fun invalidate(list: TidalList, id: String? = null) {
        lists.remove(key(list, id))
    }

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
        const val STALE_MS = 10 * 60_000L
    }
}
