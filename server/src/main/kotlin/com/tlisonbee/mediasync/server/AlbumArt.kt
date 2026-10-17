package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.tags.TagField
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Album art: the pictures directly in each album's folder, grouped by what they show, so that
 * copies of one picture (folder.jpg beside a smaller cover.jpg, say) come down to the best one and
 * the front cover can have the same name in every album. Pictures are compared by a 16×16
 * thumbnail kept per blob in image_info: a blob never changes, so neither does what it shows.
 */

/** A picture's size, and its 16×16 RGB thumbnail; [thumb] is null if the picture couldn't be decoded. */
class ImageInfo(val width: Int, val height: Int, val thumb: ByteArray?) {
    val pixels get() = width.toLong() * height
}

/** One picture file in an album's folder. */
class Art(val version: Version, val format: String, val blob: String, val size: Long, val info: ImageInfo?) {
    val name get() = RepoPaths.name(version.path) + "." + format
}

/** An album folder's pictures: each group is one picture, best copy first. [cover] indexes the front cover's group, null when that's unclear. */
class AlbumPictures(val folder: String, val groups: List<List<Art>>, val cover: Int?)

/** How album art is tidied: [dedupe] keeps one copy of each picture, [coverName] renames the front cover, [png] converts JPEGs. */
data class ArtOptions(val dedupe: Boolean = true, val coverName: String? = null, val png: Boolean = false)

/** Names the front cover can be given. */
val COVER_NAMES = listOf("folder", "cover")

/**
 * One picture after tidying: [slot] is the file that stays, with its history, and it takes
 * [best]'s image (as PNG with [png]) and the path [path].
 */
class Kept(val slot: Art, val best: Art, val path: String, val png: Boolean, val cover: Boolean) {
    val changes get() = slot !== best || path != slot.version.path || png
}

class ArtPlan(val album: AlbumPictures, val kept: List<Kept>, val deleted: List<Art>) {
    val changes get() = deleted.isNotEmpty() || kept.any { it.changes }
}

/** Every album folder holding pictures, with them grouped. Reads each new picture once; that can take a while the first time. */
fun Repository.albumPictures(): List<AlbumPictures> {
    class Found(val folder: String, val album: String?, val artist: String?, val images: List<Pair<Version, MediaRow>>, val embedded: List<String>)
    val found = db.read {
        val tracks = liveTracks().groupBy { RepoPaths.parent(it.path) }
        musicExtras().filter { it.kind == AssetKind.IMAGE }.groupBy { RepoPaths.parent(it.path) }.mapNotNull { (folder, files) ->
            val inFolder = tracks[folder] ?: return@mapNotNull null
            Found(
                folder,
                inFolder.firstNotNullOfOrNull { it.tags[TagField.ALBUM]?.firstOrNull() },
                inFolder.firstNotNullOfOrNull { (it.tags[TagField.ALBUM_ARTIST] ?: it.tags[TagField.ARTIST])?.firstOrNull() },
                files.mapNotNull { v -> v.renditions.firstOrNull()?.let(::media)?.let { v to it } },
                inFolder.flatMap { t -> t.pictures.filter { it.type == TagPolicy.FRONT_COVER }.map { it.blob } }.distinct(),
            )
        }
    }
    // Decoding happens outside the database lock, so syncs carry on meanwhile.
    return found.map { f ->
        val groups = group(f.images.map { (v, media) -> Art(v, media.format, media.baseBlob, media.size, imageInfo(media.baseBlob)) })
        AlbumPictures(f.folder, groups, coverOf(groups, f.embedded.mapNotNull(::imageInfo), f.album, f.artist))
    }.sortedBy { it.folder.lowercase(Locale.ROOT) }
}

/** What tidying with [options] does to each album. */
fun Repository.artPlans(options: ArtOptions, albums: List<AlbumPictures> = albumPictures()): List<ArtPlan> = db.read {
    albums.map { planAlbum(it, options) }
}

