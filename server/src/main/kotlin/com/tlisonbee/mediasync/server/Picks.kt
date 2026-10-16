package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PickItem
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.tags.TagField

/**
 * What a device holds: everything its subscription covers, except under pick rules, where only
 * the videos picked for it (and their subtitles and posters) count.
 */
class Scope(private val repo: Repository, private val device: Device) {
    private val picked: Set<String> by lazy { repo.picks(device.id) }

    /** Where the picked videos are now, by folder: a sidecar is a file there named after one. */
    private val pickedByFolder: Map<String, List<String>> by lazy {
        picked.mapNotNull { repo.headVersion(it)?.takeIf { v -> !v.deleted }?.path }.groupBy { RepoPaths.parent(it) }
    }

    fun holds(v: Version): Boolean {
        val rules = device.subscription.rules.filter { it.mode != SyncMode.SEND_ONLY && it.covers(v.path, v.kind) }
        if (rules.isEmpty()) return false
        if (rules.any { !it.pick }) return true
        if (v.assetId in picked) return true
        val name = RepoPaths.name(v.path)
        return v.kind != AssetKind.VIDEO &&
            pickedByFolder[RepoPaths.parent(v.path)].orEmpty().any { sidecarSuffix(RepoPaths.name(it), name) != null }
    }
}

fun Repository.picks(deviceId: String): Set<String> =
    db.query("SELECT asset_id FROM pick WHERE device_id = ?", deviceId) { it.getString(1) }.toSet()

/**
 * Picks and unpicks videos for a device. The device's next listing is a full one, so it fetches
 * what it now keeps and removes what it doesn't. Returns every asset whose place on the device
 * changed: the videos and their subtitles and posters.
 */
fun Repository.setPicks(deviceId: String, pick: Collection<String>, unpick: Collection<String>): List<String> = db.tx {
    val before = picks(deviceId)
    pick.filter { it !in before }.forEach { db.exec("INSERT OR IGNORE INTO pick (device_id, asset_id) VALUES (?, ?)", deviceId, it) }
    unpick.filter { it in before }.forEach { db.exec("DELETE FROM pick WHERE device_id = ? AND asset_id = ?", deviceId, it) }
    val changed = (pick.filter { it !in before } + unpick.filter { it in before }).distinct()
    if (changed.isNotEmpty()) db.exec("UPDATE device SET rescan = 1 WHERE id = ?", deviceId)
    changed.flatMap { id -> listOf(id) + (headVersion(id)?.let { sidecarsOf(it).map { s -> s.first.assetId } }.orEmpty()) }
}

/** The videos under [prefix], each with whether it's picked for [deviceId]. */
fun Repository.pickList(deviceId: String, prefix: String): List<PickItem> = db.read {
    val picked = picks(deviceId)
    val key = Repository.pathKey(prefix.trimEnd('/'))
    db.query(
        """
        SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.deleted = 0 AND v.kind = ? AND h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
        """.trimIndent(),
        AssetKind.VIDEO.apiName, "$key/", "${key}0",
    ) { it.toVersion() }.map { v ->
        val size = v.renditions.firstOrNull()?.let { media(it)?.size } ?: 0
        PickItem(v.assetId, v.path, v.category, videoLabel(v), size, v.assetId in picked)
    }
}

/** "Inception (2010)", "Fringe S01E02 · The Same Old Story", or the file name. */
fun videoLabel(v: Version): String {
    fun f(name: String) = v.tags[name]?.firstOrNull()
    val title = f(TagField.TITLE)
    return when (v.category) {
        RepoPaths.MOVIES -> (title ?: RepoPaths.name(v.path)) + (f(TagField.YEAR)?.let { " ($it)" } ?: "")
        RepoPaths.SHOWS -> {
            val code = listOfNotNull(f(TagField.SEASON)?.let { "S%02d".format(it.toIntOrNull() ?: 0) }, f(TagField.EPISODE)?.let { "E%02d".format(it.toIntOrNull() ?: 0) }).joinToString("")
            listOfNotNull(f(TagField.SHOW), code.ifEmpty { null }).joinToString(" ") + (title?.let { " · $it" } ?: "")
        }
        else -> title ?: RepoPaths.name(v.path)
    }
}
