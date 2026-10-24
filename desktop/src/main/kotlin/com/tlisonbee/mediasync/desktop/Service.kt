package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.sync.ApiClient
import com.tlisonbee.mediasync.sync.ClientConfig
import com.tlisonbee.mediasync.sync.Decisions
import com.tlisonbee.mediasync.sync.Review
import com.tlisonbee.mediasync.sync.ReviewSettings
import com.tlisonbee.mediasync.sync.SyncEngine
import com.tlisonbee.mediasync.sync.SyncListener
import com.tlisonbee.mediasync.sync.SyncStage
import com.tlisonbee.mediasync.sync.SyncReport
import com.tlisonbee.mediasync.sync.bytes
import com.tlisonbee.mediasync.sync.followRenames
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Where a running job is: files and bytes, and when this stage started, for a time estimate. */
data class JobProgress(
    val stage: SyncStage,
    val done: Int,
    val total: Int,
    val bytes: Long,
    val totalBytes: Long,
    val startedAt: Long,
    val startBytes: Long,
) {
    /** 0 to 1, by bytes where they're known, else by files; null if there's nothing to measure. */
    val fraction: Double?
        get() = when {
            totalBytes > 0 -> (bytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
            total > 0 -> (done.toDouble() / total).coerceIn(0.0, 1.0)
            else -> null
        }

    /** Milliseconds left at the rate so far; null until there's a rate. */
    fun remainingMs(now: Long): Long? {
        val elapsed = now - startedAt
        val moved = bytes - startBytes
        if (totalBytes <= 0 || moved <= 0 || elapsed < 5_000) return null
        return ((totalBytes - bytes) * elapsed / moved).coerceAtLeast(0)
    }
}

/** Something for the service's one engine to do; [reply] gets the outcome when it's done. */
sealed class Job(val reply: CompletableDeferred<String>? = null) {
    class Sync(val allowDeletes: Boolean, reply: CompletableDeferred<String>? = null) : Job(reply)

    /** Scan and stage server changes for review, changing nothing; [full] compares everything again. */
    class Check(reply: CompletableDeferred<String>? = null, val full: Boolean = false) : Job(reply)

    class Apply(val decisions: Decisions, reply: CompletableDeferred<String>? = null) : Job(reply)

    fun describe() = when (this) {
        is Sync -> "Syncing"
        is Check -> "Checking for changes"
        is Apply -> "Applying your choices"
    }
}

/**
 * `mediasync-desktop run`. With review on (the default), it checks every hour or so and asks
 * before changing anything: a notification, and a review page in the browser. With review off, it
 * syncs after local changes (once they've been quiet for 10 s), every 15 minutes, whenever the
 * server announces a new revision, and when the CLI asks.
 */
class Service(
    private val dirs: Dirs,
    initialConfig: ClientConfig,
    private val token: String,
    private val makeTray: (TrayActions) -> Tray = ::desktopTray,
    private val makeNotifier: () -> Notifier = ::desktopNotifier,
) {
    /** The config, once it follows any folders the server renamed (at [run]). */
    var config: ClientConfig = initialConfig
        private set
    private val jobs = Channel<Job>(Channel.UNLIMITED)
    private val lastLocalChange = AtomicLong(0)
    private lateinit var index: Index
    private lateinit var engine: SyncEngine
    lateinit var settings: ReviewSettings
        private set

    /** What the engine is doing right now, for the review page. */
    @Volatile
    var busy: String? = null
        private set

    /** How far the current job has got, for the review page's progress bar. */
    @Volatile
    var progress: JobProgress? = null
        private set

    private val listener = object : SyncListener {
        override fun progress(stage: SyncStage, done: Int, total: Int, bytes: Long, totalBytes: Long) {
            val previous = progress?.takeIf { it.stage == stage }
            progress = JobProgress(
                stage = stage,
                done = if (done >= 0) done else previous?.done ?: 0,
                total = if (total >= 0) total else previous?.total ?: 0,
                bytes = bytes,
                totalBytes = if (totalBytes >= 0) totalBytes else previous?.totalBytes ?: 0,
                startedAt = previous?.startedAt ?: System.currentTimeMillis(),
                startBytes = previous?.startBytes ?: bytes,
            )
        }
    }

    fun submit(job: Job) = jobs.trySend(job)

    /** Ends [run] once the current job is done. */
    fun stop() = jobs.close()

    /** What waits for approval, as of the last check. */
    fun review(): Review = engine.review()

    fun lastCheck(): Long? = index.state(SyncEngine.LAST_CHECK)?.toLong()

    fun run() = runBlocking {
        val lock = acquireLock(dirs) ?: run {
            System.err.println("Another mediasync-desktop is already running.")
            return@runBlocking
        }
        index = Index(dirs.index)
        settings = ReviewSettings(index)
        val api = ApiClient(config.serverUrl, token)
        config = followServerRenames(dirs, config, token, api, index)
        engine = SyncEngine(config, api, index, dirs.pictures, listener = listener)
        val notifier = makeNotifier()
        val page = ReviewPage(this@Service).apply { start() }
        writePrivate(dirs.reviewUrl, page.url + "\n")
        println("MediaSync desktop started: ${config.folders.joinToString { "${it.folder} → ${it.prefix}" }} with ${config.serverUrl}")
        println(if (settings.enabled) "Asking before syncing; checking every ${settings.checkMinutes} minutes. Review page: mediasync-desktop review" else "Syncing automatically.")

        val watcher = Watcher(config.folders.map { it.root }) { lastLocalChange.set(System.currentTimeMillis()) }
        Thread(watcher::run, "watcher").apply { isDaemon = true }.start()

        // What to do when: checks on a timer with review on; syncs after quiet local changes and every 15 minutes without.
        launch {
            var handledLocal = 0L
            var lastSync = 0L
            var lastCheckQueued = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (settings.enabled) {
                    handledLocal = lastLocalChange.get()
                    val last = maxOf(lastCheckQueued, lastCheck() ?: 0)
                    if (now - last >= settings.checkMinutes * 60_000L) {
                        lastCheckQueued = now
                        submit(Job.Check())
                    }
                } else {
                    val last = lastLocalChange.get()
                    if (last > handledLocal && now - last >= SyncEngine.SETTLE_MS) {
                        handledLocal = last
                        submit(Job.Sync(allowDeletes = false))
                    }
                    if (now - lastSync >= PERIOD.inWholeMilliseconds) {
                        lastSync = now
                        submit(Job.Sync(allowDeletes = false))
                    }
                }
                delay(2.seconds)
            }
        }

        // The server's announcements: a hint to sync now (without review). Reconnects with a growing pause.
        launch(Dispatchers.IO) {
            var pause = 5.seconds
            while (true) {
                try {
                    api.events { head ->
                        pause = 5.seconds
                        if (!settings.enabled && head > (index.state(SyncEngine.SINCE)?.toLong() ?: 0)) submit(Job.Sync(allowDeletes = false))
                    }
                } catch (e: Exception) {
                    // The periodic sync covers the gap; the failure shows up there if it persists.
                }
                delay(pause)
                pause = (pause * 2).coerceAtMost(5.minutes)
            }
        }

        launch(Dispatchers.IO) { serveControlSocket() }

        val tracker = Tracker(index, notifier, config, api, openReview = { openInBrowser(page.url) }, apply = { submit(Job.Apply(it)) })
        val tray = makeTray(object : TrayActions {
            override fun openReview() = openInBrowser(page.url)

            override fun acceptAll(decisions: Decisions) {
                if (!decisions.isEmpty) submit(Job.Apply(decisions))
            }

            override fun checkNow() {
                submit(if (settings.enabled) Job.Check() else Job.Sync(allowDeletes = false))
            }

            override fun openWebUi() = openInBrowser(config.serverUrl)
        })
        fun refreshTray() = runCatching { tray.update(trayState(tracker.error)) }.onFailure { System.err.println("Tray: ${it.message}") }
        refreshTray()
        for (job in jobs) {
            busy = job.describe()
            refreshTray()
            val outcome = try {
                withContext(Dispatchers.IO) {
                    when (job) {
                        is Job.Sync -> engine.sync(job.allowDeletes).also { report ->
                            tracker.succeeded(report)
                            if (report.unsettled) launch { delay(SyncEngine.SETTLE_MS + 2_000); submit(Job.Sync(false)) }
                        }.summary()
                        is Job.Check -> engine.check(full = job.full).also { tracker.reviewed(it) }.summary("this PC")
                        is Job.Apply -> engine.apply(job.decisions).also { report ->
                            tracker.succeeded(report)
                            tracker.reviewed(engine.review())
                        }.summary()
                    }
                }
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                tracker.failed(message)
                "Failed: $message"
            } finally {
                busy = null
                progress = null
            }
            refreshTray()
            job.reply?.complete(outcome)
        }
        page.stop()
        tray.close()
        coroutineContext.cancelChildren()
        lock.release()
    }

    private fun trayState(error: String?): TrayState {
        val review = review()
        return TrayState(review.incomingCount + review.outgoingCount, review.summary("this PC"), busy, lastCheck(), error, settings.enabled, review.acceptAll())
    }

    /** Lets the CLI ask this process to sync or check instead of running a second one. */
    private suspend fun serveControlSocket() {
        Files.createDirectories(dirs.socket.parent)
        Files.deleteIfExists(dirs.socket)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(dirs.socket))
        while (true) {
            val client = withContext(Dispatchers.IO) { server.accept() }
            try {
                val reader = Channels.newReader(client, Charsets.UTF_8).buffered()
                val command = reader.readLine().orEmpty()
                val reply = CompletableDeferred<String>()
                jobs.send(if (command.startsWith("check")) Job.Check(reply, full = "--full" in command) else Job.Sync(allowDeletes = "--allow-deletes" in command, reply = reply))
                val text = reply.await()
                Channels.newWriter(client, Charsets.UTF_8).apply {
                    write(text + "\n")
                    flush()
                }
            } catch (e: IOException) {
                // The CLI went away; nothing to do.
            } finally {
                client.close()
            }
        }
    }

    companion object {
        val PERIOD = 15.minutes

        /** Asks a running service to sync; returns its reply, or null if no service is running. */
        fun requestSync(dirs: Dirs, allowDeletes: Boolean): String? = request(dirs, if (allowDeletes) "sync --allow-deletes" else "sync")

        /** Sends [command] ("sync", "check") to a running service; null if none is running. */
        fun request(dirs: Dirs, command: String): String? {
            if (!Files.exists(dirs.socket)) return null
            return try {
                SocketChannel.open(UnixDomainSocketAddress.of(dirs.socket)).use { channel ->
                    val writer = Channels.newWriter(channel, Charsets.UTF_8)
                    writer.write(command + "\n")
                    writer.flush()
                    Channels.newReader(channel, Charsets.UTF_8).buffered().readText().trim()
                }
            } catch (e: IOException) {
                null
            }
        }

        /** One process at a time owns the index; returns null if another one does. */
        fun acquireLock(dirs: Dirs): FileLock? {
            Files.createDirectories(dirs.state)
            val channel = FileChannel.open(dirs.lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            return channel.tryLock() ?: run {
                channel.close()
                null
            }
        }
    }
}

