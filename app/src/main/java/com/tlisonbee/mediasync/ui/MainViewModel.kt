package com.tlisonbee.mediasync.ui

import android.app.Application
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tlisonbee.mediasync.api.DeviceKind
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
import com.tlisonbee.mediasync.sync.SyncEngine
import com.tlisonbee.mediasync.sync.SyncScheduler
import com.tlisonbee.mediasync.sync.SyncState
import kotlinx.coroutines.Dispatchers
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

data class UiState(
    val screen: Screen,
    val config: ClientConfig?,
    val lastSync: LastSync,
    val progress: Progress?,
    val files: Int,
    val pairing: PairState,
    val preview: PreviewState,
    val review: ReviewState = ReviewState(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    private val allFilesAccess = MutableStateFlow(Environment.isExternalStorageManager())
    private val pairing = MutableStateFlow(PairState())
    private val preview = MutableStateFlow(PreviewState())
    private val files = MutableStateFlow(0)
    private val review = MutableStateFlow(ReviewState())

    private data class Extra(val pairing: PairState, val preview: PreviewState, val files: Int, val review: ReviewState)

    val state: StateFlow<UiState> = combine(
        app.settings.changes, SyncState.progress, allFilesAccess, combine(pairing, preview, files, review, ::Extra),
    ) { _, progress, access, (pair, prev, count, rev) ->
        val settings = app.settings
        val screen = when {
            !access -> Screen.PERMISSION
            settings.config == null -> Screen.PAIR
            !settings.approved -> Screen.PREVIEW
            else -> Screen.STATUS
        }
        UiState(screen, settings.config, settings.lastSync, progress, count, pair, prev, rev)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initial())

    init {
        // A check or apply in the worker ends with a settings change: reload what waits.
        viewModelScope.launch { app.settings.changes.collect { loadReview() } }
    }

    fun loadReview() {
        val config = app.settings.config ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val settings = ReviewSettings(app.index)
            review.value = ReviewState(
                enabled = settings.enabled,
                checkMinutes = settings.checkMinutes,
                lastCheck = app.index.state(SyncEngine.LAST_CHECK)?.toLong(),
                review = SyncEngine.storedReview(config, app.index, ignoresCase = true),
            )
        }
    }

    /** A group's button. The review it acts on is the one on screen, so nothing newer slips in. */
    fun decide(group: ReviewGroup, action: ReviewAction) = queue(review.value.review.decide(group, action))

    fun acceptAll() = queue(review.value.review.acceptAll())

    private fun queue(decisions: Decisions) {
        if (decisions.isEmpty) return
        ReviewQueue.add(app, decisions)
        SyncScheduler.apply(app)
    }

    fun checkNow() = SyncScheduler.checkNow(app)

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
        SyncScheduler.syncNow(app)
    }

    fun syncNow(allowDeletes: Boolean = false) = SyncScheduler.syncNow(app, allowDeletes)

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
