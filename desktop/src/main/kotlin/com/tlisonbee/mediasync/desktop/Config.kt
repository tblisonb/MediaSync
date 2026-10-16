package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.sync.ClientConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText

/** XDG locations: config in ~/.config/mediasync, the local index in ~/.local/state/mediasync. */
class Dirs(val config: Path, val state: Path, val cache: Path, val runtime: Path) {
    val configFile: Path = config.resolve("desktop.json")
    val token: Path = config.resolve("token")
    val index: Path = state.resolve("index.db")
    val lock: Path = state.resolve("lock")
    val pictures: Path = cache.resolve("pictures")
    val socket: Path = runtime.resolve("desktop.sock")

    /** The running service's review page, with its secret; readable only by this user. */
    val reviewUrl: Path = runtime.resolve("review-url")

    companion object {
        fun xdg(env: Map<String, String> = System.getenv()): Dirs {
            val home = System.getProperty("user.home")
            return Dirs(
                config = Path.of(env["XDG_CONFIG_HOME"] ?: "$home/.config", "mediasync"),
                state = Path.of(env["XDG_STATE_HOME"] ?: "$home/.local/state", "mediasync"),
                cache = Path.of(env["XDG_CACHE_HOME"] ?: "$home/.cache", "mediasync"),
                runtime = Path.of(env["XDG_RUNTIME_DIR"] ?: "/tmp/mediasync-${System.getProperty("user.name")}", "mediasync"),
            )
        }
    }
}

class ConfigStore(private val dirs: Dirs) {
    fun load(): Pair<ClientConfig, String>? {
        if (!dirs.configFile.exists() || !dirs.token.exists()) return null
        val config = ApiJson.decodeFromString(ClientConfig.serializer(), dirs.configFile.readText())
        return config to dirs.token.readText().trim()
    }

    fun save(config: ClientConfig, token: String) {
        writePrivate(dirs.configFile, prettyJson.encodeToString(ClientConfig.serializer(), config) + "\n")
        writePrivate(dirs.token, token + "\n")
    }

    private companion object {
        val prettyJson = kotlinx.serialization.json.Json(ApiJson) { prettyPrint = true }
    }
}

/** Writes atomically (temp file + rename), readable only by the owner. */
fun writePrivate(path: Path, content: String) {
    Files.createDirectories(path.parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
    try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(tmp, content)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(tmp)
    }
}
