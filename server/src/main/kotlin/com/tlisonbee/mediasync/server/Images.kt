package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.Subscription
import com.tlisonbee.mediasync.library.SubscriptionRule

/** The database version once photos/ has moved to images/ (Db.migrate goes up to 5). */
private const val IMAGES_VERSION = 6L

/** The database version once [putImportsBack] has run. */
private const val IMPORTS_BACK_VERSION = 7L

private const val MOVE_MESSAGE = "Moved photos/ to images/photos/, and the folders kept by hand in it to images/other/"

/**
 * Server 0.5.0 keeps pictures of every sort in images/: the photos that were in photos/ move to
 * images/photos/, filed by date as before, and the folders kept by hand there ("Mom's Stuff") to
 * images/other/, as one revision. The devices' rules are renamed in the same transaction, keeping
 * their local folders, and each device follows (followRenames) once it's updated; until then it
 * leaves the moved files alone. Paths are kept as they are: nothing is renamed on the way.
 */
fun Repository.moveToImages() {
    if (db.read { db.long("PRAGMA user_version") } >= IMAGES_VERSION) return
    write {
        val old = db.query(
            """
            SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
            WHERE h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
            """.trimIndent(),
            RepoPaths.OLD_PHOTOS, RepoPaths.OLD_PHOTOS.trimEnd('/') + "0",
        ) { it.toVersion() }.filter { it.path.startsWith(RepoPaths.OLD_PHOTOS) }
        if (old.isNotEmpty()) {
            val scope = RevisionScope(this, webDeviceId(), MOVE_MESSAGE)
            for (v in old) scope.write(v.copy(path = RepoPaths.fromOldPhotos(v.path)), raiseCollision = false, place = false)
            scope.finish()
        }
        for (device in devices().filter { it.revokedAt == null }) {
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

/**
 * Server 0.5.0 also sent a photo filed by date to images/other/ when it had first been imported
 * from a folder kept by hand, though the devices had filed it by date long since. This puts each
 * such photo back where the move found it, in images/photos/, unless it has changed since.
 */
fun Repository.putImportsBack() {
    if (db.read { db.long("PRAGMA user_version") } >= IMPORTS_BACK_VERSION) return
    write {
        val move = db.one("SELECT rev FROM revision WHERE message = ? ORDER BY rev LIMIT 1", MOVE_MESSAGE) { it.getLong(1) }
        if (move != null) {
            val untouched = db.query(
                "SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev WHERE h.rev = ?",
                move,
            ) { it.toVersion() }
            val scope = RevisionScope(this, webDeviceId(), "Moved the photos filed by date back to images/photos/, from images/other/")
            for (v in untouched) {
                val back = versionAt(v.assetId, move - 1)?.path?.let(RepoPaths::fromOldPhotos) ?: continue
                if (back.startsWith(RepoPaths.PHOTOS) && back != v.path) scope.write(v.copy(path = back), raiseCollision = false, place = false)
            }
            scope.finish()
        }
        db.exec("PRAGMA user_version = $IMPORTS_BACK_VERSION")
    }
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
