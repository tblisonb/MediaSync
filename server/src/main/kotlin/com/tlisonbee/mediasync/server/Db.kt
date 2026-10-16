package com.tlisonbee.mediasync.server

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The repository database: one SQLite connection in WAL mode. Every access goes through [tx] or
 * [read], which serialize on this object; one user's library never needs more.
 */
class Db(path: Path) : AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private var depth = 0

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("PRAGMA foreign_keys = ON")
            // CLI commands (fsck, prune) may run beside the service.
            st.execute("PRAGMA busy_timeout = 10000")
        }
        migrate()
    }

    /** Runs [block] in a transaction; nested calls join the outer one. */
    @Synchronized
    fun <T> tx(block: () -> T): T {
        if (depth > 0) return nested(block)
        conn.autoCommit = false
        depth++
        try {
            return block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            depth--
            conn.autoCommit = true
        }
    }

    @Synchronized
    fun <T> read(block: () -> T): T = block()

    private fun <T> nested(block: () -> T): T {
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }

    // Single statements take the same lock as transactions, so one can never land inside another
    // thread's transaction on this shared connection.
    @Synchronized
    fun exec(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

    @Synchronized
    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = prepare(sql, args).use { st ->
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

    fun long(sql: String, vararg args: Any?): Long = one(sql, *args) { it.getLong(1) } ?: 0

    @Synchronized
    fun script(sql: String) = conn.createStatement().use { st ->
        sql.split(";\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
    }

    override fun close() = conn.close()

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement =
        conn.prepareStatement(sql).also { st -> args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) } }

    private fun migrate() {
        val version = long("PRAGMA user_version").toInt()
        if (version < 1) {
            tx {
                script(SCHEMA_V1)
                exec("PRAGMA user_version = 1")
            }
        }
        if (version < 2) {
            tx {
                // Videos (or anything under a pick rule) a device keeps: chosen per device.
                script("CREATE TABLE pick (device_id TEXT NOT NULL, asset_id TEXT NOT NULL, PRIMARY KEY (device_id, asset_id))")
                exec("PRAGMA user_version = 2")
            }
        }
    }
}

fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

/** The schema from the design doc, plus the columns and tables the server needs to run it. */
private val SCHEMA_V1 = """
CREATE TABLE blob (
  hash        TEXT PRIMARY KEY,
  size        INTEGER NOT NULL,
  mime        TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);

CREATE TABLE media (
  media_hash   TEXT PRIMARY KEY,
  base_blob    TEXT NOT NULL REFERENCES blob(hash),
  format       TEXT NOT NULL,
  duration_ms  INTEGER
);

CREATE TABLE device (
  id                TEXT PRIMARY KEY,
  kind              TEXT NOT NULL,
  name              TEXT NOT NULL,
  token_hash        TEXT,
  subscription      TEXT NOT NULL,
  rescan            INTEGER NOT NULL DEFAULT 0,  -- subscription changed: next listing is full
  synced_rev        INTEGER NOT NULL DEFAULT 0,
  created_at        INTEGER NOT NULL,
  last_seen_at      INTEGER,
  revoked_at        INTEGER
);
CREATE UNIQUE INDEX device_token ON device(token_hash);

CREATE TABLE pairing_code (
  code_hash   TEXT PRIMARY KEY,
  expires_at  INTEGER NOT NULL
);

CREATE TABLE revision (
  rev         INTEGER PRIMARY KEY,
  device_id   TEXT NOT NULL REFERENCES device(id),
  created_at  INTEGER NOT NULL,
  message     TEXT,
  reverts     INTEGER REFERENCES revision(rev),
  changes     TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE asset_version (
  asset_id    TEXT NOT NULL,
  rev         INTEGER NOT NULL REFERENCES revision(rev),
  deleted     INTEGER NOT NULL DEFAULT 0,
  path        TEXT NOT NULL,
  kind        TEXT NOT NULL,
  category    TEXT,
  renditions  TEXT NOT NULL,
  tags        TEXT NOT NULL,
  pictures    TEXT NOT NULL,
  PRIMARY KEY (asset_id, rev)
);
CREATE INDEX asset_version_rev ON asset_version(rev);

CREATE TABLE asset_head (
  asset_id    TEXT PRIMARY KEY,
  rev         INTEGER NOT NULL,
  path        TEXT NOT NULL,
  path_key    TEXT NOT NULL,
  deleted     INTEGER NOT NULL,
  match_key   TEXT
);
CREATE UNIQUE INDEX asset_head_live_path ON asset_head(path_key) WHERE deleted = 0;
CREATE INDEX asset_head_rev ON asset_head(rev, asset_id);
CREATE INDEX asset_head_match ON asset_head(match_key) WHERE deleted = 0;

CREATE TABLE rendition (
  media_hash  TEXT NOT NULL REFERENCES media(media_hash),
  asset_id    TEXT NOT NULL,
  PRIMARY KEY (media_hash, asset_id)
);
CREATE INDEX rendition_asset ON rendition(asset_id);

CREATE TABLE asset_import (
  asset_id     TEXT PRIMARY KEY,
  rev          INTEGER NOT NULL,
  device_id    TEXT NOT NULL,
  source_path  TEXT NOT NULL,
  raw_tags     TEXT NOT NULL
);

CREATE TABLE issue (
  id            INTEGER PRIMARY KEY,
  kind          TEXT NOT NULL,
  asset_id      TEXT NOT NULL,
  rev           INTEGER NOT NULL,
  device_id     TEXT NOT NULL,
  fields        TEXT NOT NULL DEFAULT '[]',
  candidates    TEXT NOT NULL,
  provisional   INTEGER NOT NULL,
  resolved_rev  INTEGER
);
CREATE INDEX issue_open ON issue(resolved_rev, id);

CREATE TABLE upload (
  id          TEXT PRIMARY KEY,
  device_id   TEXT NOT NULL,
  hash        TEXT NOT NULL,
  size        INTEGER NOT NULL,
  name        TEXT NOT NULL,
  created_at  INTEGER NOT NULL
)
"""
