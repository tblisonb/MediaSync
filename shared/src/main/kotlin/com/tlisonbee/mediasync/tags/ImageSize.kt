package com.tlisonbee.mediasync.tags

/**
 * Width and height from a JPEG or PNG header, without decoding the image: Android has no
 * javax.imageio, and jaudiotagger's Android mode can't decode pictures at all.
 */
internal object ImageSize {
    fun of(data: ByteArray): Pair<Int, Int>? = png(data) ?: jpeg(data)

    private fun png(d: ByteArray): Pair<Int, Int>? {
        val magic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        if (d.size < 24 || !d.copyOfRange(0, 4).contentEquals(magic)) return null
        return u32(d, 16) to u32(d, 20)
    }

    private fun jpeg(d: ByteArray): Pair<Int, Int>? {
        if (d.size < 4 || d[0] != 0xFF.toByte() || d[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < d.size) {
            if (d[i] != 0xFF.toByte()) return null
            val marker = d[i + 1].toInt() and 0xFF
            if (marker == 0xFF) {
                i++
                continue
            }
            val length = u16(d, i + 2)
            // SOF0–SOF15 hold the frame size, except DHT (C4), JPG (C8) and DAC (CC).
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return u16(d, i + 7) to u16(d, i + 5)
            }
            i += 2 + length
        }
        return null
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    private fun u32(d: ByteArray, i: Int) = (u16(d, i) shl 16) or u16(d, i + 2)
}
