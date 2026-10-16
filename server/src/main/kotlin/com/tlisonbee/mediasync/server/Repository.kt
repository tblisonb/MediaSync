package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Candidate
import com.tlisonbee.mediasync.api.ChangeType
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.IssueKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.RenditionDto
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.Subscription
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.tags.TagField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.sql.ResultSet
import java.util.Locale

/**
 * The repository: linear history of asset versions in SQLite, with the blob store beside it.
 * Operations live in extension files (Push.kt, History.kt, Resolve.kt, Devices.kt); this file has
 * the shared plumbing: reading versions, and writing them through [RevisionScope].
 */
class Repository(val dir: RepoDir, val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    val db = Db(dir.db)
    val blobs = BlobStore(this)

    @Volatile
    var settings: Settings = dir.readSettings()
        private set

    val policy: TagPolicy get() = settings.tagPolicy

    private val _head = MutableStateFlow(db.read { head() })

    /** The newest revision, for GET /events. */
    val headFlow: StateFlow<Long> get() = _head

    fun updateSettings(settings: Settings) {
        settings.tagPolicy.fields.values.flatten().forEach {
            require(it !in com.tlisonbee.mediasync.api.Fields.ALL) { "'$it' can't be a tag name" }
        }
        dir.writeSettings(settings)
        this.settings = settings
    }

    /** Runs a write; once it commits, anyone waiting on [headFlow] hears about a new revision. */
    fun <T> write(block: () -> T): T {
        val result = db.tx(block)
        _head.value = db.read { head() }
        return result
    }

    override fun close() = db.close()

    // ---- Reading ----

    fun head(): Long = db.long("SELECT COALESCE(MAX(rev), 0) FROM revision")

    fun headVersion(assetId: String): Version? = db.one(
        "SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev WHERE h.asset_id = ?",
        assetId,
    ) { it.toVersion() }

    fun version(assetId: String, rev: Long): Version? =
        db.one("SELECT * FROM asset_version WHERE asset_id = ? AND rev = ?", assetId, rev) { it.toVersion() }

    /** The asset's version as it stood at [rev]: the latest one written at or before it. */
    fun versionAt(assetId: String, rev: Long): Version? =
        db.one("SELECT * FROM asset_version WHERE asset_id = ? AND rev <= ? ORDER BY rev DESC LIMIT 1", assetId, rev) { it.toVersion() }

    fun versions(assetId: String): List<Version> =
        db.query("SELECT * FROM asset_version WHERE asset_id = ? ORDER BY rev DESC", assetId) { it.toVersion() }

    fun liveAssetAt(path: String): String? =
        db.one("SELECT asset_id FROM asset_head WHERE path_key = ? AND deleted = 0", pathKey(path)) { it.getString(1) }

    /** Live assets directly inside [folder]. */
    fun liveIn(folder: String): List<Version> {
        val key = pathKey(folder)
        return db.query(
            """
            SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
            WHERE h.deleted = 0 AND h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
            """.trimIndent(),
            "$key/", "${key}0",
        ) { it.toVersion() }.filter { RepoPaths.parent(it.path) == folder }
    }

    /** Live assets anywhere under [folder]. */
    fun liveUnder(folder: String): List<Version> {
        val key = pathKey(folder)
        return db.query(
            """
            SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
            WHERE h.deleted = 0 AND h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
            """.trimIndent(),
            "$key/", "${key}0",
        ) { it.toVersion() }.filter { it.path.startsWith("$folder/") }
    }

    /** Every live track in the music folder. */
    fun liveTracks(): List<Version> = db.query(
        """
        SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.deleted = 0 AND v.kind = ? AND h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
        """.trimIndent(),
        AssetKind.AUDIO.apiName, RepoPaths.MUSIC, "music0",
    ) { it.toVersion() }

    fun liveWithMedia(mediaHash: String): List<Version> = db.query(
        """
        SELECT v.* FROM rendition r JOIN asset_head h ON h.asset_id = r.asset_id
        JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE r.media_hash = ? AND h.deleted = 0 ORDER BY v.asset_id
        """.trimIndent(),
        mediaHash,
    ) { it.toVersion() }

    fun liveWithMatchKey(key: String): List<Version> = db.query(
        """
        SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.match_key = ? AND h.deleted = 0 ORDER BY v.asset_id
        """.trimIndent(),
        key,
    ) { it.toVersion() }

    fun media(mediaHash: String): MediaRow? = db.one(
        "SELECT m.*, b.size FROM media m JOIN blob b ON b.hash = m.base_blob WHERE m.media_hash = ?",
        mediaHash,
    ) { MediaRow(it.getString("media_hash"), it.getString("base_blob"), it.getString("format"), it.getLong("size"), it.longOrNull("duration_ms")) }

    fun blob(hash: String): BlobRow? = db.one("SELECT * FROM blob WHERE hash = ?", hash) {
        BlobRow(it.getString("hash"), it.getLong("size"), it.getString("mime"), it.getLong("created_at"))
    }

    fun originalName(assetId: String): String? =
        db.one("SELECT source_path FROM asset_import WHERE asset_id = ?", assetId) { it.getString(1) }
            ?.let { RepoPaths.name(it.replace('\\', '/')).substringBeforeLast('.') }

    fun toDto(v: Version): AssetDto = AssetDto(
        assetId = v.assetId,
        rev = v.rev,
        deleted = v.deleted,
        path = v.path,
        kind = v.kind,
        category = v.category,
        renditions = v.renditions.mapNotNull { hash ->
            media(hash)?.let { RenditionDto(it.mediaHash, it.baseBlob, it.format, it.size, it.durationMs) }
        },
        tags = v.tags,
        pictures = v.pictures,
    )

    fun deviceName(id: String): String = db.one("SELECT name FROM device WHERE id = ?", id) { it.getString(1) } ?: id

    fun webDeviceId(): String =
        db.one("SELECT id FROM device WHERE kind = 'web' ORDER BY created_at LIMIT 1") { it.getString(1) }
            ?: error("the repository has no web device; was setup run?")

    fun hasOpenIssue(assetId: String, kind: IssueKind): Boolean =
        db.long("SELECT COUNT(*) FROM issue WHERE asset_id = ? AND kind = ? AND resolved_rev IS NULL", assetId, kind.dbName) > 0

    // ---- Paths ----

    /** Where [v] belongs: music paths follow the tags; anything else keeps the path it was given. */
    fun placePath(v: Version): String {
        if (v.kind != AssetKind.AUDIO || !RepoPaths.isMusic(v.path)) return v.path
        val folder = RepoPaths.musicAlbumFolder(v.tags)
        val multiDisc = RepoPaths.saysMultiDisc(v.tags) ||
            liveIn(folder).any { it.assetId != v.assetId && RepoPaths.saysMultiDisc(it.tags) }
        val original = originalName(v.assetId) ?: RepoPaths.name(v.path)
        return RepoPaths.music(v.tags, original, multiDisc)
    }

    /** [path], or the first "path (n)" no other live asset holds. */
    fun freePath(path: String, assetId: String): String {
        var candidate = path
        var n = 2
        while (true) {
            val holder = liveAssetAt(candidate)
            if (holder == null || holder == assetId) return candidate
            candidate = "$path ($n)"
            n++
        }
    }

    internal fun ResultSet.toVersion() = Version(
        assetId = getString("asset_id"),
        rev = getLong("rev"),
        deleted = getBoolean("deleted"),
        path = getString("path"),
        kind = AssetKind.entries.first { it.apiName == getString("kind") },
        category = getString("category"),
        renditions = ApiJson.decodeFromString(stringList, getString("renditions")),
        tags = ApiJson.decodeFromString(tagsSerializer, getString("tags")),
        pictures = ApiJson.decodeFromString(pictureList, getString("pictures")),
    )

    companion object {
        val stringList = ListSerializer(String.serializer())
        val tagsSerializer = MapSerializer(String.serializer(), stringList)
        val pictureList = ListSerializer(PictureRef.serializer())
        val candidateList = ListSerializer(Candidate.serializer())
        val changeCounts = MapSerializer(ChangeType.serializer(), Int.serializer())

        fun pathKey(path: String) = path.lowercase(Locale.ROOT)

        /** Tracks with the same artists, album and title, used to suggest renditions. */
        fun matchKey(kind: AssetKind, tags: Tags): String? {
            if (kind != AssetKind.AUDIO) return null
            val title = tags[TagField.TITLE]?.firstOrNull() ?: return null
            val artists = tags[TagField.ARTIST].orEmpty().joinToString("\u0001")
            val album = tags[TagField.ALBUM]?.firstOrNull().orEmpty()
            return listOf(artists, album, title).joinToString("\u0000").lowercase(Locale.ROOT)
        }
    }
}

