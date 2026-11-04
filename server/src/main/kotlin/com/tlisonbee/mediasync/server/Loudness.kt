package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PushRequest
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.pow

/**
 * ReplayGain 2.0 values for every track, measured here once so every device and player (the
 * Android player, fooyin) levels the same way. ffmpeg's EBU R128 meter gives the loudness of each
 * 400 ms block, 10 a second; a track keeps them as a histogram in 0.1 LU steps (table loudness, by
 * media), so an album's loudness, which is gated over all its blocks together, comes from adding
 * its tracks' histograms and needs no decoding when a track joins or leaves it. The values are
 * written as tags ([TagField.REPLAYGAIN]) in revisions of their own, which devices sync like any
 * retag.
 */
object Loudness {
    /** ReplayGain 2.0's reference loudness. */
    const val REFERENCE_LUFS = -18.0

    /** One track's measurement: its sample peak (1 is full scale) and its blocks' loudness. */
    class Measurement(val peak: Double, val blocks: Map<Int, Int>) {
        fun encode(): String = blocks.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

        companion object {
            fun decode(peak: Double, text: String) = Measurement(
                peak,
                if (text.isEmpty()) emptyMap() else text.split(',').associate { e -> e.substringBefore(':').toInt() to e.substringAfter(':').toInt() },
            )
        }
    }

    private val BLOCK = Regex("""\bM:\s*(-?\d+(?:\.\d+)?)""")
    private val PEAK = Regex("""^\s*Peak:\s*(-?(?:\d+(?:\.\d+)?|inf))\s*dBFS""")

    /** Reads ffmpeg's `ebur128=framelog=info:peak=sample` log. */
    fun parse(log: Sequence<String>): Measurement? {
        val blocks = HashMap<Int, Int>()
        var peakDb: Double? = null
        var inPeak = false
        for (line in log) {
            if (" t: " in line && "TARGET:" in line) {
                val m = BLOCK.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                // Silence (and the first blocks, before a whole 400 ms is in) is below the absolute gate.
                if (m > ABSOLUTE_GATE) blocks.merge(Math.round(m * 10).toInt(), 1, Int::plus)
            } else if ("Sample peak:" in line) {
                inPeak = true
            } else if (inPeak) {
                PEAK.find(line)?.let { peakDb = it.groupValues[1].let { v -> if (v == "-inf") Double.NEGATIVE_INFINITY else v.toDouble() } }
            }
        }
        val db = peakDb ?: return null
        return Measurement(if (db.isInfinite()) 0.0 else 10.0.pow(db / 20), blocks)
    }

    private const val ABSOLUTE_GATE = -70.0

    /** The gated loudness (LUFS) of the blocks of [measurements] together, as BS.1770 gates it; null for silence. */
    fun integrated(measurements: List<Measurement>): Double? {
        val histogram = HashMap<Int, Long>()
        for (m in measurements) for ((k, n) in m.blocks) histogram.merge(k, n.toLong(), Long::plus)
        fun energy(key: Int) = 10.0.pow((key / 10.0 + 0.691) / 10)
        fun loudness(keys: Map<Int, Long>): Double? {
            val count = keys.values.sum()
            if (count == 0L) return null
            val mean = keys.entries.sumOf { (k, n) -> energy(k) * n } / count
            return -0.691 + 10 * log10(mean)
        }
        val ungated = loudness(histogram) ?: return null
        val relative = ungated - 10
        return loudness(histogram.filterKeys { it / 10.0 > relative })
    }

    fun gainTag(lufs: Double): String = "%.2f dB".format(Locale.ROOT, REFERENCE_LUFS - lufs)

    fun peakTag(peak: Double): String = "%.6f".format(Locale.ROOT, peak)

    /** The four tags for a track measured as [track], on an album measured as [album]. */
    fun tags(track: List<Measurement>, album: List<Measurement>): Map<String, List<String>>? {
        val trackLufs = integrated(track) ?: return null
        val albumLufs = integrated(album) ?: trackLufs
        return mapOf(
            TagField.REPLAYGAIN_TRACK_GAIN to listOf(gainTag(trackLufs)),
            TagField.REPLAYGAIN_TRACK_PEAK to listOf(peakTag(track.maxOf { it.peak })),
            TagField.REPLAYGAIN_ALBUM_GAIN to listOf(gainTag(albumLufs)),
            TagField.REPLAYGAIN_ALBUM_PEAK to listOf(peakTag(album.maxOf { it.peak })),
        )
    }

    /** Measures [input] with ffmpeg; null if it can't be decoded. */
    fun measure(input: Path): Measurement? = runCatching {
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-nostdin", "-i", input.toString(), "-map", "0:a:0",
            "-af", "ebur128=framelog=info:peak=sample", "-f", "null", "-",
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val result = process.errorStream.bufferedReader().useLines { parse(it) }
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return null
        }
        result.takeIf { process.exitValue() == 0 }
    }.getOrNull()
}

/** How a measuring run is going. */
class LoudnessJob {
    @Volatile var total = 0
    private val measured = java.util.concurrent.atomic.AtomicInteger()
    val done: Int get() = measured.get()
    internal fun measuredOne() = measured.incrementAndGet()
    @Volatile var written = 0
    val failed: MutableList<String> = CopyOnWriteArrayList()
    val revs: MutableList<Long> = CopyOnWriteArrayList()
    @Volatile var finished = false

    /** Set when tracks arrive during a run: it looks again once it's through. */
    @Volatile var again = false
}

