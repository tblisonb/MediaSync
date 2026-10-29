package com.tlisonbee.mediasync.ui

import android.app.Application
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.FolderPickItem
import com.tlisonbee.mediasync.api.FolderPickRequest
import com.tlisonbee.mediasync.api.PickItem
import com.tlisonbee.mediasync.api.PickRequest
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.app
import com.tlisonbee.mediasync.data.LastSync
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.sync.ApiClient
import com.tlisonbee.mediasync.sync.ClientConfig
import com.tlisonbee.mediasync.sync.FolderConfig
import com.tlisonbee.mediasync.sync.Preview
import com.tlisonbee.mediasync.sync.PreviewResult
import com.tlisonbee.mediasync.sync.Progress
import com.tlisonbee.mediasync.sync.Decisions
import com.tlisonbee.mediasync.sync.Review
import com.tlisonbee.mediasync.sync.ReviewAction
import com.tlisonbee.mediasync.sync.ReviewGroup
import com.tlisonbee.mediasync.sync.ReviewQueue
import com.tlisonbee.mediasync.sync.ReviewSettings
import com.tlisonbee.mediasync.sync.SyncActivity
import com.tlisonbee.mediasync.sync.SyncEngine
import com.tlisonbee.mediasync.sync.SyncScheduler
import com.tlisonbee.mediasync.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLEncoder

enum class Screen { PERMISSION, PAIR, PREVIEW, STATUS }

data class PairState(val busy: Boolean = false, val error: String? = null)

data class PreviewState(val running: Boolean = false, val read: Int = 0, val total: Int = 0, val result: PreviewResult? = null, val error: String? = null)

/** Whether syncs wait for approval, how often to check, and what waits. */
data class ReviewState(val enabled: Boolean = true, val checkMinutes: Int = ReviewSettings.DEFAULT_MINUTES, val lastCheck: Long? = null, val review: Review = Review.EMPTY)

/** The video picker: every video in the library, and which ones this phone keeps. */
data class VideosState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val items: List<PickItem> = emptyList(),
    val chosen: Set<String> = emptySet(),
    val saving: Boolean = false,
    val error: String? = null,
) {
    val changes get() = items.count { (it.assetId in chosen) != it.picked }
    val chosenBytes get() = items.filter { it.assetId in chosen }.sumOf { it.size }
}

/**
 * The game picker: every platform and game in the library, and which this phone keeps. [chosen]
 * holds folders: a platform's keeps all its games, now and later; a game's keeps all its files.
 */
data class GamesState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val items: List<FolderPickItem> = emptyList(),
    val chosen: Set<String> = emptySet(),
    val saving: Boolean = false,
    val error: String? = null,
) {
    val changes get() = items.count { (it.folder in chosen) != it.picked }

    /** What the choice takes on the phone: whole platforms, and games outside them. */
    val chosenBytes get() = items.filter { it.folder in chosen && (it.whole || platformOf(it) !in chosen) }.sumOf { it.size }

    fun platformOf(game: FolderPickItem) = game.folder.split('/').take(2).joinToString("/")
}

/**
 * Adding an image folder: [suggestions] are folders on the phone that hold pictures (DCIM, and
 * the folders in DCIM and Pictures), relative to its storage.
 */
