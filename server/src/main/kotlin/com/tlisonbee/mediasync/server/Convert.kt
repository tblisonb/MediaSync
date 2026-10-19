package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.tags.AudioTags
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Tracks to MP3, with ffmpeg: WMA, AAC (.m4a), FLAC, Ogg and the rest. Each becomes an MP3 (LAME's
 * best VBR setting), so every device and player gets one format. A track MediaSync already tags
 * keeps its tags and cover, and its place. WMA and the other formats it can't tag sync as plain
 * files under the names they came with; they take the tags and cover ffmpeg finds, and are filed
 * by their tags like any other track. A conversion is one revision: reverting it brings the
 * originals back, which stay in the repository until pruned.
 */

/** Audio formats a track can be converted from. */
val CONVERTIBLE_FORMATS = setOf(
    "wma", "m4a", "m4b", "aac", "alac", "flac", "ogg", "oga", "opus", "wav", "aif", "aiff", "ape", "wv", "mpc", "tta", "mka", "ac3",
)

/**
 * Formats that keep every bit of the recording: converting them gives that up for good, so the
 * Cleanup page leaves them unticked. (An .m4a can be lossless ALAC too; it's listed as m4a.)
 */
val LOSSLESS_FORMATS = setOf("flac", "alac", "wav", "aif", "aiff", "ape", "wv", "tta")

/** How a conversion is going. [rev] is set once it's written; [finished] also when nothing was. */
class Conversion(val total: Int) {
    @Volatile var done = 0
    val failed: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var rev: Long? = null
    @Volatile var finished = false
}

/** The tracks in the music folder that have no MP3, by the format of the one they have. */
fun Repository.nonMp3Tracks(): Map<String, List<Version>> = db.read {
    db.query(
        """
        SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.deleted = 0 AND v.kind IN (?, ?) AND h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
        """.trimIndent(),
        AssetKind.AUDIO.apiName, AssetKind.OTHER.apiName, RepoPaths.MUSIC, "music0",
    ) { it.toVersion() }
        .mapNotNull { v ->
            val formats = v.renditions.mapNotNull { media(it)?.format }
            formats.firstOrNull()?.takeIf { it in CONVERTIBLE_FORMATS && "mp3" !in formats }?.let { it to v }
        }
        .groupBy({ it.first }, { it.second })
}

/**
 * Starts converting every track in [formats] on [launch]'s thread; returns the conversion, or the
 * one already running.
 */
fun Repository.startConversion(deviceId: String, formats: Set<String>, launch: (() -> Unit) -> Unit = ::inBackground): Conversion {
    if (!Ffmpeg.available) badRequest("converting needs ffmpeg on the server: sudo apt install ffmpeg")
    val running = conversion.get()
    if (running != null && !running.finished) return running
    val tracks = nonMp3Tracks().filterKeys { it in formats }.values.flatten()
    val job = Conversion(tracks.size)
    if (!conversion.compareAndSet(running, job)) return conversion.get()!!
    launch {
        try {
            job.rev = convertToMp3(deviceId, tracks, job)
        } finally {
            job.finished = true
        }
    }
    return job
}

/**
 * After a push: converts what it brought in a format [Settings.convertToMp3] lists, in the
 * background, so tracks added later end up MP3s too. A conversion that's already running is left
 * to finish; the next push picks up what it missed.
 */
fun Repository.convertArrivals(request: PushRequest) {
    val formats = settings.convertToMp3.toSet()
    if (formats.isEmpty() || !Ffmpeg.available) return
    val arrived = request.changes.any { change ->
        !change.deleted && change.path.startsWith(RepoPaths.MUSIC) && change.mediaHash?.let { media(it)?.format } in formats
    }
    if (arrived) startConversion(webDeviceId(), formats)
}

private fun inBackground(work: () -> Unit) = Thread(work, "mp3-conversion").apply { isDaemon = true }.start()

/** Converts [tracks], then writes them as one revision; a track changed meanwhile is left as it is. */
internal fun Repository.convertToMp3(deviceId: String, tracks: List<Version>, job: Conversion): Long? {
    class Converted(val track: Version, val from: String, val media: MediaRow, val tags: Map<String, List<String>>, val pictures: List<PictureRef>)
    val converted = mutableListOf<Converted>()
    for (track in tracks) {
        val original = track.renditions.firstOrNull()?.let(::media)
        val name = "${RepoPaths.name(track.path)}.${original?.format}"
        val mp3 = original?.let { Ffmpeg.toMp3(dir.blob(it.baseBlob), dir.uploads) }
        if (mp3 == null) {
            job.failed += name
        } else {
            try {
                val read = AudioTags.inspect(mp3)
                val media = blobs.putBytes(Files.readAllBytes(mp3), RepoPaths.name(track.path) + ".mp3")
                val cover = read.tags.frontCover?.let { picture ->
                    val blob = blobs.putBytes(picture.data, if ("png" in picture.mimeType) "cover.png" else "cover.jpg").baseBlob
                    PictureRef(TagPolicy.FRONT_COVER, blob, picture.mimeType)
                }
                converted += Converted(track, original.format, media, read.tags.fields, listOfNotNull(cover))
            } catch (e: Exception) {
                job.failed += name
            } finally {
                Files.deleteIfExists(mp3)
            }
        }
        job.done++
    }
    if (converted.isEmpty()) return null
    val from = converted.groupingBy { it.from.uppercase() }.eachCount().entries.sortedByDescending { it.value }
        .joinToString { "${it.value} ${it.key}" }
    return write {
        val scope = RevisionScope(this, deviceId, "Converted ${converted.size} ${if (converted.size == 1) "track" else "tracks"} to MP3 ($from)")
        for (c in converted) {
            val head = headVersion(c.track.assetId)?.takeIf { it.rev == c.track.rev } ?: continue
            // A track MediaSync tags keeps its tags and cover; one it couldn't takes what ffmpeg read.
            val managed = head.kind == AssetKind.AUDIO
            scope.write(
                head.copy(
                    kind = AssetKind.AUDIO,
                    renditions = listOf(c.media.mediaHash),
                    tags = if (managed) head.tags else policy.filter(AssetKind.AUDIO, c.tags),
                    pictures = if (managed) head.pictures else policy.filterPictures(AssetKind.AUDIO, c.pictures),
                ),
            )
        }
        scope.refreshAlbums()
        scope.finish()
    }
}

internal object Ffmpeg {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    /**
     * [input] as an MP3 in a temporary file under [work], with its tags and cover; null if ffmpeg
     * can't (a DRM-protected WMA, say). A cover MP3 can't hold is left out rather than the track.
     */
    fun toMp3(input: Path, work: Path): Path? {
        val out = Files.createTempFile(work, "convert-", ".mp3")
        val withCover = listOf("-map", "0:v:0?", "-c:v", "copy", "-disposition:v", "attached_pic")
        for (picture in listOf(withCover, emptyList())) {
            val command = listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", input.toString(), "-map", "0:a:0") +
                picture + listOf("-c:a", "libmp3lame", "-q:a", "0", "-map_metadata", "0", "-id3v2_version", "3", "-f", "mp3", out.toString())
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                break
            }
            if (process.exitValue() == 0 && Files.size(out) > 0) return out
        }
        Files.deleteIfExists(out)
        return null
    }
}