/** Turns sync outcomes into log lines and the notifications the design promises. */
private class Tracker(
    private val index: Index,
    private val notifier: Notifier,
    private val config: ClientConfig,
    private val api: ApiClient,
    private val openReview: () -> Unit,
    private val apply: (Decisions) -> Unit,
) {
    private var lastError: String? = null

    /** The last failure, until a sync works again. */
    val error: String? get() = lastError

    /** Says what waits for approval, once per distinct list. "Accept all" takes the list as notified, nothing newer. */
    fun reviewed(review: Review) {
        if (review.isEmpty) {
            index.setState(NOTIFIED_REVIEW, null)
            return
        }
        if (index.state(NOTIFIED_REVIEW) == review.version) return
        index.setState(NOTIFIED_REVIEW, review.version)
        println("Waiting for approval: ${review.summary("this PC")}")
        val body = review.groups.take(4).joinToString("\n") { "${it.title} (${it.items.size})" } +
            if (review.groups.size > 4) "\n…and ${review.groups.size - 4} more" else ""
        val decisions = review.acceptAll()
        notifier.show(
            "review", review.summary("this PC").replaceFirstChar { it.uppercase() }, body, onClick = openReview,
            actions = listOf(NotificationAction("Review", openReview), NotificationAction("Accept all") { apply(decisions) }),
        )
    }

    fun succeeded(report: SyncReport) {
        if (lastError != null) println("Sync working again.")
        lastError = null
        index.setState(FAILING_NOTIFIED, null)
        if (report.pushed + report.downloadedFiles + report.deleted + report.retagged + report.renamed > 0 || report.warnings.isNotEmpty()) {
            println("Synced: ${report.summary()}")
        }
        report.warnings.forEach { System.err.println("Warning: $it") }

        // Held-back deletions need a person; say so once per distinct warning.
        report.warnings.filter { "--allow-deletes" in it }.forEach { warning ->
            if (index.state("warned:" + warning.hashCode()) == null) {
                notifier.show("deletes", "MediaSync held back deletions", warning)
                index.setState("warned:" + warning.hashCode(), "1")
            }
        }

        val transferred = report.uploadedFiles + report.downloadedFiles
        if (transferred >= LARGE_FILES || report.uploadedBytes + report.downloadedBytes >= LARGE_BYTES) {
            val parts = listOfNotNull(
                report.uploadedFiles.takeIf { it > 0 }?.let { "uploaded $it files (${bytes(report.uploadedBytes)})" },
                report.downloadedFiles.takeIf { it > 0 }?.let { "downloaded $it files (${bytes(report.downloadedBytes)})" },
            )
            notifier.show("transfer", "MediaSync finished a large sync", parts.joinToString(", ").replaceFirstChar { it.uppercase() })
        }

        // New entries in the resolve queue.
        val open = runCatching { api.openIssues() }.getOrNull() ?: return
        val notified = index.state(NOTIFIED_ISSUES)?.let { ApiJson.decodeFromString(ids, it) }.orEmpty().toSet()
        val fresh = open.filter { it.id !in notified }
        if (fresh.isNotEmpty()) {
            val n = open.size
            notifier.show("issues", "$n ${if (n == 1) "file needs" else "files need"} a decision", fresh.take(3).joinToString("\n") { it.path }, onClick = {
                openInBrowser(config.serverUrl + "/resolve")
            })
        }
        index.setState(NOTIFIED_ISSUES, ApiJson.encodeToString(ids, open.map { it.id }))
    }

    fun failed(message: String) {
        // Log each distinct failure once instead of every few minutes.
        if (message != lastError) System.err.println("Sync failed: $message")
        lastError = message
        val lastSuccess = index.state(SyncEngine.LAST_SUCCESS)?.toLong() ?: return
        if (System.currentTimeMillis() - lastSuccess > FAILING_AFTER.inWholeMilliseconds && index.state(FAILING_NOTIFIED) == null) {
            val since = DateTimeFormatter.ofPattern("EEE HH:mm").format(Instant.ofEpochMilli(lastSuccess).atZone(ZoneId.systemDefault()))
            notifier.show("failing", "MediaSync hasn't synced since $since", message)
            index.setState(FAILING_NOTIFIED, "1")
        }
    }

    companion object {
        const val NOTIFIED_ISSUES = "notifiedIssues"
        const val NOTIFIED_REVIEW = "notifiedReview"
        const val FAILING_NOTIFIED = "failingNotified"
        val FAILING_AFTER = 12.hours
        const val LARGE_FILES = 25
        const val LARGE_BYTES = 1L shl 30
        val ids = ListSerializer(Long.serializer())
    }
}

