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
) {
    val root: Path get() = Path.of(folder)

    fun rule() = SubscriptionRule(prefix, folder, mode, filter, prefer)
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
}

val SyncMode.label get() = name.lowercase().replace('_', '-')
