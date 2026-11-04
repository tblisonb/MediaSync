package com.tlisonbee.mediasync.player.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** The player's index: one row per audio file, refreshed when its size or date changes. */
class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE track (
                path TEXT PRIMARY KEY,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                title TEXT NOT NULL,
                artists TEXT NOT NULL,
                album TEXT NOT NULL,
                album_artist TEXT NOT NULL,
                date TEXT NOT NULL,
                genre TEXT NOT NULL,
                track_no INTEGER,
                disc_no INTEGER,
                duration_ms INTEGER,
                cover TEXT,
                rg_track_gain REAL,
                rg_track_peak REAL,
                rg_album_gain REAL,
                rg_album_peak REAL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    fun all(): List<Track> = readableDatabase.query("track", null, null, null, null, null, null).use { c ->
        buildList { while (c.moveToNext()) add(c.toTrack()) }
    }

    fun put(tracks: Collection<Track>) = transaction { db ->
        for (t in tracks) db.insertWithOnConflict("track", null, t.values(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(paths: Collection<String>) = transaction { db ->
        for (p in paths) db.delete("track", "path = ?", arrayOf(p))
    }

    fun clear() {
        writableDatabase.delete("track", null, null)
    }

    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Track.values() = ContentValues().apply {
        put("path", path)
        put("size", size)
        put("mtime", mtime)
        put("title", title)
        put("artists", artists.joinToString(SEPARATOR))
        put("album", album)
        put("album_artist", albumArtist)
        put("date", date)
        put("genre", genre)
        put("track_no", trackNumber)
        put("disc_no", discNumber)
        put("duration_ms", durationMs)
        put("cover", cover)
        put("rg_track_gain", gain.trackGain)
        put("rg_track_peak", gain.trackPeak)
        put("rg_album_gain", gain.albumGain)
        put("rg_album_peak", gain.albumPeak)
    }

    private fun Cursor.toTrack(): Track {
        fun col(name: String) = getColumnIndexOrThrow(name)
        fun str(name: String) = col(name).let { if (isNull(it)) null else getString(it) }
        fun int(name: String) = col(name).let { if (isNull(it)) null else getInt(it) }
        fun long(name: String) = col(name).let { if (isNull(it)) null else getLong(it) }
        fun float(name: String) = col(name).let { if (isNull(it)) null else getFloat(it) }
        return Track(
            path = str("path")!!,
            size = long("size")!!,
            mtime = long("mtime")!!,
            title = str("title")!!,
            artists = str("artists")!!.split(SEPARATOR).filter { it.isNotEmpty() },
            album = str("album")!!,
            albumArtist = str("album_artist")!!,
            date = str("date")!!,
            genre = str("genre")!!,
            trackNumber = int("track_no"),
            discNumber = int("disc_no"),
            durationMs = long("duration_ms"),
            cover = str("cover"),
            gain = Gains(float("rg_track_gain"), float("rg_track_peak"), float("rg_album_gain"), float("rg_album_peak")),
        )
    }

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}
