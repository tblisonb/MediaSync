package com.tlisonbee.mediasync.sync

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tlisonbee.mediasync.MediaSyncApp
import com.tlisonbee.mediasync.R
import com.tlisonbee.mediasync.app
import com.tlisonbee.mediasync.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Where a running sync is, for the status screen. */
data class Progress(val stage: SyncStage, val done: Int, val total: Int, val bytes: Long) {
    fun describe(): String = when (stage) {
        SyncStage.SCANNING -> "Reading files: ${fmt(done)} of ${fmt(total)}"
        SyncStage.PULLING -> "Checking the server"
        SyncStage.DOWNLOADING -> "Downloading: ${bytes(bytes)}"
        SyncStage.UPLOADING -> if (total > 0) "Uploading: ${fmt(done)} of ${fmt(total)} files, ${bytes(bytes)}" else "Uploading: ${bytes(bytes)}"
    }

    private fun fmt(n: Int) = "%,d".format(n)
}

object SyncState {
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    internal fun set(progress: Progress?) {
        _progress.value = progress
    }
}

/**
 * One sync, run by WorkManager as a foreground job with a progress notification, so the first
 * sync (the whole library) can take as long as it needs.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val settings = app.settings
        val config = settings.config
        val token = settings.token
        if (config == null || token == null || !settings.approved) return Result.success()
        if (!Environment.isExternalStorageManager()) {
            settings.recordError("MediaSync needs All files access to read and write Music/. Open the app to grant it.")
            return Result.failure()
        }
        // Not allowed when started from the background on recent Android; then it runs as a plain job.
        runCatching { setForeground(foregroundInfo(null)) }
            .onFailure { Log.i(TAG, "Syncing without a foreground notification: ${it.message}") }

        return lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val report = engine(config, token).sync(allowDeletes = inputData.getBoolean(ALLOW_DELETES, false))
                    settings.recordSuccess(report.summary(), report.warnings)
                    afterSync(report, ApiClient(config.serverUrl, token), config)
                    Result.success()
                } catch (e: ApiException) {
                    settings.recordError(e.message ?: "The server refused the sync")
                    if (e.status == 401) Result.failure() else Result.retry()
                } catch (e: IOException) {
                    settings.recordError("Couldn't reach the server: ${e.message ?: e.javaClass.simpleName}")
                    Result.retry()
                } finally {
                    SyncState.set(null)
                }
            }
        }
    }

    private fun engine(config: ClientConfig, token: String): SyncEngine {
        var lastShown = 0L
        val listener = object : SyncListener {
            override fun progress(stage: SyncStage, done: Int, total: Int, bytes: Long) {
                val previous = SyncState.progress.value
                val progress = Progress(
                    stage,
                    if (done >= 0) done else previous?.done ?: 0,
                    if (total >= 0) total else previous?.total ?: 0,
                    bytes,
                )
                SyncState.set(progress)
                val now = System.currentTimeMillis()
                if (now - lastShown > 1_000) {
                    lastShown = now
                    runCatching { setForegroundAsync(foregroundInfo(progress)) }
                }
            }

            /** Music players learn about tag edits, renames and deletions through MediaStore. */
            override fun filesChanged(paths: List<Path>) {
                paths.chunked(500).forEach { chunk ->
                    MediaScannerConnection.scanFile(applicationContext, chunk.map { it.toString() }.toTypedArray(), null, null)
                }
            }
        }
        val pictures = applicationContext.cacheDir.toPath().resolve("pictures")
        return SyncEngine(config, ApiClient(config.serverUrl, token), applicationContext.app.index, pictures, log = { Log.i(TAG, it) }, listener = listener,
            // Shared storage ignores case: "IRON MAIDEN/x" lands in an existing "Iron Maiden" folder.
            ignoresCase = true,
        )
    }

    /** Notifications for what needs a person: held-back deletions and new resolve-queue entries. */
    private fun afterSync(report: SyncReport, api: ApiClient, config: ClientConfig) {
        val settings = applicationContext.app.settings
        report.warnings.filter { "--allow-deletes" in it }.forEach {
            notify(ID_DELETES, "MediaSync held back deletions", "Open MediaSync to review them.", MainActivity.intent(applicationContext))
        }
        val open = runCatching { api.openIssues() }.getOrNull() ?: return
        val fresh = open.filter { it.id !in settings.notifiedIssues }
        if (fresh.isNotEmpty()) {
            val n = open.size
            val web = Intent(Intent.ACTION_VIEW, (config.serverUrl + "/resolve").toUri())
            notify(ID_ISSUES, "$n ${if (n == 1) "file needs" else "files need"} a decision", fresh.take(3).joinToString("\n") { it.path }, web)
        }
        settings.notifiedIssues = open.map { it.id }.toSet()
    }

    private fun notify(id: Int, title: String, text: String, intent: Intent) {
        val notification = NotificationCompat.Builder(applicationContext, MediaSyncApp.CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(applicationContext, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        val allowed = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(progress: Progress?): ForegroundInfo {
        val builder = NotificationCompat.Builder(applicationContext, MediaSyncApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Syncing music")
            .setContentText(progress?.describe() ?: "Starting")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(applicationContext, 0, MainActivity.intent(applicationContext), PendingIntent.FLAG_IMMUTABLE))
        if (progress != null && progress.total > 0 && progress.stage != SyncStage.PULLING) {
            builder.setProgress(progress.total, progress.done.coerceAtMost(progress.total), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification: Notification = builder.build()
        return ForegroundInfo(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "MediaSync"
        const val ALLOW_DELETES = "allowDeletes"
        private const val ID_PROGRESS = 1
        private const val ID_ISSUES = 2
        private const val ID_DELETES = 3

        /** Periodic and on-demand syncs never run the engine at the same time. */
        private val lock = Mutex()
    }
}

/** Every 15 minutes on Wi-Fi (Android's minimum), and on demand on any network. */
object SyncScheduler {
    private const val PERIODIC = "sync-periodic"
    private const val NOW = "sync-now"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context, allowDeletes: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.ALLOW_DELETES to allowDeletes))
            .build()
        // A sync already running finishes first; asking again during it doesn't restart it.
        val policy = if (allowDeletes) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(NOW)
        }
    }
}
