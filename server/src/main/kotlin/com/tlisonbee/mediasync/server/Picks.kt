package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.FolderPickItem
import com.tlisonbee.mediasync.api.PickItem
import com.tlisonbee.mediasync.library.Platforms
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.tags.TagField

/**
 * What a device holds: everything its subscription covers, except under pick rules, where only
 * what is picked for it counts: videos (with their subtitles and posters), games (with every file
 * in their folder), and folders kept whole (a platform's games).
 */
class Scope(private val repo: Repository, private val device: Device) {
    private val picked: Set<String> by lazy { repo.picks(device.id) }
    private val folders: Set<String> by lazy { repo.folderPicks(device.id) }

    /** Where the picked videos and games are now, by folder: a sidecar is a file there named after one. */
    private val pickedByFolder: Map<String, List<String>> by lazy {
        picked.mapNotNull { repo.headVersion(it)?.takeIf { v -> !v.deleted }?.path }.groupBy { RepoPaths.parent(it) }
    }

    /** The game folders with a picked file in them: the game's other files come too. */
    private val pickedGames: Set<String> by lazy { pickedByFolder.values.flatten().mapNotNull(RepoPaths::gameFolder).toSet() }

    fun holds(v: Version): Boolean {
        val rules = device.subscription.rules.filter { it.mode != SyncMode.SEND_ONLY && it.covers(v.path, v.kind) }
        if (rules.isEmpty()) return false
        if (rules.any { !it.pick }) return true
        if (v.assetId in picked) return true
        if (folders.any { v.path.startsWith("$it/") }) return true
        if (v.kind == AssetKind.GAME) return RepoPaths.gameFolder(v.path) in pickedGames
        val name = RepoPaths.name(v.path)
        return v.kind != AssetKind.VIDEO &&
            pickedByFolder[RepoPaths.parent(v.path)].orEmpty().any { sidecarSuffix(RepoPaths.name(it), name) != null }
    }
}

fun Repository.picks(deviceId: String): Set<String> =
    db.query("SELECT asset_id FROM pick WHERE device_id = ?", deviceId) { it.getString(1) }.toSet()

fun Repository.folderPicks(deviceId: String): Set<String> =
    db.query("SELECT folder FROM pick_folder WHERE device_id = ?", deviceId) { it.getString(1) }.toSet()

/**
 * The folder a game file is listed under for picking: its game's, or for a file not (yet) filed
 * in one, the folder it's in.
 */
private fun pickFolderOf(v: Version) = RepoPaths.gameFolder(v.path) ?: RepoPaths.parent(v.path)

/** A platform's folder (games/{platform}) is kept whole; deeper ones are games. */
private fun isWhole(folder: String) = folder.count { it == '/' } <= 1

/**
 * Keeps or stops keeping folders whole on a device. A platform's folder is kept as a folder, so
 * games added to it later come too. A game is kept as its files, so it stays kept when its folder
 * is renamed (an identification, a retag). Returns every asset whose place on the device changed.
 */
fun Repository.setFolderPicks(deviceId: String, pick: Collection<String>, unpick: Collection<String>): List<String> {
    val changed = LinkedHashSet<String>()
    db.tx {
        val before = folderPicks(deviceId)
        for (folder in pick + unpick) {
            val keep = folder in pick
            val files = db.read { liveUnder(folder) }.map { it.assetId }
            if (isWhole(folder)) {
                if (keep && folder !in before) db.exec("INSERT OR IGNORE INTO pick_folder (device_id, folder) VALUES (?, ?)", deviceId, folder)
                if (!keep && folder in before) db.exec("DELETE FROM pick_folder WHERE device_id = ? AND folder = ?", deviceId, folder)
                if (keep != (folder in before)) changed += files
            } else {
                changed += if (keep) setPicks(deviceId, files, emptyList()) else setPicks(deviceId, emptyList(), files)
            }
        }
        if (changed.isNotEmpty()) db.exec("UPDATE device SET rescan = 1 WHERE id = ?", deviceId)
    }
    return changed.toList()
}

/** Every platform and game under [prefix], each with whether [deviceId] keeps it. */
fun Repository.folderPickList(deviceId: String, prefix: String): List<FolderPickItem> = db.read {
    val picked = picks(deviceId)
    val whole = folderPicks(deviceId)
    val files = liveGames(prefix.trimEnd('/'))
    val sizes = files.associate { v -> v.assetId to (v.renditions.firstOrNull()?.let { media(it)?.size } ?: 0L) }
    val games = files.groupBy(::pickFolderOf).map { (folder, inGame) ->
        val named = inGame.firstOrNull { it.tags[TagField.TITLE] != null } ?: inGame.first()
        FolderPickItem(
            folder, gameLabel(named), named.category, isWhole(folder), inGame.sumOf { sizes.getValue(it.assetId) }, inGame.size,
            folder in whole || inGame.any { it.assetId in picked },
        )
    }
    val platforms = files.groupBy { it.path.split('/').take(2).joinToString("/") }.filterKeys { it.count { c -> c == '/' } == 1 && games.none { g -> g.folder == it } }
        .map { (folder, inPlatform) ->
            val platform = Platforms.byFolder(RepoPaths.name(folder))
            FolderPickItem(folder, platform?.name ?: RepoPaths.name(folder), platform?.id, true, inPlatform.sumOf { sizes.getValue(it.assetId) }, inPlatform.size, folder in whole)
        }
    (platforms + games).sortedWith(compareBy({ it.folder.split('/').getOrNull(1)?.lowercase() }, { !it.whole }, { it.label.lowercase() }))
}

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
