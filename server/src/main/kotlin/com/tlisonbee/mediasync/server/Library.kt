package com.tlisonbee.mediasync.server

/** Read-only views of the repository for the web UI. */

data class Listing(val folders: List<Pair<String, Int>>, val assets: List<Version>)

/** The folders and live assets directly inside [folder] ("" for the top). */
fun Repository.browse(folder: String): Listing = db.read {
    val key = Repository.pathKey(folder)
    val versions = if (folder.isEmpty()) {
        db.query(
            "SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev WHERE h.deleted = 0 ORDER BY h.path_key",
        ) { it.toVersion() }
    } else {
        db.query(
            """
            SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
            WHERE h.deleted = 0 AND h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
            """.trimIndent(),
            "$key/", "${key}0",
        ) { it.toVersion() }
    }
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val inside = versions.filter { it.path.startsWith(prefix) }
    val folders = inside.map { it.path.removePrefix(prefix) }.filter { '/' in it }
        .groupingBy { it.substringBefore('/') }.eachCount().toList()
    Listing(folders, inside.filter { '/' !in it.path.removePrefix(prefix) })
}

/** Deleted assets, most recently deleted first. */
fun Repository.recentlyDeleted(limit: Int = 200): List<Version> = db.read {
    db.query(
        """
        SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.deleted = 1 ORDER BY h.rev DESC LIMIT ?
        """.trimIndent(),
        limit,
    ) { it.toVersion() }
}

fun Repository.createdAt(rev: Long): Long? = db.read { db.one("SELECT created_at FROM revision WHERE rev = ?", rev) { it.getLong(1) } }
