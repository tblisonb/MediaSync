package com.tlisonbee.mediasync.media

import java.security.MessageDigest

/**
 * MP3: the MPEG frames between the leading ID3v2 tag and the trailing tags. Hashing starts at the
 * first real frame rather than at the end of the tag, because tag writers drop the padding or junk
 * some encoders leave in between.
 */
internal object Mp3Payload {
    private const val SCAN_LIMIT = 1 shl 20

    fun digest(file: FileBytes, digest: MessageDigest) {
        val start = Id3v2.skipLeading(file, 0)
        val end = Id3v2.trailingTagsStart(file, start)
        val first = findFirstFrame(file, start, end) ?: throw MalformedMediaException("no MPEG audio frames found")
        file.digestRange(digest, first, end)
    }

    /** First frame header whose frame is followed by another matching header (or by the end). */
    private fun findFirstFrame(file: FileBytes, start: Long, end: Long): Long? {
        val window = file.read(start, minOf(end - start, SCAN_LIMIT + MAX_FRAME + 4L).toInt())
        val scanEnd = minOf(window.size - 4, SCAN_LIMIT)
        for (i in 0..scanEnd) {
            val header = window.headerAt(i) ?: continue
            val length = frameLength(header) ?: continue
            val next = i + length
            if (start + next == end) return start + i
            val following = if (next + 4 <= window.size) window.headerAt(next) else null
            if (following != null && following and SAME_STREAM == header and SAME_STREAM && frameLength(following) != null) {
                return start + i
            }
        }
        return null
    }

    private fun ByteArray.headerAt(i: Int): Int? {
        if (i < 0 || i + 4 > size || u8(i) != 0xFF || u8(i + 1) and 0xE0 != 0xE0) return null
        return (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
    }

    /** Frame size in bytes, or null if [header] isn't a valid (non-free-format) MPEG audio header. */
    internal fun frameLength(header: Int): Int? {
        val version = (header ushr 19) and 3 // 0 = MPEG 2.5, 1 = reserved, 2 = MPEG 2, 3 = MPEG 1
        val layer = (header ushr 17) and 3 // 1 = Layer III, 2 = Layer II, 3 = Layer I
        val bitrateIndex = (header ushr 12) and 0xF
        val sampleRateIndex = (header ushr 10) and 3
        val padding = (header ushr 9) and 1
        if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null

        val mpeg1 = version == 3
        val bitrate = 1000 * when {
            mpeg1 && layer == 3 -> MPEG1_LAYER1
            mpeg1 && layer == 2 -> MPEG1_LAYER2
            mpeg1 -> MPEG1_LAYER3
            layer == 3 -> MPEG2_LAYER1
            else -> MPEG2_LAYER23
        }[bitrateIndex - 1]
        val sampleRate = when (version) {
            3 -> intArrayOf(44100, 48000, 32000)
            2 -> intArrayOf(22050, 24000, 16000)
            else -> intArrayOf(11025, 12000, 8000)
        }[sampleRateIndex]

        return when {
            layer == 3 -> (12 * bitrate / sampleRate + padding) * 4
            layer == 1 && !mpeg1 -> 72 * bitrate / sampleRate + padding
            else -> 144 * bitrate / sampleRate + padding
        }
    }

    // Sync, version, layer, protection bit and sample rate stay the same throughout a stream.
    private const val SAME_STREAM = 0xFFFE0C00.toInt()
    private const val MAX_FRAME = 2881 // MPEG 2.5 Layer II at 160 kbit/s, 8 kHz, padded

    private val MPEG1_LAYER1 = intArrayOf(32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val MPEG1_LAYER2 = intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val MPEG1_LAYER3 = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_LAYER1 = intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val MPEG2_LAYER23 = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
}
