package com.tlisonbee.mediasync.player.audio

import kotlin.math.pow

/** ReplayGain values as tags write them ("-6.02 dB", "+1.5 dB", "0.988"), and the gain they give. */
object ReplayGain {
    /** The MediaItem extras key the player puts a track's gain in, in dB. */
    const val EXTRA_GAIN_DB = "com.tlisonbee.mediasync.player.GAIN_DB"

    private val number = Regex("""^\s*([+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+))\s*(?:dB)?\s*$""", RegexOption.IGNORE_CASE)

    /** A gain tag's value in dB, or null when it doesn't hold one. */
    fun parseDb(value: String): Float? =
        number.matchEntire(value)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() }

    /** A peak tag's value (1.0 is full scale), or null. */
    fun parsePeak(value: String): Float? = parseDb(value)?.takeIf { it > 0f }

    /**
     * The linear factor for [gainDb] plus [preampDb]: never more than [peak] allows without
     * clipping, when the peak is known.
     */
    fun factor(gainDb: Float, preampDb: Float = 0f, peak: Float? = null): Float {
        val factor = 10f.pow((gainDb + preampDb) / 20f)
        return if (peak != null && factor * peak > 1f) 1f / peak else factor
    }
}
