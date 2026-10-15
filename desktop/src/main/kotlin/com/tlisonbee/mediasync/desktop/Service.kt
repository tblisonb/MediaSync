package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.sync.ApiClient
import com.tlisonbee.mediasync.sync.ClientConfig
import com.tlisonbee.mediasync.sync.SyncEngine
import com.tlisonbee.mediasync.sync.SyncReport
import com.tlisonbee.mediasync.sync.bytes
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** A sync someone asked for; [reply] gets the outcome when it's done. */
private class SyncRequest(val allowDeletes: Boolean, val reply: CompletableDeferred<String>? = null)

/**
 * `mediasync-desktop run`: syncs after local changes (once they've been quiet for 10 s), every 15
 * minutes, whenever the server announces a new revision, and when the CLI asks.
 */
class Service(private val dirs: Dirs, private val config: ClientConfig, private val token: String) {
    private val requests = Channel<SyncRequest>(Channel.UNLIMITED)
    private val lastLocalChange = AtomicLong(0)

    fun run() = runBlocking {
        val lock = acquireLock(dirs) ?: run {
            System.err.println("Another mediasync-desktop is already running.")
            return@runBlocking
        }
        val index = Index(dirs.index)
        val api = ApiClient(config.serverUrl, token)
        val engine = SyncEngine(config, api, index, dirs.pictures)
        val notifier = desktopNotifier()
        println("MediaSync desktop started: ${config.folders.joinToString { "${it.folder} → ${it.prefix}" }} with ${config.serverUrl}")

        // Local changes: wait until the folders have been quiet for 10 s, so a save in several steps is one change.
        val watcher = Watcher(config.folders.map { it.root }) { lastLocalChange.set(System.currentTimeMillis()) }
        Thread(watcher::run, "watcher").apply { isDaemon = true }.start()
        launch {
            var handled = 0L
            while (true) {
                delay(2.seconds)
                val last = lastLocalChange.get()
                if (last > handled && System.currentTimeMillis() - last >= SyncEngine.SETTLE_MS) {
                    handled = last
                    requests.trySend(SyncRequest(allowDeletes = false))
                }
            }
        }

        // The server's announcements: a hint to sync now. Reconnects with a growing pause.
        launch(Dispatchers.IO) {
            var pause = 5.seconds
            while (true) {
                try {
                    api.events { head ->
                        pause = 5.seconds
                        if (head > (index.state(SyncEngine.SINCE)?.toLong() ?: 0)) requests.trySend(SyncRequest(allowDeletes = false))
                    }
                } catch (e: Exception) {
                    // The periodic sync covers the gap; the failure shows up there if it persists.
                }
                delay(pause)
                pause = (pause * 2).coerceAtMost(5.minutes)
            }
        }

        launch {
            while (true) {
                requests.trySend(SyncRequest(allowDeletes = false))
                delay(PERIOD)
            }
        }

        launch(Dispatchers.IO) { serveControlSocket() }

        val tracker = Tracker(index, notifier, config, api)
        for (request in requests) {
            val outcome = try {
                val report = withContext(Dispatchers.IO) { engine.sync(request.allowDeletes) }
                tracker.succeeded(report)
                if (report.unsettled) launch { delay(SyncEngine.SETTLE_MS + 2_000); requests.trySend(SyncRequest(false)) }
                report.summary()
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                tracker.failed(message)
                "Sync failed: $message"
            }
            request.reply?.complete(outcome)
        }
        lock.release()
    }

    /** Lets `mediasync-desktop sync` ask this process to sync instead of running a second one. */
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
                requests.send(SyncRequest(allowDeletes = "--allow-deletes" in command, reply = reply))
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
        fun requestSync(dirs: Dirs, allowDeletes: Boolean): String? {
            if (!Files.exists(dirs.socket)) return null
            return try {
                SocketChannel.open(UnixDomainSocketAddress.of(dirs.socket)).use { channel ->
                    val writer = Channels.newWriter(channel, Charsets.UTF_8)
                    writer.write(if (allowDeletes) "sync --allow-deletes\n" else "sync\n")
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
private class Tracker(private val index: Index, private val notifier: Notifier, private val config: ClientConfig, private val api: ApiClient) {
    private var lastError: String? = null

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
            notifier.show("issues", "$n ${if (n == 1) "file needs" else "files need"} a decision", fresh.take(3).joinToString("\n") { it.path }) {
                openInBrowser(config.serverUrl + "/resolve")
            }
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
