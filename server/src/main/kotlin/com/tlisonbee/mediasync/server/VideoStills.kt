package com.tlisonbee.mediasync.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Stills a moving preview steps through, evenly spread from start to end. */
const val FRAMES = 12

/** Longer side of a video's stills, in pixels: the galleries show them wider than a photo's thumbnail. */
private const val STILL_SIZE = 480

/** How far into a video its thumbnail is taken: past the logos and the fade in. */
private const val THUMB_AT = 0.1

/** ffmpeg runs at once: a gallery of films asks for dozens of stills on its first showing. */
private val slots = Semaphore(3)

/** Probes by blob, kept while the server runs: a blob never changes. */
private val probes = ConcurrentHashMap<String, Probe>()

/** Blobs ffprobe couldn't read and stills ffmpeg couldn't make, not to try again until a restart. */
private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

/** One lock per still, so two requests for it make it once. */
private val making = ConcurrentHashMap<Path, Any>()

/** What ffprobe reads of a video blob; null without ffmpeg, or for a file it can't read. */
fun Repository.probe(hash: String): Probe? {
    probes[hash]?.let { return it }
    if (!Ffmpeg.available || hash in failed) return null
    val probe = Ffmpeg.probe(dir.blob(hash))
    if (probe == null) failed += hash else probes[hash] = probe
    return probe
}

/** A video blob's thumbnail, a tenth of the way in. */
fun Repository.videoThumbnail(hash: String): Path? = still(hash, dir.thumb(hash)) { seconds -> (seconds ?: 0.0) * THUMB_AT }

/** Still [n] of a video blob's [FRAMES]; null when its length isn't known, as the stills are spread by it. */
fun Repository.frame(hash: String, n: Int): Path? {
    if (n !in 0 until FRAMES) return null
    blob(hash)?.takeIf { it.mime.startsWith("video/") } ?: return null
    return still(hash, dir.frame(hash, n)) { seconds -> seconds?.let { it * (n + 1) / (FRAMES + 1) } }
}

/** The still of [hash] at the time [at] gives for its length, made once and kept at [file]. */
private fun Repository.still(hash: String, file: Path, at: (Double?) -> Double?): Path? {
    if (Files.exists(file)) return file
    val key = file.toString()
    if (key in failed) return null
    val probe = probe(hash) ?: return null
    val seconds = at(probe.seconds) ?: return null
    synchronized(making.computeIfAbsent(file) { Any() }) {
        if (Files.exists(file)) return file
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, ".still", ".tmp")
        slots.acquire()
        val made = try {
            Ffmpeg.still(dir.blob(hash), seconds, STILL_SIZE, tmp)
        } finally {
            slots.release()
        }
        if (!made) {
            Files.deleteIfExists(tmp)
            failed += key
            return null
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return file
    }
}