/** The stored measurement of each media, by its hash; failed ones have none. */
private fun Repository.measurements(): Map<String, Loudness.Measurement?> = db.read {
    db.query("SELECT media_hash, peak, blocks, failed FROM loudness") {
        it.getString(1) to if (it.getInt(4) == 1) null else Loudness.Measurement.decode(it.getDouble(2), it.getString(3))
    }.toMap()
}

/** Measures what isn't yet and brings every track's values up to date, in the background; returns the run. */
fun Repository.startMeasuring(): LoudnessJob? {
    if (!settings.measureLoudness || !Ffmpeg.available) return null
    val running = loudnessJob.get()
    if (running != null && !running.finished) {
        running.again = true
        return running
    }
    val job = LoudnessJob()
    if (!loudnessJob.compareAndSet(running, job)) return loudnessJob.get()
    inBackground("loudness") {
        try {
            do {
                job.again = false
                measureAndWrite(job)
            } while (job.again && settings.measureLoudness)
        } catch (e: Exception) {
            job.failed += "measuring stopped: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            job.finished = true
        }
    }
    return job
}

/** After a push: tracks it brought (or replaced, or moved between albums) get their values. */
fun Repository.measureArrivals(request: PushRequest) {
    if (request.changes.any { it.path.startsWith(RepoPaths.MUSIC) }) startMeasuring()
}

/** The track's media to measure: its first rendition. */
private fun Version.measuredMedia(): String? = renditions.firstOrNull()

/** Tracks without an album share no album values: theirs are their own. */
private fun albumOf(v: Version): String? =
    if (v.tags[TagField.ALBUM].orEmpty().any { it.isNotBlank() }) RepoPaths.musicAlbumFolder(v.tags) else null

internal fun Repository.measureAndWrite(job: LoudnessJob, threads: Int = MEASURING_THREADS) {
    val tracks = liveTracks()
    val known = measurements()
    val toMeasure = tracks.mapNotNull { it.measuredMedia() }.distinct().filter { it !in known }
    job.total += toMeasure.size
    if (toMeasure.isNotEmpty()) {
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "loudness-ffmpeg").apply { isDaemon = true } }
        try {
            toMeasure.map { hash ->
                pool.submit {
                    val media = media(hash)
                    val result = media?.let { Loudness.measure(dir.blob(it.baseBlob)) }
                    db.tx {
                        db.exec(
                            "INSERT OR REPLACE INTO loudness (media_hash, peak, blocks, failed, measured_at) VALUES (?, ?, ?, ?, ?)",
                            hash, result?.peak ?: 0.0, result?.encode() ?: "", if (result == null) 1 else 0, clock(),
                        )
                    }
                    if (result == null) job.failed += tracks.firstOrNull { it.measuredMedia() == hash }?.path ?: hash
                    job.measuredOne()
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    // Every track's values as they should be now; written where the tags differ.
    val measured = measurements()
    val albums = tracks.groupBy { albumOf(it) }
    val mediaOf = tracks.associate { it.assetId to it.measuredMedia() }
    val wanted = LinkedHashMap<String, Map<String, List<String>>>()
    for ((album, members) in albums) {
        val albumMeasures = if (album == null) null else members.mapNotNull { m -> m.measuredMedia()?.let { measured[it] } }
        for (track in members) {
            val own = track.measuredMedia()?.let { measured[it] } ?: continue
            val values = Loudness.tags(listOf(own), albumMeasures?.takeIf { it.isNotEmpty() } ?: listOf(own)) ?: continue
            if (TagField.REPLAYGAIN.any { track.tags[it] != values[it] }) wanted[track.assetId] = values
        }
    }
    for (chunk in wanted.entries.chunked(WRITE_CHUNK)) {
        val rev = write {
            val scope = RevisionScope(this, webDeviceId(), "Measured the loudness of ${chunk.size} ${if (chunk.size == 1) "track" else "tracks"} (ReplayGain)")
            for ((assetId, values) in chunk) {
                val head = headVersion(assetId)?.takeIf { !it.deleted && it.kind == AssetKind.AUDIO } ?: continue
                // Its media was replaced meanwhile: the next run measures the new one.
                if (head.measuredMedia() != mediaOf[assetId]) continue
                scope.write(head.copy(tags = head.tags + values), place = false)
            }
            scope.finish()
        }
        rev?.let { job.revs += it }
        job.written += chunk.size
    }
}

/** ffmpeg runs at once; the server's other work keeps the rest of the machine. */
private val MEASURING_THREADS = (Runtime.getRuntime().availableProcessors() / 3).coerceIn(1, 8)

/** Tracks per revision: a first run over a whole library is several revisions rather than one huge one. */
private const val WRITE_CHUNK = 1000

/** A line for the Settings page: how many tracks have values, and how a run is going. */
fun Repository.loudnessStatus(): String? {
    if (!settings.measureLoudness) return null
    if (!Ffmpeg.available) return "Measuring needs ffmpeg on the server: sudo apt install ffmpeg"
    val tracks = db.read { liveTracks() }
    val with = tracks.count { it.tags[TagField.REPLAYGAIN_TRACK_GAIN] != null }
    val job = loudnessJob.get()
    val run = when {
        job == null -> ""
        !job.finished && job.done < job.total -> " Measuring: ${job.done} of ${job.total}."
        !job.finished -> " Writing the values…"
        job.failed.isNotEmpty() -> " ${job.failed.size} couldn't be read: ${job.failed.take(5).joinToString { RepoPaths.name(it) }}${if (job.failed.size > 5) ", …" else ""}."
        else -> ""
    }
    return "$with of ${tracks.size} tracks have values.$run"
}