val IssueKind.dbName get() = name.lowercase()

fun issueKind(db: String) = IssueKind.entries.first { it.dbName == db }

val DeviceKind.dbName get() = name.lowercase()

fun deviceKind(db: String) = DeviceKind.entries.first { it.dbName == db }

/**
 * Collects the writes of one revision. The revision row is created on the first write, so a push
 * that changes nothing leaves no trace. Call inside [Repository.write].
 */
class RevisionScope(
    private val repo: Repository,
    val deviceId: String,
    private val message: String? = null,
    private val reverts: Long? = null,
) {
    private var rev: Long? = null

    /** Each written asset's head from before this revision, for the change counts. */
    private val before = LinkedHashMap<String, Version?>()

    /** Album folders whose tracks may need renaming once this revision's writes are in. */
    private val albums = LinkedHashSet<String>()

    private val issues = LinkedHashMap<String, MutableList<Long>>()

    /** Resolve-queue entries this revision raised for [assetId]. */
    fun issuesFor(assetId: String): List<Long> = issues[assetId].orEmpty()

    val created get() = rev != null

    fun rev(): Long = rev ?: (repo.head() + 1).also {
        repo.db.exec(
            "INSERT INTO revision (rev, device_id, created_at, message, reverts) VALUES (?, ?, ?, ?, ?)",
            it, deviceId, repo.clock(), message?.takeIf { m -> m.isNotBlank() }, reverts,
        )
        rev = it
    }

    /**
     * Writes [proposed] as the asset's new version, after placing it at its path (music follows its
     * tags; a taken path gets " (2)" and a resolve-queue entry). Returns the asset's resulting
     * version, which is the old head if nothing changed.
     */
    fun write(proposed: Version, raiseCollision: Boolean = true): Version {
        val head = repo.headVersion(proposed.assetId)
        var v = proposed.copy(path = repo.placePath(proposed))
        if (!v.deleted) {
            val free = repo.freePath(v.path, v.assetId)
            if (free != v.path) {
                val other = repo.liveAssetAt(v.path)
                // Only when it lands there now: an asset already moved aside was reported then.
                val moved = head?.path != free
                if (raiseCollision && moved && other != null && !repo.hasOpenIssue(v.assetId, IssueKind.PATH_COLLISION)) {
                    raise(
                        IssueKind.PATH_COLLISION, v.assetId,
                        candidates = listOf(
                            Candidate("Already there", assetId = other, path = v.path),
                            Candidate("Moved aside", assetId = v.assetId, path = free),
                        ),
                        provisional = 1,
                    )
                }
                v = v.copy(path = free)
            }
        }
        if (head != null && head.sameState(v)) return head

        val r = rev()
        if (proposed.assetId !in before) before[proposed.assetId] = head
        v = v.copy(rev = r)
        repo.db.exec(
            """
            INSERT OR REPLACE INTO asset_version (asset_id, rev, deleted, path, kind, category, renditions, tags, pictures)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            v.assetId, r, v.deleted, v.path, v.kind.apiName, v.category,
            ApiJson.encodeToString(Repository.stringList, v.renditions),
            ApiJson.encodeToString(Repository.tagsSerializer, v.tags),
            ApiJson.encodeToString(Repository.pictureList, v.pictures),
        )
        repo.db.exec(
            "INSERT OR REPLACE INTO asset_head (asset_id, rev, path, path_key, deleted, match_key) VALUES (?, ?, ?, ?, ?, ?)",
            v.assetId, r, v.path, Repository.pathKey(v.path), v.deleted, Repository.matchKey(v.kind, v.tags),
        )
        repo.db.exec("DELETE FROM rendition WHERE asset_id = ?", v.assetId)
        if (!v.deleted) {
            v.renditions.forEach { repo.db.exec("INSERT OR IGNORE INTO rendition (media_hash, asset_id) VALUES (?, ?)", it, v.assetId) }
        }
        for (path in listOfNotNull(head?.path, v.path)) {
            if (v.kind == AssetKind.AUDIO && RepoPaths.isMusic(path)) albums += RepoPaths.parent(path)
        }
        return v
    }

    /** Adds a resolve-queue entry raised by this revision. */
    fun raise(kind: IssueKind, assetId: String, candidates: List<Candidate>, provisional: Int, fields: List<String> = emptyList()): Long {
        repo.db.exec(
            "INSERT INTO issue (kind, asset_id, rev, device_id, fields, candidates, provisional) VALUES (?, ?, ?, ?, ?, ?, ?)",
            kind.dbName, assetId, rev(), deviceId,
            ApiJson.encodeToString(Repository.stringList, fields),
            ApiJson.encodeToString(Repository.candidateList, candidates),
            provisional,
        )
        return repo.db.long("SELECT last_insert_rowid()").also { issues.getOrPut(assetId) { mutableListOf() } += it }
    }

    /**
     * Renames tracks whose album gained or lost its second disc: the disc prefix ("1-01") depends on
     * the whole album, not just one track's tags.
     */
    fun refreshAlbums() {
        for (folder in albums.toList()) {
            for (track in repo.liveIn(folder)) write(track)
        }
        bringExtras()
    }

    /**
     * Moves what isn't a track (cover scans, a booklet, a .cue) after its album: once every track
     * has left a folder, and all for the same new folder, whatever is still under it follows.
     */
    private fun bringExtras() {
        val moves = LinkedHashMap<String, MutableSet<String>>()
        for ((assetId, old) in before.toList()) {
            if (old == null || old.deleted || old.kind != AssetKind.AUDIO || !RepoPaths.isMusic(old.path)) continue
            val now = repo.headVersion(assetId) ?: continue
            val from = RepoPaths.parent(old.path)
            val to = RepoPaths.parent(now.path).takeUnless { now.deleted }
            if (from != to) moves.getOrPut(from) { LinkedHashSet() }.addAll(listOfNotNull(to))
        }
        for ((from, targets) in moves) {
            val to = targets.singleOrNull() ?: continue
            val left = repo.liveUnder(from)
            if (left.any { it.kind == AssetKind.AUDIO }) continue
            for (extra in left) moveExtra(extra, to + extra.path.removePrefix(from))
        }
    }

    /** Moves [extra] to [path]; if the same file is already there, the copy is deleted instead. */
    fun moveExtra(extra: Version, path: String) {
        val there = repo.liveAssetAt(path)?.let(repo::headVersion)
        if (there != null && there.assetId != extra.assetId && there.renditions.firstOrNull() == extra.renditions.firstOrNull()) {
            write(extra.copy(deleted = true))
        } else {
            write(extra.copy(path = path), raiseCollision = false)
        }
    }

    /** Records the change counts; returns the revision, or null if nothing was written. */
    fun finish(): Long? {
        val r = rev ?: return null
        val counts = mutableMapOf<ChangeType, Int>()
        for ((assetId, old) in before) {
            val new = repo.headVersion(assetId) ?: continue
            changeTypes(old, new).forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        repo.db.exec("UPDATE revision SET changes = ? WHERE rev = ?", ApiJson.encodeToString(Repository.changeCounts, counts), r)
        return r
    }
}