private fun Repository.planAlbum(album: AlbumPictures, options: ArtOptions): ArtPlan {
    val kept = mutableListOf<Kept>()
    val deleted = mutableListOf<Art>()
    album.groups.forEachIndexed { i, group ->
        val cover = i == album.cover
        for (copies in if (options.dedupe) listOf(group) else group.map { listOf(it) }) {
            val best = copies.first()
            val target = options.coverName?.takeIf { cover && best === group.first() }?.let { "${album.folder}/$it" }
            // A copy already at the cover's name stays and takes the best image: renaming onto a
            // name that another file leaves in the same revision would collide on devices.
            val slot = target?.let { t -> copies.firstOrNull { Repository.pathKey(it.version.path) == Repository.pathKey(t) } } ?: best
            val holder = target?.let(::liveAssetAt)
            val path = if (target != null && (holder == null || copies.any { it.version.assetId == holder })) target else slot.version.path
            kept += Kept(slot, best, path, options.png && best.format == "jpg" && best.info?.thumb != null, cover)
            deleted += copies.filter { it !== slot }
        }
    }
    return ArtPlan(album, kept, deleted)
}

/** Carries out [artPlans] as one revision; an album whose pictures changed since is left alone. */
fun Repository.tidyAlbumArt(deviceId: String, options: ArtOptions): Long? {
    val plans = artPlans(options).filter { it.changes }
    if (plans.isEmpty()) return null
    // Converted pictures go into the blob store first, outside the revision's transaction.
    val converted = HashMap<String, MediaRow>()
    for (kept in plans.flatMap { it.kept }.filter { it.png }) {
        if (kept.best.blob in converted) continue
        Images.png(dir.blob(kept.best.blob))?.let { converted[kept.best.blob] = blobs.putBytes(it, RepoPaths.name(kept.path) + ".png") }
    }
    return write {
        val scope = RevisionScope(this, deviceId, "Tidied the album art in ${plans.size} ${if (plans.size == 1) "album" else "albums"}")
        for (plan in plans) {
            val involved = plan.deleted + plan.kept.flatMap { listOf(it.slot, it.best) }
            if (involved.any { headVersion(it.version.assetId)?.rev != it.version.rev }) continue
            plan.deleted.forEach { scope.write(it.version.copy(deleted = true)) }
            for (kept in plan.kept.filter { it.changes }) {
                val head = headVersion(kept.slot.version.assetId)!!
                val renditions = converted[kept.best.blob]?.let { listOf(it.mediaHash) } ?: kept.best.version.renditions
                val holder = liveAssetAt(kept.path)
                val path = if (holder == null || holder == head.assetId) kept.path else head.path
                scope.write(head.copy(path = path, renditions = renditions), raiseCollision = false)
            }
        }
        scope.finish()
    }
}

/** What a picture blob shows: from image_info, or read from the blob the first time. */
fun Repository.imageInfo(blob: String): ImageInfo? {
    val cached = db.one("SELECT width, height, thumb FROM image_info WHERE blob = ?", blob) { ImageInfo(it.getInt(1), it.getInt(2), it.getBytes(3)) }
    if (cached != null) return cached.takeIf { it.width > 0 }
    val file = dir.blob(blob)
    if (!Files.exists(file)) return null
    val info = Images.info(file)
    db.exec("INSERT OR REPLACE INTO image_info (blob, width, height, thumb) VALUES (?, ?, ?, ?)", blob, info?.width ?: 0, info?.height ?: 0, info?.thumb)
    return info
}

/** Groups pictures that show the same thing, best copy first: the most pixels, then PNG, then the bigger file. */
private fun group(art: List<Art>): List<List<Art>> {
    val groups = mutableListOf<MutableList<Art>>()
    for (a in art) {
        groups.firstOrNull { g -> g.any { sameImage(it.info, a.info) } }?.add(a) ?: groups.add(mutableListOf(a))
    }
    val best = compareByDescending<Art> { it.info?.pixels ?: 0 }.thenBy { if (it.format == "png") 0 else 1 }
        .thenByDescending { it.size }.thenBy { it.version.path }
    return groups.map { it.sortedWith(best) }
}

/**
 * Which group is the front cover: the picture the tracks carry, else the one whose name says so
 * (folder, cover, front, the album's name), else the only one. Null when that leaves a tie.
 */