/**
 * Opens a URL through the user's systemd manager, so the browser doesn't inherit this service's
 * sandbox (read-only home); falls back to a direct launch outside systemd.
 */
fun openInBrowser(url: String) {
    val viaSystemd = runCatching {
        ProcessBuilder("systemd-run", "--user", "--quiet", "--collect", "xdg-open", url).inheritIO().start().waitFor() == 0
    }.getOrDefault(false)
    if (!viaSystemd) ProcessBuilder("xdg-open", url).inheritIO().start()
}

/**
 * [config] once it follows the folders the server renamed (followRenames), saved; as it is when
 * the server can't be asked, until next time.
 */
fun followServerRenames(dirs: Dirs, config: ClientConfig, token: String, api: ApiClient, index: Index): ClientConfig {
    val renamed = try {
        followRenames(config, api, index)
    } catch (e: IOException) {
        System.err.println("Couldn't ask the server whether it renamed any folders: ${e.message}")
        null
    } ?: return config
    ConfigStore(dirs).save(renamed, token)
    val names = config.folders.zip(renamed.folders).filter { (old, new) -> old.prefix != new.prefix }
    println("The server renamed " + names.joinToString { (old, new) -> "${old.prefix} to ${new.prefix} (${new.folder})" } + "; the files stay where they are.")
    return renamed
}
