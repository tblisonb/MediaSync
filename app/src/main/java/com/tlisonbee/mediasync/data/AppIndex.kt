package com.tlisonbee.mediasync.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.sync.FileIndex
import com.tlisonbee.mediasync.sync.Row
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Path

/** The local index on Android: the same table as the desktop client's, through Android's SQLite. */
class AppIndex(context: Context) : SQLiteOpenHelper(context, "index.db", null, 1), FileIndex {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE file (
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
        db.execSQL("CREATE INDEX file_asset ON file(asset_id)")
        db.execSQL("CREATE INDEX file_prefix ON file(prefix)")
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun all(): List<Row> = query(null, emptyArray())

    override fun get(path: Path): Row? = query("path = ?", arrayOf(path.toString())).firstOrNull()

    override fun byAsset(assetId: String): List<Row> = query("asset_id = ?", arrayOf(assetId))

    override fun inFolder(prefix: String): List<Row> = query("prefix = ?", arrayOf(prefix))

    override fun put(row: Row) {
        val values = ContentValues().apply {
            put("path", row.path)
            put("prefix", row.prefix)
            put("size", row.size)
            put("mtime", row.mtime)
            put("kind", row.kind.apiName)
            put("media", row.media)
            put("tags", encode(row.tags))
            put("cover", row.cover)
            put("cover_mime", row.coverMime)
            put("duration_ms", row.durationMs)
            put("seen_media", row.seenMedia)
            put("seen_tags", row.seenTags?.let(::encode))
            put("seen_cover", row.seenCover)
            put("asset_id", row.assetId)
            put("base", row.base?.let { ApiJson.encodeToString(AssetDto.serializer(), it) })
            put("missing", row.missing)
            put("ignored", row.ignored)
            put("extras", row.extras)
        }
        writableDatabase.insertWithOnConflict("file", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun delete(path: String) {
        writableDatabase.delete("file", "path = ?", arrayOf(path))
    }

    override fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        if (db.inTransaction()) return block()
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    override fun state(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM state WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun setState(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete("state", "key = ?", arrayOf(key))
        } else {
            writableDatabase.insertWithOnConflict(
                "state", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /** Forgets everything, as when the phone is paired again. */
    fun clear() {
        writableDatabase.delete("file", null, null)
        writableDatabase.delete("state", null, null)
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM file WHERE missing = 0", null).use { it.moveToFirst(); it.getInt(0) }

    private fun query(where: String?, args: Array<String>): List<Row> =
        readableDatabase.query("file", null, where, args, null, null, "path").use { c ->
            buildList { while (c.moveToNext()) add(c.toRow()) }
        }

    private fun Cursor.toRow(): Row {
        fun str(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        fun long(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
        return Row(
            path = str("path")!!,
            prefix = str("prefix")!!,
            size = long("size")!!,
            mtime = long("mtime")!!,
            kind = AssetKind.entries.first { it.apiName == str("kind") },
            media = str("media")!!,
            tags = decode(str("tags")!!),
            cover = str("cover"),
            coverMime = str("cover_mime"),
            durationMs = long("duration_ms"),
            seenMedia = str("seen_media"),
            seenTags = str("seen_tags")?.let(::decode),
            seenCover = str("seen_cover"),
            assetId = str("asset_id"),
            base = str("base")?.let { ApiJson.decodeFromString(AssetDto.serializer(), it) },
            missing = long("missing") == 1L,
            ignored = str("ignored"),
            extras = long("extras") == 1L,
        )
    }

    private companion object {
        val tagsSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
        fun encode(tags: Tags) = ApiJson.encodeToString(tagsSerializer, tags)
        fun decode(text: String): Tags = ApiJson.decodeFromString(tagsSerializer, text)
    }
}
