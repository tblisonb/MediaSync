package com.tlisonbee.mediasync.player.library

import android.util.Log
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.player.audio.ReplayGain
import com.tlisonbee.mediasync.player.remote.RemoteMusic
import com.tlisonbee.mediasync.player.tidal.TidalCatalog
import com.tlisonbee.mediasync.player.tidal.TidalHttp
import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.TagField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Where a scan is. */
data class ScanState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val lastError: String? = null)

/**
 * The music on the phone: the index kept in [LibraryDb], scanned from [root], and the playlists
 * in it. Everything is read into memory ([data]), which for ten thousand tracks is a few megabytes.
 */
class Library(
    private val db: LibraryDb,
    val covers: Covers,
    val root: File,
    private val scope: CoroutineScope,
    /** The MediaSync server's music, for what the phone doesn't hold. */
    val remote: RemoteMusic,
    showRemote: Boolean,
) {
    private val _data = MutableStateFlow<LibraryData?>(null)

    /** Null until the index has been read. */
    val data: StateFlow<LibraryData?> = _data.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistFile>>(emptyList())

    /** The playlist files in the library, by name; a file's date changes when it's edited. */
    val playlists: StateFlow<List<PlaylistFile>> = _playlists.asStateFlow()

    private val scanLock = Mutex()
    private val playlistLock = Mutex()

    /** When the last scan finished, for not scanning again on every visit. */
    @Volatile
    var lastScan = 0L
        private set

    val playlistFolder: File get() = File(root, "Playlists")

    /** TIDAL tracks Segue has seen, by ID, for playlists that name them (set by PlayerApp). */
    @Volatile
    var tidalLookup: (String) -> Track? = { null }

    /** The tracks on the phone, as last indexed; null until the index is read. */
    @Volatile
    private var local: List<Track>? = null

    /** Whether the server's tracks the phone lacks are in the library (Settings). */
    @Volatile
    var showRemote: Boolean = showRemote
        set(value) {
            field = value
            rebuild()
        }

    init {
        covers.fetch = { key ->
            when {
                key.startsWith(RemoteMusic.REMOTE_COVER) -> remote.download(key.removePrefix(RemoteMusic.REMOTE_COVER))
                // TIDAL's images are public.
                key.startsWith(TidalCatalog.COVER_PREFIX) -> TidalCatalog.coverUrl(key)?.let { url -> runCatching { TidalHttp.get(url) }.getOrNull()?.takeIf { it.ok }?.body }
                else -> null
            }
        }
        scope.launch(Dispatchers.IO) { loaded() }
    }

    /** Waits for the index; for the playback service, which may start before any screen. */
    suspend fun loaded(): LibraryData = withContext(Dispatchers.IO) {
        _data.value ?: run {
            remote.loadCached()
            if (local == null) local = db.all()
            rebuild()
            _data.value!!
        }
    }

    /** Asks the server for its music again, if it's been a while (or [force]). */
    fun refreshRemote(force: Boolean = false, minIntervalMs: Long = 5 * 60_000L) {
        if (!force && System.currentTimeMillis() - remote.state.value.lastRefresh < minIntervalMs) return
        scope.launch {
            loaded()
            if (remote.refresh() || force) rebuild()
        }
    }

    /** The library as shown: the phone's tracks, and the server's that aren't on it. */
    private fun rebuild() {
        val phone = local ?: return
        val onPhone = phone.mapTo(HashSet()) { it.path.lowercase() }
        val extra = remote.tracks.filter { t -> t.stream?.localPath?.lowercase().let { it == null || it !in onPhone } }
        remote.counted(extra.size)
        _data.value = LibraryData(if (showRemote) phone + extra else phone)
    }

    fun scanSoon(minIntervalMs: Long = 5 * 60_000L) {
        if (System.currentTimeMillis() - lastScan >= minIntervalMs && !_scan.value.running) scope.launch { scan() }
    }

    /** Brings the index up to date with the files; [full] reads every file again. */
    suspend fun scan(full: Boolean = false) = scanLock.withLock {
        withContext(Dispatchers.IO) {
            _scan.value = ScanState(running = true)
            try {
                runScan(full)
                _scan.value = ScanState()
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
                _scan.value = ScanState(lastError = e.message ?: e.javaClass.simpleName)
            }
            lastScan = System.currentTimeMillis()
        }
    }

    private class Found(val path: String, val size: Long, val mtime: Long)

    private fun runScan(full: Boolean) {
        AudioTags.useAndroidMode()
        val audio = mutableListOf<Found>()
        val playlists = mutableListOf<PlaylistFile>()
        // The best cover image in each folder, by its place in FOLDER_IMAGES.
        val folderImages = HashMap<String, Pair<Int, File>>()
        if (root.isDirectory) {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                    if (dir != root.toPath() && dir.fileName.toString().startsWith(".")) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val name = file.fileName.toString()
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val stem = name.substringBeforeLast('.').lowercase()
                    when {
                        ext in AUDIO -> audio += Found(file.toString(), attrs.size(), attrs.lastModifiedTime().toMillis())
                        ext in PLAYLISTS -> playlists += PlaylistFile(file.toString(), attrs.lastModifiedTime().toMillis())
                        ext in Covers.IMAGE_EXTENSIONS && stem in Covers.FOLDER_IMAGES -> {
                            val rank = Covers.FOLDER_IMAGES.indexOf(stem)
                            val folder = file.parent.toString()
                            if ((folderImages[folder]?.first ?: Int.MAX_VALUE) > rank) folderImages[folder] = rank to file.toFile()
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
        }
        playlists.sortBy { fold(it.name) }
        _playlists.value = playlists

        val known = db.all().associateBy { it.path }.toMutableMap()

        val seen = audio.mapTo(HashSet()) { it.path }
        val removed = known.keys.filter { it !in seen }
        if (removed.isNotEmpty()) db.delete(removed)
        removed.forEach(known::remove)

        // A full scan reads everything again, over the old index, so the library stays usable meanwhile.
        val changed = if (full) audio else audio.filter { f -> known[f.path].let { it == null || it.size != f.size || it.mtime != f.mtime } }
        _scan.value = ScanState(running = true, done = 0, total = changed.size)
        // Nothing new: the library as loaded is still right.
        if (changed.isEmpty() && removed.isEmpty() && _data.value != null) {
            Log.i(TAG, "scanned ${audio.size} files: no changes, ${playlists.size} playlists")
            return
        }
        if (removed.isNotEmpty() || _data.value == null) publish(known.values)

        val batch = mutableListOf<Track>()
        changed.forEachIndexed { i, f ->
            batch += read(f, folderImages[File(f.path).parent]?.second)
            if (batch.size == BATCH || i == changed.lastIndex) {
                db.put(batch)
                batch.forEach { known[it.path] = it }
                batch.clear()
                _scan.value = ScanState(running = true, done = i + 1, total = changed.size)
                // The first scan shows the library as it fills.
                if ((i + 1) % PUBLISH_EVERY == 0) publish(known.values)
            }
        }
        publish(known.values)
        covers.keepOnly(known.values.mapNotNullTo(HashSet()) { it.cover } + remote.tracks.mapNotNull { it.cover })
        Log.i(TAG, "scanned ${audio.size} files: ${changed.size} read, ${removed.size} gone, ${playlists.size} playlists")
    }

    private fun publish(tracks: Collection<Track>) {
        local = tracks.toList()
        rebuild()
    }

    private fun read(f: Found, folderImage: File?): Track {
        val file = File(f.path)
        val inspection = runCatching { AudioTags.inspect(file.toPath()) }.getOrNull()
        val tags = inspection?.tags
        val cover = tags?.frontCover?.let { runCatching { covers.fromBytes(it.data) }.getOrNull() }
            ?: folderImage?.let { runCatching { covers.fromImage(it) }.getOrNull() }
        val rg = inspection?.replayGain.orEmpty()
        return Track(
            path = f.path,
            size = f.size,
            mtime = f.mtime,
            title = tags?.get(TagField.TITLE)?.firstOrNull() ?: Track.titleOf(file),
            artists = tags?.get(TagField.ARTIST).orEmpty(),
            album = tags?.get(TagField.ALBUM)?.firstOrNull().orEmpty(),
            albumArtist = tags?.get(TagField.ALBUM_ARTIST)?.firstOrNull().orEmpty(),
            date = tags?.get(TagField.DATE)?.firstOrNull().orEmpty(),
            genre = tags?.get(TagField.GENRE)?.joinToString(", ").orEmpty(),
            trackNumber = Track.number(tags?.get(TagField.TRACK_NUMBER)?.firstOrNull()),
            discNumber = Track.number(tags?.get(TagField.DISC_NUMBER)?.firstOrNull()),
            durationMs = inspection?.durationMs,
            cover = cover,
            gain = Gains(
                rg["REPLAYGAIN_TRACK_GAIN"]?.let(ReplayGain::parseDb),
                rg["REPLAYGAIN_TRACK_PEAK"]?.let(ReplayGain::parsePeak),
                rg["REPLAYGAIN_ALBUM_GAIN"]?.let(ReplayGain::parseDb),
                rg["REPLAYGAIN_ALBUM_PEAK"]?.let(ReplayGain::parsePeak),
            ),
        )
    }

    // Playlists. Each change reads the file again and writes it whole, so edits from elsewhere
    // (fooyin, through MediaSync) aren't lost unless they land in between.

    /** A playlist's entries, each with the track it means (null when it isn't in the library). */
    suspend fun readPlaylist(path: String): List<Pair<M3u.Entry, Track?>> = withContext(Dispatchers.IO) {
        val library = loaded()
        entries(path).map { it to M3u.match(it, library, tidalLookup) }
    }

    private fun entries(path: String): List<M3u.Entry> {
        val file = File(path)
        return if (file.isFile) M3u.parse(file.readText(), file.parent ?: "/") else emptyList()
    }

    /** Makes `Playlists/<name>.m3u8` with [tracks]; returns its path, or null if one has that name. */
    suspend fun createPlaylist(name: String, tracks: List<Track>): String? = playlistLock.withLock {
        withContext(Dispatchers.IO) {
            val file = File(playlistFolder, RepoPaths.segment(name.trim(), 200) + ".m3u8")
            if (file.exists()) return@withContext null
            playlistFolder.mkdirs()
            write(file, tracks.map(M3u::entry))
            file.path
        }
    }

    suspend fun addToPlaylist(path: String, tracks: List<Track>) = edit(path) { it + tracks.map(M3u::entry) }

    suspend fun removeFromPlaylist(path: String, index: Int) = edit(path) { list -> list.filterIndexed { i, _ -> i != index } }

    suspend fun moveInPlaylist(path: String, from: Int, to: Int) = edit(path) { list ->
        if (from !in list.indices || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Renames a playlist; false if another has the name. */
    suspend fun renamePlaylist(path: String, name: String): Boolean = playlistLock.withLock {
        withContext(Dispatchers.IO) {
            val file = File(path)
            val target = File(file.parentFile, RepoPaths.segment(name.trim(), 200) + "." + file.extension)
            if (target.exists()) return@withContext false
            // Entries are relative to the folder, which stays the same.
            file.renameTo(target).also { if (it) { changed(file); changed(target) } }
        }
    }

    suspend fun deletePlaylist(path: String) = playlistLock.withLock {
        withContext(Dispatchers.IO) {
            File(path).delete()
            changed(File(path))
        }
    }

    private suspend fun edit(path: String, change: (List<M3u.Entry>) -> List<M3u.Entry>) = playlistLock.withLock {
        withContext(Dispatchers.IO) {
            val file = File(path)
            val library = loaded()
            // Entries found by their title are written with their file's path now.
            val entries = entries(path).map { e -> M3u.match(e, library, tidalLookup)?.takeIf { it.path != e.path }?.let(M3u::entry) ?: e }
            write(file, change(entries))
        }
    }

    private fun write(file: File, entries: List<M3u.Entry>) {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(M3u.write(entries, file.parent ?: "/"))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("couldn't write $file")
        }
        changed(file)
    }

    /** Updates [playlists] for one file the player wrote, renamed or deleted. */
    private fun changed(file: File) {
        val others = _playlists.value.filter { it.path != file.path }
        _playlists.value = (if (file.isFile) others + PlaylistFile(file.path, file.lastModified()) else others).sortedBy { fold(it.name) }
    }

    companion object {
        private const val TAG = "PlayerLibrary"
        private const val BATCH = 100
        private const val PUBLISH_EVERY = 1500

        val AUDIO = setOf("mp3", "flac", "m4a", "ogg", "oga", "opus", "wav", "aac")
        val PLAYLISTS = setOf("m3u", "m3u8")
    }
}
