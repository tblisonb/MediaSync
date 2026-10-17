package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.tags.AudioTags
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * WMA tracks to MP3, with ffmpeg. MediaSync can't tag WMA, so those tracks sync as plain files,
 * keep the path they came with, and many players can't play them. Each becomes an MP3 (LAME's
 * best VBR setting) carrying the tags and cover ffmpeg finds, and is filed by its tags like any
 * other track. All of them are one revision: reverting it brings the WMA files back.
 */

/** How a conversion is going. [rev] is set once it's written; [finished] also when nothing was. */
class Conversion(val total: Int) {
    @Volatile var done = 0
    val failed: MutableList<String> = CopyOnWriteArrayList()
    @Volatile var rev: Long? = null
    @Volatile var finished = false
}

/** The WMA tracks in the music folder. */
fun Repository.wmaTracks(): List<Version> = db.read {
    musicExtras().filter { it.kind == AssetKind.OTHER && formatOf(it) == "wma" }
}

/**
 * Starts converting every WMA track on [launch]'s thread; returns the conversion, or the one already
 * running.
 */
fun Repository.startWmaConversion(deviceId: String, launch: (() -> Unit) -> Unit): Conversion {
    if (!Ffmpeg.available) badRequest("converting needs ffmpeg on the server: sudo apt install ffmpeg")
    val tracks = wmaTracks()
    val job = Conversion(tracks.size)
    val running = conversion.get()
    if (running != null && !running.finished) return running
    if (!conversion.compareAndSet(running, job)) return conversion.get()!!
    launch {
        try {
            job.rev = convertWma(deviceId, tracks, job)
        } finally {
            job.finished = true
        }
    }
    return job
}

/** Converts [tracks], then writes them as one revision; a track changed meanwhile is left as it is. */
internal fun Repository.convertWma(deviceId: String, tracks: List<Version>, job: Conversion): Long? {
    class Converted(val track: Version, val media: MediaRow, val tags: Map<String, List<String>>, val pictures: List<PictureRef>)
    val converted = mutableListOf<Converted>()
    for (track in tracks) {
        val name = "${RepoPaths.name(track.path)}.wma"
        val mp3 = track.renditions.firstOrNull()?.let(::media)?.let { Ffmpeg.toMp3(dir.blob(it.baseBlob), dir.uploads) }
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
                converted += Converted(track, media, read.tags.fields, listOfNotNull(cover))
            } catch (e: Exception) {
                job.failed += name
            } finally {
                Files.deleteIfExists(mp3)
            }
        }
        job.done++
    }
    if (converted.isEmpty()) return null
    return write {
        val scope = RevisionScope(this, deviceId, "Converted ${converted.size} WMA ${if (converted.size == 1) "track" else "tracks"} to MP3")
        for (c in converted) {
            val head = headVersion(c.track.assetId)?.takeIf { it.rev == c.track.rev } ?: continue
            scope.write(
                head.copy(
                    kind = AssetKind.AUDIO,
                    renditions = listOf(c.media.mediaHash),
                    tags = policy.filter(AssetKind.AUDIO, c.tags),
                    pictures = policy.filterPictures(AssetKind.AUDIO, c.pictures),
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
