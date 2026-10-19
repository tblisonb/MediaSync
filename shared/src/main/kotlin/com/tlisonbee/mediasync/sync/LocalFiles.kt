package com.tlisonbee.mediasync.sync

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.media.MediaFormat
import com.tlisonbee.mediasync.media.MediaHasher
import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.Picture
import com.tlisonbee.mediasync.tags.TagSet
import com.tlisonbee.mediasync.tags.TagWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.name

/** What a local file holds, as MediaSync sees it. */
class FileState(
    val kind: AssetKind,
    val media: String,
    val tags: Tags,
    val cover: Picture?,
    val coverHash: String?,
    val durationMs: Long?,
    /** Every tag the file had, for the server's import record. Only read when importing. */
    val raw: Tags?,
    /** Holds tags no policy keeps (comments, encoder notes, extra pictures). */
    val hasExtras: Boolean = false,
)

object LocalFiles {
    /** Formats whose tags MediaSync manages. Others (Opus, WAV, cover.jpg, .cue) sync as plain files. */
    private val TAGGED = setOf("mp3", "flac", "m4a", "ogg", "oga")
    // Camera raw formats too, so a raw file is filed beside the JPEG taken with it.
    private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf")
    private val VIDEOS = setOf("mp4", "m4v", "mov", "mkv", "avi", "webm")

    fun isTagged(format: String) = format.lowercase() in TAGGED

    /** What Windows (Media Player, Explorer) leaves in music folders; never synced. */
    private val CLUTTER = Regex("""(?i)desktop\.ini|thumbs\.db|albumartsmall\.jpg|albumart_\{[0-9a-f-]+\}_(small|large)\.jpg""")

    /** Our own temporary files, the dotfiles other programs leave around, and Windows clutter. */
    fun skipped(name: String) = name.startsWith(".") || CLUTTER.matches(name)

    /**
     * What a device deletes when the server asks it to: the Windows clutter, and macOS's .DS_Store
     * and "._" files. Never other dotfiles: .nomedia means something on Android, and our own
     * temporary files start with a dot.
     */
    fun isClutter(name: String) = CLUTTER.matches(name) || name == ".DS_Store" || name.startsWith("._")

    fun stat(path: Path): Pair<Long, Long> {
        val size = Files.size(path)
        val mtime = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).to(TimeUnit.MICROSECONDS)
        return size to mtime
    }

    fun read(path: Path, withRaw: Boolean = false): FileState {
        val ext = path.extension.lowercase()
        val media = MediaHasher.hash(path)
        if (ext in TAGGED && media.format != MediaFormat.OPAQUE) {
            val inspection = try {
                AudioTags.inspect(path)
            } catch (e: Exception) {
                null // unreadable tags: the file still syncs, as a plain file
            }
            if (inspection != null) {
                val cover = inspection.tags.frontCover
                return FileState(
                    kind = AssetKind.AUDIO,
                    media = media.hex,
                    tags = inspection.tags.fields,
                    cover = cover,
                    coverHash = cover?.let { sha256Hex(it.data) },
                    durationMs = inspection.durationMs,
                    raw = if (withRaw) inspection.raw else null,
                    hasExtras = inspection.hasExtras,
                )
            }
        }
        val kind = when (ext) {
            in IMAGES -> AssetKind.IMAGE
            in VIDEOS -> AssetKind.VIDEO
            else -> AssetKind.OTHER
        }
        return FileState(kind, media.hex, emptyMap(), null, null, null, null)
    }

    /** "music/Radiohead/OK Computer/01 Airbag" for ~/Music/Radiohead/OK Computer/01 Airbag.flac. */
    fun repoPath(folder: FolderConfig, file: Path): String {
        val relative = folder.root.relativize(file).joinToString("/") { it.name }
        if (RepoPaths.keepsExtension(folder.prefix + relative)) return folder.prefix + relative
        val dot = relative.lastIndexOf('.')
        val stem = if (dot > relative.lastIndexOf('/') + 1) relative.substring(0, dot) else relative
        return folder.prefix + stem
    }

    /** Where an asset's file goes in [folder], with extension [ext]. */
    fun localPath(folder: FolderConfig, assetPath: String, ext: String): Path {
        val relative = assetPath.removePrefix(folder.prefix)
        val name = if (ext.isEmpty() || RepoPaths.keepsExtension(assetPath)) relative else "$relative.$ext"
        return folder.root.resolve(name).normalize()
    }

    /** The fields this client can write, out of what the server holds. */
    fun writable(tags: Tags): Tags = tags.filterKeys { it in AudioTags.FIELDS }

    fun coverOf(asset: AssetDto) = asset.pictures.firstOrNull { it.type == TagPolicy.FRONT_COVER }

    /** Writes [tags] and [cover] into [file], guarding its media as TagWriter does. */
    fun writeTags(file: Path, tags: Tags, cover: Picture?) {
        TagWriter.writeVerified(file, TagSet(writable(tags), cover))
    }

    /** Renames [from] to [to], creating folders as needed; refuses to overwrite another file. */
    fun move(from: Path, to: Path) {
        if (from == to) return
        Files.createDirectories(to.parent)
        if (Files.exists(to) && !Files.isSameFile(from, to)) throw IOException("$to already exists")
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    /** Deletes a file, then any folders it leaves empty, up to (not including) [root]. */
    fun delete(file: Path, root: Path) {
        Files.deleteIfExists(file)
        pruneEmpty(file.parent, root)
    }

    fun pruneEmpty(dir: Path?, root: Path) {
        var current = dir
        while (current != null && current != root && current.startsWith(root)) {
            val empty = Files.isDirectory(current) && Files.list(current).use { !it.findAny().isPresent }
            if (!empty) return
            Files.deleteIfExists(current)
            current = current.parent
        }
    }
}
