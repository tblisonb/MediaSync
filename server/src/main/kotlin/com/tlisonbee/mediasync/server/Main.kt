package com.tlisonbee.mediasync.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path
import kotlin.system.exitProcess

data class Config(val repo: Path, val host: String, val port: Int) {
    companion object {
        const val DEFAULT_REPO = "/var/lib/mediasync"

        fun fromEnv(env: Map<String, String> = System.getenv()) = Config(
            repo = Path.of(env["MEDIASYNC_REPO"] ?: DEFAULT_REPO),
            // Loopback by default: only reachable through a local proxy such as `tailscale serve`.
            host = env["MEDIASYNC_HOST"] ?: "127.0.0.1",
            port = env["MEDIASYNC_PORT"]?.toInt() ?: 8766,
        )
    }
}

private val USAGE = """
    Usage: mediasync-server [command] [options] [--repo DIR]

    Commands:
      serve                 Run the server (the default)
      setup                 Create the repository, or change its admin password
      pair-code             Print a one-time code for pairing a device
      fsck [--full]         Check the repository; --full also re-hashes every blob
      prune --older-than D  Drop history older than D (e.g. 90d, 1y) and the blobs nothing uses
      snapshot              Write a consistent copy of repo.db to snapshots/, for backups

    The repository folder is --repo, else MEDIASYNC_REPO, else ${Config.DEFAULT_REPO}.
""".trimIndent()

fun main(args: Array<String>) {
    val command = args.firstOrNull()?.takeUnless { it.startsWith("-") } ?: "serve"
    if ("-h" in args || "--help" in args) {
        println(USAGE)
        return
    }
    val config = Config.fromEnv().let { c -> option(args, "--repo")?.let { c.copy(repo = Path.of(it)) } ?: c }
    val dir = RepoDir(config.repo)
    val code = try {
        when (command) {
            "serve" -> serve(config, dir)
            "setup" -> Setup.interactive(dir)
            "pair-code" -> withRepo(dir) { println(it.newPairingCode()); 0 }
            "fsck" -> withRepo(dir) { Maintenance.fsck(it, full = "--full" in args) }
            "prune" -> withRepo(dir) { Maintenance.prune(it, option(args, "--older-than") ?: return@withRepo usage()) }
            "snapshot" -> withRepo(dir) { Maintenance.snapshot(it); 0 }
            else -> usage()
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        2
    }
    exitProcess(code)
}

private fun usage(): Int {
    System.err.println(USAGE)
    return 2
}

private fun option(args: Array<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun withRepo(dir: RepoDir, block: (Repository) -> Int): Int {
    require(dir.isSetUp) { "No repository at ${dir.root}. Run: mediasync-server setup --repo ${dir.root}" }
    return Repository(dir).use(block)
}

private fun serve(config: Config, dir: RepoDir): Int {
    require(dir.isSetUp) { "No repository at ${dir.root}. Run: mediasync-server setup --repo ${dir.root}" }
    val repo = Repository(dir)
    repo.startup()
    embeddedServer(Netty, host = config.host, port = config.port) {
        mediaSyncModule(repo)
    }.start(wait = true)
    return 0
}
