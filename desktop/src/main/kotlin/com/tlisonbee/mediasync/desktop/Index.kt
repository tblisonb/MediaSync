package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.sync.FileIndex
import com.tlisonbee.mediasync.sync.RemoteChange
import com.tlisonbee.mediasync.sync.Row
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** The local index, in SQLite beside the rest of the client's state. */
class Index(path: Path) : FileIndex, AutoCloseable {
    private val conn: Connection

    init {
        Files.createDirectories(path.parent)
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("PRAGMA busy_timeout = 10000")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS file (
                    path TEXT PRIMARY KEY,
                    prefix TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    mtime INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    media TEXT NOT NULL,
                    tags TEXT NOT NULL,
                    cover TEXT,
                    cover_mime TEXT,
                    duration_ms INTEGER,
                    seen_media TEXT,
                    seen_tags TEXT,
                    seen_cover TEXT,
                    asset_id TEXT,
                    base TEXT,
                    missing INTEGER NOT NULL DEFAULT 0,
                    ignored TEXT,
                    extras INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS file_asset ON file(asset_id)")
            st.execute("CREATE INDEX IF NOT EXISTS file_media ON file(media)")
            st.execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS remote (asset_id TEXT PRIMARY KEY, rev INTEGER NOT NULL, change TEXT NOT NULL)")
        }
    }

    @Synchronized
    override fun all(): List<Row> = query("SELECT * FROM file ORDER BY path")

    @Synchronized
    override fun get(path: Path): Row? = query("SELECT * FROM file WHERE path = ?", path.toString()).firstOrNull()

    @Synchronized
    override fun byAsset(assetId: String): List<Row> = query("SELECT * FROM file WHERE asset_id = ? ORDER BY path", assetId)

    @Synchronized
    override fun inFolder(prefix: String): List<Row> = query("SELECT * FROM file WHERE prefix = ? ORDER BY path", prefix)

    @Synchronized
    override fun put(row: Row) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO file (path, prefix, size, mtime, kind, media, tags, cover, cover_mime, duration_ms,
                seen_media, seen_tags, seen_cover, asset_id, base, missing, ignored, extras)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            listOf<Any?>(
                row.path, row.prefix, row.size, row.mtime, row.kind.apiName, row.media, encode(row.tags), row.cover, row.coverMime,
                row.durationMs, row.seenMedia, row.seenTags?.let(::encode), row.seenCover, row.assetId,
                row.base?.let { ApiJson.encodeToString(AssetDto.serializer(), it) }, row.missing, row.ignored, row.extras,
            ).forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    @Synchronized
    override fun delete(path: String) {
        conn.prepareStatement("DELETE FROM file WHERE path = ?").use { it.setString(1, path); it.executeUpdate() }
    }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (!conn.autoCommit) return block()
        conn.autoCommit = false
        try {
            return block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    override fun state(key: String): String? =
        conn.prepareStatement("SELECT value FROM state WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    @Synchronized
    override fun setState(key: String, value: String?) {
        if (value == null) {
            conn.prepareStatement("DELETE FROM state WHERE key = ?").use { it.setString(1, key); it.executeUpdate() }
        } else {
            conn.prepareStatement("INSERT OR REPLACE INTO state (key, value) VALUES (?, ?)").use {
                it.setString(1, key)
                it.setString(2, value)
                it.executeUpdate()
            }
        }
    }

    @Synchronized
    override fun remote(): List<RemoteChange> = conn.prepareStatement("SELECT change FROM remote ORDER BY rev, asset_id").use { st ->
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(ApiJson.decodeFromString(RemoteChange.serializer(), rs.getString(1))) } }
    }

    @Synchronized
    override fun putRemote(change: RemoteChange) {
        conn.prepareStatement("INSERT OR REPLACE INTO remote (asset_id, rev, change) VALUES (?, ?, ?)").use {
            it.setString(1, change.assetId)
            it.setLong(2, change.rev)
            it.setString(3, ApiJson.encodeToString(RemoteChange.serializer(), change))
            it.executeUpdate()
        }
    }

    @Synchronized
    override fun deleteRemote(assetId: String) {
        conn.prepareStatement("DELETE FROM remote WHERE asset_id = ?").use { it.setString(1, assetId); it.executeUpdate() }
    }

    /** Forgets everything: used when the device is paired again, perhaps with another server. */
    @Synchronized
    fun clear() {
        conn.createStatement().use {
            it.execute("DELETE FROM file")
            it.execute("DELETE FROM state")
            it.execute("DELETE FROM remote")
        }
    }

    override fun close() = conn.close()

    private fun query(sql: String, vararg args: Any?): List<Row> = conn.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRow()) } }
    }

    private fun ResultSet.toRow() = Row(
        path = getString("path"),
        prefix = getString("prefix"),
        size = getLong("size"),
        mtime = getLong("mtime"),
        kind = AssetKind.of(getString("kind")),
        media = getString("media"),
        tags = decode(getString("tags")),
        cover = getString("cover"),
        coverMime = getString("cover_mime"),
        durationMs = getLong("duration_ms").takeUnless { wasNull() },
        seenMedia = getString("seen_media"),
        seenTags = getString("seen_tags")?.let(::decode),
        seenCover = getString("seen_cover"),
        assetId = getString("asset_id"),
        base = getString("base")?.let { ApiJson.decodeFromString(AssetDto.serializer(), it) },
        missing = getBoolean("missing"),
        ignored = getString("ignored"),
        extras = getBoolean("extras"),
    )

    private companion object {
        val tagsSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
        fun encode(tags: Tags) = ApiJson.encodeToString(tagsSerializer, tags)
        fun decode(text: String): Tags = ApiJson.decodeFromString(tagsSerializer, text)
    }
}
