package com.tlisonbee.mediasync.sync

import com.tlisonbee.mediasync.library.Subscription
import com.tlisonbee.mediasync.library.SubscriptionRule
import com.tlisonbee.mediasync.library.SyncMode
import kotlinx.serialization.Serializable
import java.nio.file.Path

/** One synced folder: a repository prefix kept in a local folder. */
@Serializable
data class FolderConfig(
    val prefix: String,
    val folder: String,
    val mode: SyncMode = SyncMode.TWO_WAY,
    val filter: List<String> = emptyList(),
    val prefer: List<String> = emptyList(),
    /** Only what is picked for this device (in the web UI or the app), as with videos on a phone. */
    val pick: Boolean = false,
) {
    val root: Path get() = Path.of(folder)

    fun rule() = SubscriptionRule(prefix, folder, mode, filter, prefer, pick)
}

/** A client's connection to the server and its synced folders, on the desktop and on Android alike. */
@Serializable
data class ClientConfig(
    val serverUrl: String,
    val deviceId: String,
    val deviceName: String,
    val folders: List<FolderConfig>,
) {
    fun subscription() = Subscription(folders.map { it.rule() })

    /** The folder rule a repository path belongs to: the longest matching prefix. */
    fun folderFor(repoPath: String): FolderConfig? =
        folders.filter { repoPath.startsWith(it.prefix) }.maxByOrNull { it.prefix.length }

    /**
     * The folders renamed on the server, old prefix to new folder: a rule the server no longer
     * has, where it has one for the same local folder under a prefix this config lacks. That's how
     * a move of part of the library (photos/ to images/photos/) reaches the devices.
     */
    fun renamedIn(server: Subscription): Map<String, FolderConfig> {
        val prefixes = folders.map { it.prefix }.toSet()
        val unknown = server.rules.filter { it.prefix !in prefixes }
        if (unknown.isEmpty()) return emptyMap()
        val renamed = LinkedHashMap<String, FolderConfig>()
        for (folder in folders) {
            if (server.rules.any { it.prefix == folder.prefix }) continue
            val rule = unknown.singleOrNull { it.localFolder == folder.folder } ?: continue
            renamed[folder.prefix] = FolderConfig(rule.prefix, folder.folder, rule.mode, rule.filter, rule.prefer, rule.pick)
        }
        return renamed
    }

    /** This config with [renamed] (from [renamedIn]) applied. */
    fun renaming(renamed: Map<String, FolderConfig>) = copy(folders = folders.map { renamed[it.prefix] ?: it })
}

val SyncMode.label get() = name.lowercase().replace('_', '-')