private fun coverOf(groups: List<List<Art>>, embedded: List<ImageInfo>, album: String?, artist: String?): Int? {
    groups.indices.filter { i -> groups[i].any { a -> embedded.any { sameImage(a.info, it) } } }.singleOrNull()?.let { return it }
    val scores = groups.map { g -> g.maxOf { coverScore(it, album, artist) } }
    val top = scores.maxOrNull() ?: return null
    return if (top > 0) scores.indices.singleOrNull { scores[it] == top } else groups.indices.singleOrNull()
}

/** 2 for folder, cover or front; 1 for a name starting with one of those or "albumart", or the album's name. */
private fun coverScore(art: Art, album: String?, artist: String?): Int {
    val stem = RepoPaths.name(art.version.path).lowercase(Locale.ROOT)
    val key = nameKey(stem)
    return when {
        stem in listOf("folder", "cover", "front") -> 2
        listOf("folder", "cover", "front", "albumart").any { key.startsWith(it) } -> 1
        album != null && (key == nameKey(album) || (artist != null && key == nameKey("$artist $album"))) -> 1
        else -> 0
    }
}

/**
 * Whether two pictures show the same thing: the same shape, and thumbnails that differ no more
 * than resizing and re-encoding make them. A part that differs (a sticker, a tracklist) is enough
 * to keep them apart.
 */
fun sameImage(a: ImageInfo?, b: ImageInfo?): Boolean {
    if (a == null || b == null) return false
    val ta = a.thumb ?: return false
    val tb = b.thumb ?: return false
    val ra = a.width.toDouble() / a.height
    val rb = b.width.toDouble() / b.height
    if (abs(ra - rb) > 0.03 * maxOf(ra, rb)) return false
    var total = 0
    var worst = 0
    for (i in ta.indices) {
        val d = abs((ta[i].toInt() and 0xFF) - (tb[i].toInt() and 0xFF))
        total += d
        if (d > worst) worst = d
    }
    return total <= 8 * ta.size && worst <= 48
}

/** Reading and converting pictures with javax.imageio, which reads JPEG, PNG, GIF and BMP. */
internal object Images {
    private const val THUMB = 16

    init {
        // In memory: the service may not write to /tmp.
        ImageIO.setUseCache(false)
    }

    fun info(file: Path): ImageInfo? = try {
        ImageIO.createImageInputStream(file.toFile())?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                // Every nth pixel is plenty for a 16×16 thumbnail, and much quicker to decode.
                val step = maxOf(1, minOf(width, height) / (THUMB * 8))
                val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                val thumb = try {
                    thumbnail(reader.read(0, param))
                } catch (e: Exception) {
                    null // a CMYK JPEG, say: its size is known, but it isn't compared
                }
                ImageInfo(width, height, thumb)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: IOException) {
        null
    }

    /** [file] as PNG, or null if it can't be decoded. */
    fun png(file: Path): ByteArray? = try {
        ImageIO.read(file.toFile())?.let { image -> ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray() }
    } catch (e: Exception) {
        null
    }

    /** The average colour of each cell of a 16×16 grid, as RGB bytes; transparency counts as white. */
    private fun thumbnail(image: BufferedImage): ByteArray {
        val out = ByteArray(THUMB * THUMB * 3)
        for (cy in 0 until THUMB) {
            val y0 = cy * image.height / THUMB
            val y1 = maxOf(y0 + 1, (cy + 1) * image.height / THUMB)
            for (cx in 0 until THUMB) {
                val x0 = cx * image.width / THUMB
                val x1 = maxOf(x0 + 1, (cx + 1) * image.width / THUMB)
                val sum = LongArray(3)
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val argb = image.getRGB(x, y)
                        val alpha = argb ushr 24
                        for (c in 0..2) {
                            val value = (argb shr (16 - 8 * c)) and 0xFF
                            sum[c] += (value * alpha + 255 * (255 - alpha)) / 255
                        }
                    }
                }
                val n = (y1 - y0).toLong() * (x1 - x0)
                for (c in 0..2) out[(cy * THUMB + cx) * 3 + c] = (sum[c] / n).toInt().toByte()
            }
        }
        return out
    }
}
