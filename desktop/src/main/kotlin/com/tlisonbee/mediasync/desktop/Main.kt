package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.sync.ApiClient
import com.tlisonbee.mediasync.sync.ApiException
import com.tlisonbee.mediasync.sync.ClientConfig
import com.tlisonbee.mediasync.sync.FolderConfig
import com.tlisonbee.mediasync.sync.Preview
import com.tlisonbee.mediasync.sync.ReviewSettings
import com.tlisonbee.mediasync.sync.SyncEngine
import com.tlisonbee.mediasync.sync.label
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private const val USAGE = """Usage: mediasync-desktop <command>

  setup                  Pair this PC with your MediaSync server and choose the music folder
  preview                Show what the first sync will do to your files, without changing them
  run                    Run the sync service (systemd starts this for you)
  status                 Show the server, the folders and what is waiting to sync
  review                 Open the page where you approve or undo changes waiting to sync
  check [--full]         Look for changes now and say what waits (through the running service);
                         --full compares every file with the server again
  sync [--allow-deletes] Sync everything now, without asking (through the running service, if there is one)
  log [count]            The revision log, newest first
  diff <rev>             What one revision changed, field by field
  revert <rev>           Undo a revision (as a new revision)
  open [resolve]         Open the web UI, or its resolve queue, in the browser
"""

fun main(args: Array<String>) {
    val dirs = Dirs.xdg()
    try {
        when (args.firstOrNull()) {
            "setup" -> setup(dirs)
            "preview" -> preview(dirs)
            "run" -> withConfig(dirs) { config, token -> Service(dirs, config, token).run() }
            "status" -> status(dirs)
            "sync" -> sync(dirs, allowDeletes = "--allow-deletes" in args)
            "review" -> review(dirs)
            "check" -> println(Service.request(dirs, if ("--full" in args) "check --full" else "check") ?: fail("The service isn't running: systemctl --user start mediasync-desktop"))
            "log" -> log(dirs, args.getOrNull(1)?.toIntOrNull() ?: 20)
            "diff" -> diff(dirs, args.getOrNull(1)?.toLongOrNull() ?: usage())
            "revert" -> revert(dirs, args.getOrNull(1)?.toLongOrNull() ?: usage())
            "open" -> withConfig(dirs) { config, _ -> openInBrowser(config.serverUrl + if (args.getOrNull(1) == "resolve") "/resolve" else "/") }
            "-h", "--help", "help", null -> print(USAGE)
            else -> usage()
        }
    } catch (e: ApiException) {
        System.err.println(e.message)
        exitProcess(1)
    } catch (e: java.io.IOException) {
        System.err.println("Can't reach the server: ${e.message ?: e.javaClass.simpleName}")
        exitProcess(1)
    }
}

private fun usage(): Nothing {
    System.err.print(USAGE)
    exitProcess(2)
}

private fun <T> withConfig(dirs: Dirs, block: (ClientConfig, String) -> T): T {
    val (config, token) = ConfigStore(dirs).load() ?: run {
        System.err.println("Not set up yet; run `mediasync-desktop setup` first.")
        exitProcess(1)
    }
    return block(config, token)
}

private fun ask(prompt: String, default: String? = null): String {
    print(if (default != null) "$prompt [$default]: " else "$prompt: ")
    System.out.flush()
    val answer = readlnOrNull()?.trim() ?: exitProcess(1)
    return answer.ifEmpty { default.orEmpty() }
}

private fun expandHome(path: String): Path =
    Path.of(if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path).toAbsolutePath().normalize()

private fun setup(dirs: Dirs) {
    val previous = ConfigStore(dirs).load()?.first
    println("Connecting this PC to your MediaSync server.")
    val url = normalizeUrl(ask("Server URL, e.g. https://my-pc.tailnet.ts.net:8443", previous?.serverUrl))
        ?: fail("That isn't a valid server URL. Use https://, or http:// only for this PC itself (127.0.0.1).")
    print("Checking the server… ")
    ApiClient(url, null).health()
    println("OK.")

    val code = ask("Pairing code (web UI > Devices > Pair a device)")
    val name = ask("Name for this PC", previous?.deviceName ?: InetAddress.getLocalHost().hostName)
    val music = expandHome(ask("Music folder", previous?.folders?.firstOrNull { it.prefix == RepoPaths.MUSIC }?.folder ?: "~/Music"))
    if (!Files.isDirectory(music)) {
        if (ask("$music doesn't exist. Create it? [Y/n]", "y").lowercase().startsWith("n")) exitProcess(1)
        Files.createDirectories(music)
    }
    val mode = when (ask("Sync mode: two-way, receive-only or send-only", "two-way")) {
        "two-way" -> SyncMode.TWO_WAY
        "receive-only" -> SyncMode.RECEIVE_ONLY
        "send-only" -> SyncMode.SEND_ONLY
        else -> fail("Choose two-way, receive-only or send-only.")
    }

    val pairing = ApiClient(url, null).pair(PairRequest(code, name, DeviceKind.DESKTOP))
    val config = ClientConfig(url, pairing.deviceId, name, listOf(FolderConfig(RepoPaths.MUSIC, music.toString(), mode)))
    ApiClient(url, pairing.token).putSubscription(config.subscription())
    ConfigStore(dirs).save(config, pairing.token)
    // A new pairing is a new device: what the old one synced is no base for this one.
    Index(dirs.index).use { it.clear() }
    writeSandboxDropIn(config)
    println()
    println("Paired as \"$name\"; $music syncs with music/ (${mode.label}).")
    println("Photos and videos come with a later milestone.")
}

