package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.Subscription
import com.tlisonbee.mediasync.library.SubscriptionRule

/** The database version once photos/ has moved to images/ (Db.migrate goes up to 5). */
private const val IMAGES_VERSION = 6L

/**
 * Server 0.5.0 keeps pictures of every sort in images/: the photos that were in photos/ move to
 * images/photos/, filed by date as before, and the folders kept by hand there ("Mom's Stuff") to
 * images/other/, as one revision. A photo that came from such a folder but was filed by date goes
 * back to its folder, in images/other/. The devices' rules are renamed in the same transaction,
 * keeping their local folders, and each device follows (followRenames) once it's updated; until
 * then it leaves the moved files alone. Paths are kept as they are: nothing is renamed on the way.
 */
fun Repository.moveToImages() {
    if (db.read { db.long("PRAGMA user_version") } >= IMAGES_VERSION) return
    write {
        val devices = devices().filter { it.revokedAt == null }
        // Where each device kept photos/, for finding the folder an import came from.
        val roots = devices.mapNotNull { d -> d.subscription.rules.firstOrNull { it.prefix == RepoPaths.OLD_PHOTOS }?.let { d.id to it.localFolder.trimEnd('/', '\\') } }.toMap()
        val old = db.query(
            """
            SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
            WHERE h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
            """.trimIndent(),
            RepoPaths.OLD_PHOTOS, RepoPaths.OLD_PHOTOS.trimEnd('/') + "0",
        ) { it.toVersion() }.filter { it.path.startsWith(RepoPaths.OLD_PHOTOS) }
        if (old.isNotEmpty()) {
            val scope = RevisionScope(this, webDeviceId(), "Moved photos/ to images/photos/, and the folders kept by hand in it to images/other/")
            for (v in old) scope.write(v.copy(path = imagesPath(v, roots)), raiseCollision = false, place = false)
            scope.finish()
        }
        for (device in devices) {
            val rules = device.subscription.rules.map(::movedRule)
            if (rules == device.subscription.rules) continue
            // No full listing: a device that isn't updated yet would read one as "everything left".
            db.exec("UPDATE device SET subscription = ? WHERE id = ?", ApiJson.encodeToString(Subscription.serializer(), Subscription(rules)), device.id)
        }
        for ((device, folder) in db.query("SELECT device_id, folder FROM pick_folder") { it.getString(1) to it.getString(2) }) {
            val moved = RepoPaths.fromOldPhotos("$folder/").trimEnd('/')
            if (moved != folder) db.exec("UPDATE OR IGNORE pick_folder SET folder = ? WHERE device_id = ? AND folder = ?", moved, device, folder)
        }
        db.exec("PRAGMA user_version = $IMAGES_VERSION")
    }
}

/** Where [v], in photos/, goes in images/. [roots] are the devices' photos/ folders, by device id. */
private fun Repository.imagesPath(v: Version, roots: Map<String, String>): String {
    val moved = RepoPaths.fromOldPhotos(v.path)
    if (!moved.startsWith(RepoPaths.PHOTOS)) return moved
    val (device, source) = db.one("SELECT device_id, source_path FROM asset_import WHERE asset_id = ?", v.assetId) {
        it.getString(1) to it.getString(2).replace('\\', '/')
    } ?: return moved
    val root = roots[device]?.replace('\\', '/') ?: return moved
    val inFolder = source.removePrefix("$root/").takeIf { it != source } ?: return moved
    if (!inFolder.contains('/') || RepoPaths.isDateFolder(inFolder.substringBefore('/'))) return moved
    val name = RepoPaths.name(inFolder)
    val withoutExtension = RepoPaths.parent(inFolder) + "/" + (if (name.contains('.')) name.substringBeforeLast('.') else name)
    return runCatching { "${RepoPaths.IMAGES}other/" + RepoPaths.clean(withoutExtension) }.getOrDefault(moved)
}

/** A rule (or its path: filters) on photos/, on where photos/ went. */
private fun movedRule(rule: SubscriptionRule) = rule.copy(
    prefix = RepoPaths.fromOldPhotos(rule.prefix),
    filter = rule.filter.map { term ->
        val negated = term.startsWith("not ")
        val body = term.removePrefix("not ").trim()
        if (!body.startsWith("path:")) term else (if (negated) "not " else "") + "path:" + RepoPaths.fromOldPhotos(body.removePrefix("path:"))
    },
)