data class ImagesState(
    val adding: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * The Library tab: the web UI, logged in with this phone's pairing. The phone is let in once with
 * the web password; after that each login is a one-time ticket the server swaps for its token.
 */
data class LibraryState(
    val needsPassword: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** A login for the web view to make, once: POST [WebLogin.body] to [WebLogin.url]. */
    val login: WebLogin? = null,
)

class WebLogin(val url: String, val body: ByteArray)

data class UiState(
    val screen: Screen,
    val config: ClientConfig?,
    val lastSync: LastSync,
    val progress: Progress?,
    val files: Int,
    val pairing: PairState,
    val preview: PreviewState,
    val review: ReviewState = ReviewState(),
    val videos: VideosState = VideosState(),
    /** What the worker is doing, from the moment a button asks for it; null when idle. */
    val activity: SyncActivity? = null,
    /** The review groups whose button was pressed, with what's happening to them ("Accepting"). */
    val applying: Map<String, String> = emptyMap(),
    val images: ImagesState = ImagesState(),
    val games: GamesState = GamesState(),
) {
    val videosEnabled get() = config?.folders?.any { it.prefix == VIDEOS } == true
    val gamesEnabled get() = config?.folders?.any { it.prefix == RepoPaths.GAMES } == true

    /** The folders of the library's images/ this phone syncs, each with a folder of its own. */
    val imageFolders get() = config?.folders?.filter { it.prefix.startsWith(RepoPaths.IMAGES) }.orEmpty()
    val busy get() = activity != null
}

/** Where this phone keeps the videos picked for it. */
const val VIDEOS = "videos/"

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    private val allFilesAccess = MutableStateFlow(Environment.isExternalStorageManager())
    private val pairing = MutableStateFlow(PairState())
    private val preview = MutableStateFlow(PreviewState())
    private val files = MutableStateFlow(0)
    private val review = MutableStateFlow(ReviewState())
    private val videos = MutableStateFlow(VideosState())
    private val games = MutableStateFlow(GamesState())
    private val working = SyncScheduler.activity(app).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Set when a button queues work, until WorkManager reports it (a moment later). */
    private val requested = MutableStateFlow<SyncActivity?>(null)
    private val applying = MutableStateFlow<Map<String, String>>(emptyMap())
    private val images = MutableStateFlow(ImagesState())
    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private data class Extra(val pairing: PairState, val preview: PreviewState, val files: Int, val review: ReviewState, val videos: VideosState)

    private data class Work(val activity: SyncActivity?, val applying: Map<String, String>, val images: ImagesState, val games: GamesState)

    val state: StateFlow<UiState> = combine(
        app.settings.changes,
        SyncState.progress,
        allFilesAccess,
        combine(pairing, preview, files, review, videos, ::Extra),
        combine(working, requested, applying, images, games) { now, asked, groups, pictures, game -> Work(now ?: asked, groups, pictures, game) },
    ) { _, progress, access, (pair, prev, count, rev, vids), work ->
        val settings = app.settings
        val screen = when {
            !access -> Screen.PERMISSION
            settings.config == null -> Screen.PAIR
            !settings.approved -> Screen.PREVIEW
            else -> Screen.STATUS
        }
        val activity = work.activity ?: progress?.let { SyncActivity(SyncActivity.Kind.SYNC, started = true) }
        UiState(screen, settings.config, settings.lastSync, progress, count, pair, prev, rev, vids, activity, work.applying, work.images, work.games)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initial())

    init {
        // A check or apply in the worker ends with a settings change: reload what waits.
        viewModelScope.launch { app.settings.changes.collect { loadReview() } }
        viewModelScope.launch {
            working.collect { now ->
                if (now != null) {
                    requested.value = null
                } else if (requested.value == null && applying.value.isNotEmpty()) {
                    // All done: the pressed groups keep their "Accepting…" until the new list is in.
                    loadReview(doneApplying = true)
                }
            }
        }
    }

    fun loadReview(doneApplying: Boolean = false) {
        val config = app.settings.config ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val settings = ReviewSettings(app.index)
            review.value = ReviewState(
                enabled = settings.enabled,
                checkMinutes = settings.checkMinutes,
                lastCheck = app.index.state(SyncEngine.LAST_CHECK)?.toLong(),
                review = SyncEngine.storedReview(config, app.index, ignoresCase = true),
            )
            if (doneApplying) applying.value = emptyMap()
        }
    }

    /** A group's button. The review it acts on is the one on screen, so nothing newer slips in. */
    fun decide(group: ReviewGroup, action: ReviewAction) {
        val doing = when (action) {
            ReviewAction.ACCEPT -> "Accepting"
            ReviewAction.UNDO -> "Putting the files back"
            ReviewAction.REVERT -> "Reverting on the server"
        }
        queue(review.value.review.decide(group, action), mapOf(group.key to doing))
    }

    fun acceptAll() {
        val review = review.value.review
        queue(review.acceptAll(), review.groups.associate { it.key to "Accepting" })
    }

    private fun queue(decisions: Decisions, groups: Map<String, String>) {
        if (decisions.isEmpty) return
        applying.value = applying.value + groups
        request(SyncActivity.Kind.APPLY)
        ReviewQueue.add(app, decisions)
        SyncScheduler.apply(app)
    }

    fun checkNow() {
        request(SyncActivity.Kind.CHECK)
        SyncScheduler.checkNow(app)
    }

    /** Shows [kind] as waiting right away; WorkManager's own state takes over once it has the work. */
    private fun request(kind: SyncActivity.Kind) {
        if (working.value != null) return
        val asked = SyncActivity(kind, started = false)
        requested.value = asked
        viewModelScope.launch {
            // In case WorkManager kept existing work instead and never reports this one.
            delay(10_000)
            if (requested.compareAndSet(asked, null) && working.value == null && applying.value.isNotEmpty()) loadReview(doneApplying = true)
        }
    }

    /** Adds or removes the videos folder: only the videos picked for this phone, in Movies/MediaSync. */
    fun setVideos(enabled: Boolean) = setFolder(VIDEOS, Environment.DIRECTORY_MOVIES, pick = true, enabled) {
        videos.value = videos.value.copy(error = it)
    }

    /** Adds or removes the games folder: only the games and platforms picked for this phone, in ROMs/MediaSync. */
    fun setGames(enabled: Boolean) = setFolder(RepoPaths.GAMES, "ROMs", pick = true, enabled) {
        games.value = games.value.copy(error = it)
    }

    fun openGames() {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        games.value = GamesState(open = true, loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            games.value = try {
                val items = ApiClient(config.serverUrl, token).folderPicks(RepoPaths.GAMES)
                GamesState(open = true, items = items, chosen = items.filter { it.picked }.map { it.folder }.toSet())
            } catch (e: Exception) {
                GamesState(open = true, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun closeGames() {
        games.value = GamesState()
    }

    fun chooseGames(folders: Collection<String>, keep: Boolean) {
        val state = games.value
        games.value = state.copy(chosen = if (keep) state.chosen + folders else state.chosen - folders.toSet())
    }

    /** Saves the picks; what they bring or take away is applied without asking again. */
    fun saveGames() {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        val state = games.value
        val pick = state.items.filter { it.folder in state.chosen && !it.picked }.map { it.folder }
        val unpick = state.items.filter { it.folder !in state.chosen && it.picked }.map { it.folder }
        games.value = state.copy(saving = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val changed = ApiClient(config.serverUrl, token).setFolderPicks(FolderPickRequest(pick, unpick)).changed
                ReviewQueue.approvePicked(app, changed)
                SyncScheduler.checkNow(app, replace = true)
                games.value = GamesState()
            } catch (e: Exception) {
                games.value = state.copy(saving = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The phone's storage, which image folders are given relative to. */
    val storage: File = Environment.getExternalStorageDirectory()

    fun openAddImages() {
        images.value = ImagesState(adding = true)
        viewModelScope.launch(Dispatchers.IO) {
            val folders = listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES).flatMap { top ->
                val dir = storage.resolve(top)
                val inside = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().map { "$top/${it.name}" }.sorted()
                (if (top == Environment.DIRECTORY_DCIM && dir.isDirectory) listOf(top) else emptyList()) + inside
            }
            images.value = images.value.copy(suggestions = folders)
        }
    }

    fun closeAddImages() {
        images.value = ImagesState()
    }

    /**
     * Syncs images/[name]/ with [folder], given relative to the phone's storage ("DCIM",
     * "Pictures/Screenshots"), or in full (an SD card's). A camera's folder is best [SyncMode.SEND_ONLY]: the library files
     * photos by date, and the phone's names stay as they are. With [keepDeleted], what's deleted
     * here stays in the library.
     */
    fun addImages(name: String, folder: String, mode: SyncMode, keepDeleted: Boolean) {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        val subfolder = name.trim().trim('/')
        val prefix = "${RepoPaths.IMAGES}$subfolder/"
        val dir = folder.trim().let { if (it.startsWith("/")) File(it.trimEnd('/')) else storage.resolve(it.trim('/')) }
        val overlap = config.folders.firstOrNull { dir.toPath().startsWith(it.root) || it.root.startsWith(dir.toPath()) }
        val nested = config.folders.firstOrNull { it.prefix.startsWith(prefix) || prefix.startsWith(it.prefix) }
        val problem = when {
            subfolder.isEmpty() || runCatching { RepoPaths.clean(subfolder) }.getOrNull() != subfolder ->
                "Name a folder of the library's images/, such as photos or screenshots, without characters like : or *."
            folder.isBlank() -> "Choose a folder on this phone."
            nested != null -> "${nested.prefix} already syncs, with ${local(nested.folder)}."
            overlap != null -> "${local(dir.path)} overlaps ${local(overlap.folder)}, which already syncs."
            else -> null
        }
        if (problem != null) {
            images.value = images.value.copy(error = problem)
            return
        }
        images.value = images.value.copy(saving = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dir.mkdirs()
                val updated = config.copy(folders = config.folders + FolderConfig(prefix, dir.path, mode, keepDeleted = keepDeleted && mode == SyncMode.SEND_ONLY))
                ApiClient(config.serverUrl, token).putSubscription(updated.subscription())
                app.settings.updateConfig(updated)
                images.value = ImagesState()
                request(SyncActivity.Kind.CHECK)
                SyncScheduler.checkNow(app, replace = true)
            } catch (e: Exception) {
                images.value = images.value.copy(saving = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Stops syncing [prefix] on this phone. Its files stay where they are, here and on the server. */
    fun removeImages(prefix: String) {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        images.value = images.value.copy(saving = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = config.copy(folders = config.folders.filter { it.prefix != prefix })
                ApiClient(config.serverUrl, token).putSubscription(updated.subscription())
                app.index.forget(prefix, updated)
                app.settings.updateConfig(updated)
                images.value = images.value.copy(saving = false)
                loadReview()
            } catch (e: Exception) {
                images.value = images.value.copy(saving = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Whether what's deleted in the (send-only) folder for [prefix] stays in the library; only this phone needs to know. */
    fun setKeepDeleted(prefix: String, keep: Boolean) {
        val config = app.settings.config ?: return
        viewModelScope.launch(Dispatchers.IO) {
            app.settings.updateConfig(config.copy(folders = config.folders.map { if (it.prefix == prefix) it.copy(keepDeleted = keep) else it }))
        }
    }

    /** [path] as the image folders show it: relative to the phone's storage. */
    fun local(path: String): String = File(path).relativeToOrSelf(storage).path

    /** A MediaSync folder inside one of the phone's public folders (Movies, Pictures), apart from what other apps put there. */
    private fun setFolder(prefix: String, publicFolder: String, pick: Boolean, enabled: Boolean, onError: (String?) -> Unit) {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        onError(null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val folder = Environment.getExternalStoragePublicDirectory(publicFolder).resolve("MediaSync")
                val folders = config.folders.filter { it.prefix != prefix } +
                    if (enabled) listOf(FolderConfig(prefix, folder.path, SyncMode.TWO_WAY, pick = pick)) else emptyList()
                if (enabled) folder.mkdirs()
                val updated = config.copy(folders = folders)
                ApiClient(config.serverUrl, token).putSubscription(updated.subscription())
                app.settings.updateConfig(updated)
                request(SyncActivity.Kind.CHECK)
                SyncScheduler.checkNow(app, replace = true)
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun openVideos() {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        videos.value = VideosState(open = true, loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            videos.value = try {
                val items = ApiClient(config.serverUrl, token).picks(VIDEOS)
                VideosState(open = true, items = items, chosen = items.filter { it.picked }.map { it.assetId }.toSet())
            } catch (e: Exception) {
                VideosState(open = true, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun closeVideos() {
        videos.value = VideosState()
    }

    fun choose(ids: Collection<String>, keep: Boolean) {
        val state = videos.value
        videos.value = state.copy(chosen = if (keep) state.chosen + ids else state.chosen - ids.toSet())
    }

    /** Saves the picks; what they bring or take away is applied without asking again. */
    fun saveVideos() {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        val state = videos.value
        val pick = state.items.filter { it.assetId in state.chosen && !it.picked }.map { it.assetId }
        val unpick = state.items.filter { it.assetId !in state.chosen && it.picked }.map { it.assetId }
        videos.value = state.copy(saving = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val changed = ApiClient(config.serverUrl, token).setPicks(PickRequest(pick, unpick)).changed
                ReviewQueue.approvePicked(app, changed)
                SyncScheduler.checkNow(app, replace = true)
                videos.value = VideosState()
            } catch (e: Exception) {
                videos.value = state.copy(saving = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun setReview(enabled: Boolean? = null, minutes: Int? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = ReviewSettings(app.index)
            enabled?.let { settings.enabled = it }
            minutes?.let { settings.checkMinutes = it }
            SyncScheduler.schedulePeriodic(app)
            // Without review, whatever was waiting syncs now.
            if (enabled == false) SyncScheduler.syncNow(app)
            loadReview()
        }
    }

    private fun initial() = UiState(Screen.PAIR, null, app.settings.lastSync, null, 0, PairState(), PreviewState())

    /** Called when the app comes back to the front, e.g. from the All files access setting. */
    fun refresh() {
        allFilesAccess.value = Environment.isExternalStorageManager()
        viewModelScope.launch(Dispatchers.IO) { files.value = app.index.count() }
        loadReview()
        if (app.settings.config != null && !app.settings.approved && preview.value.result == null && !preview.value.running) runPreview()
    }

    val defaultName: String = Build.MODEL
    val defaultFolder: String = Environment.getExternalStorageDirectory().resolve(Environment.DIRECTORY_MUSIC).path

    fun pair(serverUrl: String, code: String, name: String, folder: String) {
        val url = normalizeUrl(serverUrl) ?: run {
            pairing.value = PairState(error = "Use the server's https:// address, e.g. https://my-pc.tailnet.ts.net:8443")
            return
        }
        pairing.value = PairState(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val pairing = ApiClient(url, null).pair(PairRequest(code.trim(), name.trim(), DeviceKind.ANDROID))
                    val config = ClientConfig(
                        url, pairing.deviceId, name.trim(),
                        // When the phone has none of a track's formats, it prefers the smaller ones.
                        listOf(FolderConfig(RepoPaths.MUSIC, folder.trim(), SyncMode.TWO_WAY, prefer = listOf("mp3", "m4a", "ogg", "opus", "flac"))),
                    )
                    ApiClient(url, pairing.token).putSubscription(config.subscription())
                    app.index.clear()
                    app.settings.pair(config, pairing.token)
                }
                pairing.value = PairState()
                runPreview()
            } catch (e: Exception) {
                pairing.value = PairState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun runPreview() {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        preview.value = PreviewState(running = true)
        viewModelScope.launch(Dispatchers.IO) {
            preview.value = try {
                val policy = ApiClient(config.serverUrl, token).policy()
                val result = Preview.compute(config, policy) { done, total -> preview.value = PreviewState(true, done, total) }
                PreviewState(result = result)
            } catch (e: Exception) {
                PreviewState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun approve() {
        app.settings.approve()
        SyncScheduler.schedulePeriodic(app)
        syncNow()
    }

    fun syncNow(allowDeletes: Boolean = false) {
        request(SyncActivity.Kind.SYNC)
        SyncScheduler.syncNow(app, allowDeletes)
    }

    /** Logs the Library tab's web view in, then shows [next]; or asks for the password first. */
    fun openLibrary(next: String = "/") {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        if (_library.value.busy) return
        _library.value = _library.value.copy(busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            _library.value = try {
                val ticket = ApiClient(config.serverUrl, token).webTicket()
                if (ticket == null) LibraryState(needsPassword = true) else LibraryState(login = webLogin(config.serverUrl, ticket, next))
            } catch (e: Exception) {
                LibraryState(error = "Couldn't reach the server: ${e.message ?: e}")
            }
        }
    }

    /** Lets this phone into the web UI with its password, then logs the web view in. */
    fun allowLibrary(password: String) {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        _library.value = LibraryState(needsPassword = true, busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            _library.value = try {
                val api = ApiClient(config.serverUrl, token)
                when {
                    !api.allowWeb(password) -> LibraryState(needsPassword = true, error = "That isn't the web UI's password.")
                    else -> api.webTicket()?.let { LibraryState(login = webLogin(config.serverUrl, it, "/")) }
                        ?: LibraryState(needsPassword = true, error = "The server didn't let this phone in. Try again.")
                }
            } catch (e: Exception) {
                LibraryState(needsPassword = true, error = "Couldn't reach the server: ${e.message ?: e}")
            }
        }
    }

    /** The web view has the login; it isn't made twice. */
    fun loginSent() {
        _library.value = _library.value.copy(login = null)
    }

    private fun webLogin(server: String, ticket: String, next: String) = WebLogin(
        server.trimEnd('/') + "/login/device",
        "ticket=${URLEncoder.encode(ticket, Charsets.UTF_8)}&next=${URLEncoder.encode(next, Charsets.UTF_8)}".toByteArray(),
    )

    fun unpair() {
        // The web view's login belongs to this pairing.
        CookieManager.getInstance().removeAllCookies(null)
        _library.value = LibraryState()
        SyncScheduler.cancelAll(app)
        app.settings.unpair()
        viewModelScope.launch(Dispatchers.IO) { app.index.clear() }
        preview.value = PreviewState()
    }

    private fun normalizeUrl(text: String): String? {
        val uri = runCatching { URI(text.trim().trimEnd('/')) }.getOrNull() ?: return null
        return if (uri.scheme == "https" && uri.host != null) uri.toString() else null
    }
}
