package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.sync.ApiClient
import com.tlisonbee.mediasync.sync.ApiException
import com.tlisonbee.mediasync.sync.ClientConfig
import com.tlisonbee.mediasync.sync.ClientInfo
import com.tlisonbee.mediasync.sync.compareVersions
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
  add-folder <prefix> <folder> [--mode two-way|receive-only|send-only] [--pick] [--keep-deleted]
                         Sync another folder, e.g. add-folder videos/ ~/Videos or games/ ~/Games;
                         --pick keeps only what you pick for this PC in the web UI;
                         --keep-deleted (send-only) keeps files in the library when deleted here
  move-folder <prefix> <folder>
                         Move a synced folder's files somewhere else and sync them there,
                         e.g. move-folder games/ /mnt/other/media/games
  remove-folder <prefix> Stop syncing a folder here; its files stay where they are, and on the server
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
  version                This program's version, and the server's
"""

/** From the jar's manifest; null when not run from a built jar. */
private val VERSION: String? = object {}.javaClass.`package`?.implementationVersion

/**
 * The oldest server this client works with. Raise it whenever the client starts relying on
 * something newer. A sync with an older server stops and says so, and scripts/release.sh puts it
 * in the release notes.
 */
const val MIN_SERVER = "1.0.0"

fun main(args: Array<String>) {
    val dirs = Dirs.xdg()
    ClientInfo.kind = "desktop"
    ClientInfo.version = VERSION
    ClientInfo.minServer = MIN_SERVER
    try {
        when (args.firstOrNull()) {
            "setup" -> setup(dirs)
            "add-folder" -> addFolder(dirs, args.drop(1))
            "move-folder" -> moveFolder(dirs, args.drop(1))
            "remove-folder" -> removeFolder(dirs, args.drop(1))
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
            "version", "--version" -> version(dirs)
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
    println("Sync your pictures and videos too with: mediasync-desktop add-folder images/ ~/Pictures (or images/photos/ for just the photos, videos/ ~/Videos)")
}

/** The service may write only to its state and to the synced folders; tell systemd which those are. */
private fun writeSandboxDropIn(config: ClientConfig) {
    val unitDir = Path.of(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"), "systemd/user/mediasync-desktop.service.d")
    Files.createDirectories(unitDir)
    // "-": a folder that isn't there (an unmounted drive) mustn't stop the service starting; the
    // sync skips it until it's back.
    val paths = config.folders.joinToString(" ") { "\"-${it.folder}\"" }
    Files.writeString(unitDir.resolve("folders.conf"), "# Written by `mediasync-desktop setup`.\n[Service]\nReadWritePaths=$paths\n")
    runCatching {
        ProcessBuilder("systemctl", "--user", "daemon-reload").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
    }
}

private fun addFolder(dirs: Dirs, args: List<String>) = withConfig(dirs) { config, token ->
    val positional = args.filterIndexed { i, a -> !a.startsWith("--") && args.getOrNull(i - 1) != "--mode" }
    val prefix = positional.getOrNull(0)?.let { if (it.endsWith("/")) it else "$it/" } ?: usage()
    val folder = expandHome(positional.getOrNull(1) ?: usage())
    val mode = when (args.getOrNull(args.indexOf("--mode") + 1).takeIf { "--mode" in args } ?: "two-way") {
        "two-way" -> SyncMode.TWO_WAY
        "receive-only" -> SyncMode.RECEIVE_ONLY
        "send-only" -> SyncMode.SEND_ONLY
        else -> fail("Choose two-way, receive-only or send-only.")
    }
    val keepDeleted = "--keep-deleted" in args
    if (keepDeleted && mode != SyncMode.SEND_ONLY) fail("--keep-deleted is for send-only folders: one that receives would get the files back.")
    if (config.folders.any { it.prefix == prefix }) fail("$prefix is already synced, to ${config.folders.first { it.prefix == prefix }.folder}.")
    config.folders.firstOrNull { folder.startsWith(it.root) || it.root.startsWith(folder) }?.let {
        fail("$folder overlaps ${it.folder}, which is already synced.")
    }
    if (!Files.isDirectory(folder)) {
        if (ask("$folder doesn't exist. Create it? [Y/n]", "y").lowercase().startsWith("n")) exitProcess(1)
        Files.createDirectories(folder)
    }
    val updated = config.copy(folders = config.folders + FolderConfig(prefix, folder.toString(), mode, pick = "--pick" in args, keepDeleted = keepDeleted))
    ApiClient(config.serverUrl, token).putSubscription(updated.subscription())
    ConfigStore(dirs).save(updated, token)
    writeSandboxDropIn(updated)
    val notes = listOfNotNull(mode.label, "only what you pick".takeIf { "--pick" in args }, "kept in the library when deleted here".takeIf { keepDeleted })
    println("$folder now syncs with $prefix (${notes.joinToString(", ")}).")
    if (Files.exists(dirs.socket)) {
        runCatching { ProcessBuilder("systemctl", "--user", "restart", "mediasync-desktop").inheritIO().start().waitFor() }
        println("The service restarted with it; its next check lists what the folder holds (mediasync-desktop check).")
    }
}

private fun moveFolder(dirs: Dirs, args: List<String>) = withConfig(dirs) { config, token ->
    val prefix = args.getOrNull(0)?.let { if (it.endsWith("/")) it else "$it/" } ?: usage()
    val to = expandHome(args.getOrNull(1) ?: usage())
    val folder = config.folders.firstOrNull { it.prefix == prefix }
        ?: fail("$prefix isn't synced here; these are: ${config.folders.joinToString { it.prefix }}.")
    if (to == folder.root) fail("$prefix already syncs with $to.")
    if (to.startsWith(folder.root) || folder.root.startsWith(to)) fail("$to and ${folder.folder} are inside one another; choose a folder beside it.")
    config.folders.firstOrNull { it != folder && (to.startsWith(it.root) || it.root.startsWith(to)) }?.let {
        fail("$to overlaps ${it.folder}, which syncs with ${it.prefix}.")
    }
    Index(dirs.index).use { index -> FolderMove(folder, to).problem(index) }?.let(::fail)

    // The service mustn't look at the folder while its files are on their way.
    val wasRunning = systemctl("is-active", "--quiet", "mediasync-desktop") == 0
    if (wasRunning) {
        println("Stopping the sync service while the files move…")
        systemctl("stop", "mediasync-desktop")
    }
    val lock = Service.acquireLock(dirs) ?: fail("Another mediasync-desktop holds the index; stop it first (systemctl --user stop mediasync-desktop).")
    val moved = Index(dirs.index).use { index ->
        // Listed again now that nothing else writes to the folder.
        val move = FolderMove(folder, to)
        move.problem(index)?.let {
            if (wasRunning) systemctl("start", "mediasync-desktop")
            fail(it)
        }
        if (move.files.isEmpty()) {
            println("${folder.folder} is empty or gone; its files are taken to be in $to already.")
        } else {
            println("Moving ${move.files.size} files (${FolderMove.size(move.bytes)}) from ${folder.folder} to $to…")
        }
        try {
            move.run(index) { files, bytes ->
                print("\r  ${files}/${move.files.size} files, ${FolderMove.size(bytes)} of ${FolderMove.size(move.bytes)}")
                System.out.flush()
            }.also { if (move.files.isNotEmpty()) println() }
        } catch (e: java.io.IOException) {
            println()
            fail(
                "The move stopped: ${e.message ?: e.javaClass.simpleName}\nWhat moved is synced from $to already; " +
                    "run the same command again to move the rest. The sync service stays stopped until then.",
            )
        }
    }
    val updated = config.copy(folders = config.folders.map { if (it == folder) moved else it })
    ConfigStore(dirs).save(updated, token)
    writeSandboxDropIn(updated)
    lock.release()
    runCatching { ApiClient(config.serverUrl, token).putSubscription(updated.subscription()) }
        .onFailure { System.err.println("Warning: couldn't tell the server (${it.message}); it learns the new folder at the next sync.") }
    println("$to now syncs with $prefix (${folder.mode.label}).")
    if (wasRunning) {
        systemctl("start", "mediasync-desktop")
        println("The sync service started again.")
    }
}

private fun removeFolder(dirs: Dirs, args: List<String>) = withConfig(dirs) { config, token ->
    val prefix = args.getOrNull(0)?.let { if (it.endsWith("/")) it else "$it/" } ?: usage()
    val folder = config.folders.firstOrNull { it.prefix == prefix }
        ?: fail("$prefix isn't synced here; these are: ${config.folders.joinToString { it.prefix }}.")
    if (config.folders.size == 1) fail("$prefix is the only folder synced here; pair again with `mediasync-desktop setup` to start over.")
    val updated = config.copy(folders = config.folders - folder)

    // The service mustn't sync the folder halfway through being forgotten.
    val wasRunning = systemctl("is-active", "--quiet", "mediasync-desktop") == 0
    if (wasRunning) systemctl("stop", "mediasync-desktop")
    val lock = Service.acquireLock(dirs) ?: run {
        if (wasRunning) systemctl("start", "mediasync-desktop")
        fail("Another mediasync-desktop holds the index; stop it first (systemctl --user stop mediasync-desktop).")
    }
    try {
        // The server first: if it can't be told, nothing changes here either.
        ApiClient(config.serverUrl, token).putSubscription(updated.subscription())
        Index(dirs.index).use { it.forget(prefix, updated) }
        ConfigStore(dirs).save(updated, token)
        writeSandboxDropIn(updated)
    } finally {
        lock.release()
        if (wasRunning) systemctl("start", "mediasync-desktop")
    }
    println("${folder.folder} no longer syncs with $prefix. Its files are left as they are, here and on the server.")
}

private fun systemctl(vararg args: String): Int = runCatching {
    ProcessBuilder(listOf("systemctl", "--user") + args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.INHERIT).start().waitFor()
}.getOrDefault(-1)

private fun version(dirs: Dirs) {
    println("mediasync-desktop ${VERSION ?: "(a development build)"}, needs server $MIN_SERVER or later")
    val config = ConfigStore(dirs).load()?.first ?: return
    val server = runCatching { ApiClient(config.serverUrl, null).serverVersion() }
    println(
        "Server at ${config.serverUrl}: " + server.fold(
            { v ->
                when {
                    v == null -> "0.4.3 or older (it doesn't say)"
                    compareVersions(v, MIN_SERVER) < 0 -> "$v, too old for this client: update the server"
                    else -> v
                }
            },
            { "not reachable (${it.message ?: it.javaClass.simpleName})" },
        ),
    )
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
        val api = ApiClient(config.serverUrl, token)
        val report = SyncEngine(followServerRenames(dirs, config, token, api, index), api, index, dirs.pictures).sync(allowDeletes)
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
            if (!Files.isDirectory(folder.root)) {
                println("${folder.folder} → ${folder.prefix} (${folder.mode.label}): not there, so it isn't synced until it's back")
                continue
            }
            val mode = folder.mode.label + if (folder.keepsDeleted) ", kept in the library when deleted here" else ""
            println("${folder.folder} → ${folder.prefix} ($mode): ${inFolder.count { !it.missing }} files, $waiting waiting to sync")
            inFolder.filter { it.ignored != null }.forEach { println("  left alone: ${it.path} (${it.ignoredReason})") }
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
