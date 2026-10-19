package com.tlisonbee.mediasync.ui

import android.app.Application
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tlisonbee.mediasync.api.DeviceKind
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

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
) {
    val videosEnabled get() = config?.folders?.any { it.prefix == VIDEOS } == true
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
    private val working = SyncScheduler.activity(app).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Set when a button queues work, until WorkManager reports it (a moment later). */
    private val requested = MutableStateFlow<SyncActivity?>(null)
    private val applying = MutableStateFlow<Map<String, String>>(emptyMap())

    private data class Extra(val pairing: PairState, val preview: PreviewState, val files: Int, val review: ReviewState, val videos: VideosState)

    private data class Work(val activity: SyncActivity?, val applying: Map<String, String>)

    val state: StateFlow<UiState> = combine(
        app.settings.changes,
        SyncState.progress,
        allFilesAccess,
        combine(pairing, preview, files, review, videos, ::Extra),
        combine(working, requested, applying) { now, asked, groups -> Work(now ?: asked, groups) },
    ) { _, progress, access, (pair, prev, count, rev, vids), work ->
        val settings = app.settings
        val screen = when {
            !access -> Screen.PERMISSION
            settings.config == null -> Screen.PAIR
            !settings.approved -> Screen.PREVIEW
            else -> Screen.STATUS
        }
        val activity = work.activity ?: progress?.let { SyncActivity(SyncActivity.Kind.SYNC, started = true) }
        UiState(screen, settings.config, settings.lastSync, progress, count, pair, prev, rev, vids, activity, work.applying)
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
    fun setVideos(enabled: Boolean) {
        val config = app.settings.config ?: return
        val token = app.settings.token ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).resolve("MediaSync")
                val folders = config.folders.filter { it.prefix != VIDEOS } +
                    if (enabled) listOf(FolderConfig(VIDEOS, folder.path, SyncMode.TWO_WAY, pick = true)) else emptyList()
                if (enabled) folder.mkdirs()
                val updated = config.copy(folders = folders)
                ApiClient(config.serverUrl, token).putSubscription(updated.subscription())
                app.settings.updateConfig(updated)
                SyncScheduler.checkNow(app, replace = true)
            } catch (e: Exception) {
                videos.value = videos.value.copy(error = e.message ?: e.javaClass.simpleName)
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

    fun unpair() {
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