/** The service may write only to its state and to the synced folders; tell systemd which those are. */
private fun writeSandboxDropIn(config: ClientConfig) {
    val unitDir = Path.of(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"), "systemd/user/mediasync-desktop.service.d")
    Files.createDirectories(unitDir)
    val paths = config.folders.joinToString(" ") { "\"${it.folder}\"" }
    Files.writeString(unitDir.resolve("folders.conf"), "# Written by `mediasync-desktop setup`.\n[Service]\nReadWritePaths=$paths\n")
    runCatching {
        ProcessBuilder("systemctl", "--user", "daemon-reload").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
    }
}

private fun normalizeUrl(text: String): String? {
    val uri = runCatching { URI(text.trim().trimEnd('/')) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    return when {
        uri.scheme == "https" -> uri.toString()
        uri.scheme == "http" && host in setOf("127.0.0.1", "localhost", "::1") -> uri.toString()
        else -> null
    }
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

private fun preview(dirs: Dirs) = withConfig(dirs) { config, token ->
    val policy = ApiClient(config.serverUrl, token).policy()
    config.folders.forEach { println("Reading ${it.folder}…") }
    val result = Preview.compute(config, policy)
    for (folder in result.folders) {
        println("${folder.folder} → ${folder.prefix}")
        folder.examples.forEach { (from, to) -> println("  $from  →  $to") }
        folder.describe().forEach { println("  $it") }
    }
    val report = dirs.state.resolve("preview.tsv")
    Files.createDirectories(report.parent)
    Files.write(report, result.lines)
    println()
    println("Every file, one per line: $report")
    println("Files the server already has are matched up, so nothing is uploaded twice. Nothing has been changed yet.")
}

private fun sync(dirs: Dirs, allowDeletes: Boolean) = withConfig(dirs) { config, token ->
    Service.requestSync(dirs, allowDeletes)?.let {
        println(it)
        return@withConfig
    }
    val lock = Service.acquireLock(dirs) ?: fail("Another mediasync-desktop holds the index and doesn't answer; try again shortly.")
    Index(dirs.index).use { index ->
        val report = SyncEngine(config, ApiClient(config.serverUrl, token), index, dirs.pictures).sync(allowDeletes)
        report.warnings.forEach { System.err.println("Warning: $it") }
        println(report.summary())
    }
    lock.release()
}

private fun review(dirs: Dirs) {
    val url = runCatching { Files.readString(dirs.reviewUrl).trim() }.getOrNull()
    if (url == null || !Files.exists(dirs.socket)) fail("The service isn't running: systemctl --user start mediasync-desktop")
    openInBrowser(url)
}

private fun status(dirs: Dirs) = withConfig(dirs) { config, token ->
    println("Server: ${config.serverUrl} (this PC is \"${config.deviceName}\")")
    val running = Files.exists(dirs.socket)
    println("Service: ${if (running) "running" else "not running (systemctl --user start mediasync-desktop)"}")
    Index(dirs.index).use { index ->
        val rows = index.all()
        for (folder in config.folders) {
            val inFolder = rows.filter { it.prefix == folder.prefix }
            val waiting = inFolder.count { it.changed && it.ignored == null }
            println("${folder.folder} → ${folder.prefix} (${folder.mode.label}): ${inFolder.count { !it.missing }} files, $waiting waiting to sync")
            inFolder.filter { it.ignored != null }.forEach { println("  left alone: ${it.path} (${it.ignored})") }
        }
        val since = index.state(SyncEngine.SINCE)
        val last = index.state(SyncEngine.LAST_SUCCESS)?.toLong()
        println("Synced up to: ${since?.let { "r$it" } ?: "never"}" + (last?.let { ", last sync ${time(it)}" } ?: ""))
        val settings = ReviewSettings(index)
        if (settings.enabled) {
            val review = SyncEngine(config, ApiClient(config.serverUrl, token), index, dirs.pictures).review()
            println("Asks before syncing, checking ${ReviewSettings.describe(settings.checkMinutes)}: ${review.summary("this PC")} (mediasync-desktop review)")
        } else {
            println("Syncs automatically, without asking")
        }
    }
    runCatching { ApiClient(config.serverUrl, token).openIssues() }.getOrNull()?.let {
        if (it.isNotEmpty()) println("${it.size} ${if (it.size == 1) "entry waits" else "entries wait"} in the resolve queue: mediasync-desktop open resolve")
    }
}

private fun log(dirs: Dirs, count: Int) = withConfig(dirs) { config, token ->
    ApiClient(config.serverUrl, token).revisions(limit = count).revisions.forEach {
        println("r${it.rev} · ${it.deviceName} · ${time(it.createdAt)} · ${it.message}")
    }
}

private fun diff(dirs: Dirs, rev: Long) = withConfig(dirs) { config, token ->
    val diff = ApiClient(config.serverUrl, token).diff(rev)
    val r = diff.revision
    println("r${r.rev} · ${r.deviceName} · ${time(r.createdAt)} · ${r.message}")
    for (asset in diff.assets) {
        println()
        println("${asset.path} (${asset.changes.joinToString { it.name.lowercase() }})")
        for (field in asset.fields) {
            println("  ${field.field}: ${field.old?.joinToString("; ") ?: "(none)"} → ${field.new?.joinToString("; ") ?: "(none)"}")
        }
    }
}

private fun revert(dirs: Dirs, rev: Long) = withConfig(dirs) { config, token ->
    val result = ApiClient(config.serverUrl, token).revert(RevertRequest(rev = rev))
    println(result.rev?.let { "Reverted r$rev as r$it." } ?: "Nothing to revert.")
    result.skipped.forEach { println("  skipped ${it.field} of ${it.assetId}: it changed again after r$rev") }
    Service.requestSync(dirs, allowDeletes = false)?.let(::println)
}

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun time(millis: Long) = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
