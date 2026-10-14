package com.tlisonbee.mediasync.media

/** Locating ID3 tags, which sit before MP3 audio and sometimes before FLAC too. */
internal object Id3v2 {
    private const val HEADER = 10
    private const val FOOTER_PRESENT = 0x10

    /** Position just past any ID3v2 tags starting at [position] (files may carry several). */
    fun skipLeading(file: FileBytes, position: Long): Long {
        var pos = position
        while (pos + HEADER <= file.size && file.startsWith(pos, "ID3".ascii())) {
            val header = file.read(pos, HEADER)
            val footer = if (header.u8(5) and FOOTER_PRESENT != 0) HEADER else 0
            pos += HEADER + header.syncsafe(6) + footer
        }
        if (pos > file.size) throw MalformedMediaException("ID3v2 tag runs past the end of the file")
        return pos
    }

    /**
     * Start of the tags at the end of an MP3 (ID3v1, APEv2, Lyrics3v2, appended ID3v2), which can
     * be stacked in any order; [start] is where the audio begins.
     */
    fun trailingTagsStart(file: FileBytes, start: Long): Long {
        var end = file.size
        while (true) {
            val before = end
            if (end - 128 >= start && file.startsWith(end - 128, "TAG".ascii())) {
                end -= 128
            }
            if (end - 32 >= start && file.startsWith(end - 32, "APETAGEX".ascii())) {
                val footer = file.read(end - 32, 32)
                val hasHeader = footer.u32le(20) and (1L shl 31) != 0L
                end -= footer.u32le(12) + if (hasHeader) 32 else 0
            }
            if (end - 15 >= start && file.startsWith(end - 9, "LYRICS200".ascii())) {
                val size = String(file.read(end - 15, 6), Charsets.US_ASCII).toLongOrNull()
                    ?: throw MalformedMediaException("bad Lyrics3v2 size")
                end -= size + 15
            }
            if (end - HEADER >= start && file.startsWith(end - HEADER, "3DI".ascii())) {
                end -= file.read(end - HEADER, HEADER).syncsafe(6) + 2L * HEADER
            }
            if (end < start) throw MalformedMediaException("trailing tags overlap the audio")
            if (end == before) return end
        }
    }
}
