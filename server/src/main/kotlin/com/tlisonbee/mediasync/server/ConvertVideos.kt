package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.library.RepoPaths
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Videos in older formats (WebM's VP8, AVI, WMV…) re-encoded as HEVC in MKV, the way rip-disc
 * encodes a disc (Ffmpeg.toHevc), so the library settles on one format. Only videos/ is converted:
 * clips among the photos stay as they were taken. A video keeps its asset, path, fields, subtitles
 * and poster; only its file changes, so devices swap the .webm for the .mkv at their next sync.
 *
 * Encoding is slow (about real time), so a run can take days. Each folder (a season, a film) is
 * saved as its own revision once it's done, so a restart loses at most the folder under way, and
 * [Settings.convertVideos] carries the run on after one. Reverting a revision brings its originals
 * back; they stay in the repository until pruned.
 */

/** Video formats that can be converted. MP4 and MKV are what the library settles on, so they aren't. */
val VIDEO_CONVERTIBLE_FORMATS = setOf(
    "webm", "avi", "wmv", "asf", "flv", "f4v", "mpg", "mpeg", "m2v", "vob", "ogv", "3gp", "3g2", "mov", "ts", "m2ts", "mts", "divx", "rm", "rmvb",
)

/** Room left on the repository's disk beyond each new file, so a conversion never fills it. */
internal const val VIDEO_SPACE_MARGIN = 5L * 1024 * 1024 * 1024

/** How a video conversion is going. Seconds are of video, for the bar and how long the rest takes. */
class VideoConversion(val total: Int, val totalSeconds: Double, private val started: Long = System.currentTimeMillis()) {
    @Volatile var done = 0

    /** Seconds of the videos done or given up on. */
    @Volatile var doneSeconds = 0.0

    /** How far into the one under way. */
    @Volatile var currentSeconds = 0.0

    /** The one under way, by its name. */
    @Volatile var current: String? = null

    /** Bytes before and after, of those converted. */
    @Volatile var bytesBefore = 0L
    @Volatile var bytesAfter = 0L

    val failed: MutableList<String> = CopyOnWriteArrayList()
    val revs: MutableList<Long> = CopyOnWriteArrayList()

    /** Why it stopped by itself before the end (the disk filling up). */
    @Volatile var error: String? = null

    /** Someone pressed Stop. */
    @Volatile var stopped = false
    @Volatile var finished = false
    @Volatile internal var process: Process? = null

    val secondsSoFar get() = doneSeconds + currentSeconds

    /** About how long the rest takes, at the pace so far; null until a minute of video is done. */
    fun millisLeft(now: Long = System.currentTimeMillis()): Long? =
        secondsSoFar.takeIf { it >= 60 }?.let { ((now - started) * (totalSeconds - it) / it).toLong().coerceAtLeast(0) }

    fun stop() {
        stopped = true
        process?.destroy()
    }
}

/** The videos in videos/ in a convertible format, by that format, in path order. */
fun Repository.convertibleVideos(): Map<String, List<Version>> = db.read {
    db.query(
        """
        SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.deleted = 0 AND v.kind = ? AND h.path_key >= ? AND h.path_key < ? ORDER BY h.path_key
        """.trimIndent(),
        AssetKind.VIDEO.apiName, RepoPaths.VIDEOS, "videos0",
    ) { it.toVersion() }
        .mapNotNull { v -> formatOf(v)?.takeIf { it in VIDEO_CONVERTIBLE_FORMATS }?.let { it to v } }
        .groupBy({ it.first }, { it.second })
}

/** Bytes free on the repository's disk, where converted videos go. */
fun Repository.freeSpace(): Long = runCatching { Files.getFileStore(dir.uploads).usableSpace }.getOrDefault(Long.MAX_VALUE)

/** Starts converting every video in [formats] in the background; returns the run, or the one already going. */
fun Repository.startVideoConversion(deviceId: String, formats: Set<String>): VideoConversion {
    if (!Ffmpeg.available) badRequest("converting needs ffmpeg on the server: sudo apt install ffmpeg")
    val running = videoConversion.get()
    if (running != null && !running.finished) return running
    val videos = convertibleVideos().filterKeys { it in formats }.values.flatten().sortedBy { it.path }
    val seconds = videos.associate { v -> v.assetId to (v.renditions.firstOrNull()?.let(::media)?.let { probe(it.baseBlob)?.seconds } ?: 0.0) }
    val job = VideoConversion(videos.size, seconds.values.sum())
    if (!videoConversion.compareAndSet(running, job)) return videoConversion.get()!!
    inBackground("video-conversion") {
        try {
            convertVideos(deviceId, videos, seconds, job)
        } catch (e: Exception) {
            job.error = e.message ?: e.toString()
        } finally {
            job.current = null
            job.finished = true
        }
    }
    return job
}

/** Stops the run under way, keeping what's saved, and stops converting videos as they arrive. */
fun Repository.stopVideoConversion() {
    updateSettings(settings.copy(convertVideos = emptyList()))
    videoConversion.get()?.takeIf { !it.finished }?.stop()
}

/** After a push: converts the videos it brought in a format [Settings.convertVideos] lists. */
fun Repository.convertVideoArrivals(request: PushRequest) {
    val formats = settings.convertVideos.toSet()
    if (formats.isEmpty() || !Ffmpeg.available) return
    val arrived = request.changes.any { change ->
        !change.deleted && change.path.startsWith(RepoPaths.VIDEOS) && change.mediaHash?.let { media(it)?.format } in formats
    }
    if (arrived) startVideoConversion(webDeviceId(), formats)
}

/** At startup: carries on a run a restart interrupted, or converts what arrived while the server was off. */
fun Repository.resumeVideoConversion() {
    val formats = settings.convertVideos.toSet()
    if (formats.isEmpty() || !Ffmpeg.available) return
    if (convertibleVideos().keys.any { it in formats }) startVideoConversion(webDeviceId(), formats)
}

/** Converts [videos] one by one, saving each folder's as a revision once the folder is done. */
internal fun Repository.convertVideos(deviceId: String, videos: List<Version>, seconds: Map<String, Double>, job: VideoConversion) {
    class Converted(val video: Version, val from: String, val media: MediaRow)
    for ((folder, inFolder) in videos.groupBy { RepoPaths.parent(it.path) }) {
        val converted = mutableListOf<Converted>()
        for (video in inFolder) {
            if (job.stopped || job.error != null) break
            val length = seconds[video.assetId] ?: 0.0
            val original = video.renditions.firstOrNull()?.let(::media)
            val name = "${RepoPaths.name(video.path)}.${original?.format}"
            job.current = name
            job.currentSeconds = 0.0
            val probe = original?.let { probe(it.baseBlob) }
            when {
                original == null || probe == null -> job.failed += "$name: ffprobe couldn't read it"
                headVersion(video.assetId)?.rev != video.rev -> Unit // changed since the run started: left as it is
                freeSpace() < original.size + VIDEO_SPACE_MARGIN -> {
                    job.error = "Stopped before $name: the repository's disk has only ${gigabytes(freeSpace())} free. " +
                        "Prune old revisions, or free some space, and start it again."
                }
                else -> {
                    val encoded = Ffmpeg.toHevc(
                        dir.blob(original.baseBlob), dir.uploads, probe,
                        started = { process -> job.process = process; if (job.stopped) process.destroy() },
                        stopped = { job.stopped },
                        onSeconds = { job.currentSeconds = it.coerceAtMost(length) },
                    )
                    job.process = null
                    val file = encoded.file
                    when {
                        job.stopped -> file?.let(Files::deleteIfExists)
                        file == null -> job.failed += "$name: ${encoded.error}"
                        else -> {
                            val after = Files.size(file)
                            val media = blobs.putFile(file, RepoPaths.name(video.path) + ".mkv")
                            converted += Converted(video, original.format, media)
                            job.bytesBefore += original.size
                            job.bytesAfter += after
                            job.done++
                        }
                    }
                }
            }
            if (job.error == null && !job.stopped) job.doneSeconds += length
            job.currentSeconds = 0.0
        }
        saveConverted(deviceId, folder, converted.map { Triple(it.video, it.from, it.media) })?.let { job.revs += it }
        if (job.stopped || job.error != null) break
    }
}

/** Writes one folder's converted videos as a revision; a video changed meanwhile is left as it is. */
private fun Repository.saveConverted(deviceId: String, folder: String, converted: List<Triple<Version, String, MediaRow>>): Long? {
    if (converted.isEmpty()) return null
    val from = converted.groupingBy { it.second.uppercase() }.eachCount().entries.sortedByDescending { it.value }
        .joinToString { "${it.value} ${it.key}" }
    val what = if (converted.size == 1) RepoPaths.name(converted.single().first.path) else "${converted.size} videos in ${folder.removePrefix(RepoPaths.VIDEOS)}"
    return write {
        val scope = RevisionScope(this, deviceId, "Converted $what to HEVC ($from)")
        for ((video, _, media) in converted) {
            val head = headVersion(video.assetId)?.takeIf { it.rev == video.rev } ?: continue
            scope.write(head.copy(renditions = listOf(media.mediaHash)))
        }
        scope.finish()
    }
}

internal fun gigabytes(bytes: Long): String = "%.1f GB".format(java.util.Locale.ROOT, bytes / 1e9)
