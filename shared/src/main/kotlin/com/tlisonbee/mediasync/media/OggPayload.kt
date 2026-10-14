package com.tlisonbee.mediasync.media

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Ogg (Vorbis, Opus, FLAC): every packet except the ones carrying tags, reassembled from the pages.
 * Page headers are left out because a tag writer that changes the comment packet's size re-paginates
 * the headers and renumbers every page after them.
 */
internal object OggPayload {
    private const val PAGE_HEADER = 27

    fun digest(file: FileBytes, digest: MessageDigest) {
        val streams = LinkedHashMap<Long, LogicalStream>() // in order of first appearance
        var pos = 0L
        while (pos < file.size) {
            if (!file.startsWith(pos, "OggS".ascii())) throw MalformedMediaException("no Ogg page at $pos")
            val header = file.read(pos, PAGE_HEADER)
            val segments = file.read(pos + PAGE_HEADER, header.u8(26))
            val bodyLength = segments.sumOf { it.toInt() and 0xFF }
            val body = file.read(pos + PAGE_HEADER + segments.size, bodyLength)

            val stream = streams.getOrPut(header.u32le(14)) { LogicalStream() }
            var offset = 0
            for (segment in segments) {
                val lacing = segment.toInt() and 0xFF
                stream.packet.write(body, offset, lacing)
                offset += lacing
                if (lacing < 255) stream.finishPacket()
            }
            pos += PAGE_HEADER + segments.size + bodyLength
        }
        for (stream in streams.values) {
            if (stream.packet.size() > 0) stream.finishPacket()
            digest.update(stream.digest.digest())
        }
    }

    private class LogicalStream {
        val packet = ByteArrayOutputStream()
        val digest = sha256()
        private var index = 0
        private var codec = Codec.OTHER

        fun finishPacket() {
            val bytes = packet.toByteArray()
            packet.reset()
            if (index == 0) codec = Codec.of(bytes)
            if (!codec.isTagPacket(index, bytes)) {
                digest.updateLength(bytes.size.toLong())
                digest.update(bytes)
            }
            index++
        }
    }

    private enum class Codec {
        VORBIS, OPUS, FLAC, OTHER;

        fun isTagPacket(index: Int, packet: ByteArray): Boolean = when (this) {
            VORBIS -> packet.startsWith(VORBIS_COMMENT)
            OPUS -> packet.startsWith(OPUS_TAGS)
            // Ogg FLAC carries each metadata block in its own packet; audio frames start with 0xFF.
            FLAC -> index > 0 && packet.isNotEmpty() && packet.u8(0) != 0xFF &&
                (packet.u8(0) and 0x7F) in FLAC_TAG_BLOCKS
            OTHER -> index == 1 // Speex and friends put their comments in the second packet
        }

        companion object {
            fun of(firstPacket: ByteArray) = when {
                firstPacket.startsWith(VORBIS_IDENTIFICATION) -> VORBIS
                firstPacket.startsWith(OPUS_HEAD) -> OPUS
                firstPacket.startsWith(FLAC_MAPPING) -> FLAC
                else -> OTHER
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val VORBIS_IDENTIFICATION = byteArrayOf(1) + "vorbis".ascii()
    private val VORBIS_COMMENT = byteArrayOf(3) + "vorbis".ascii()
    private val OPUS_HEAD = "OpusHead".ascii()
    private val OPUS_TAGS = "OpusTags".ascii()
    private val FLAC_MAPPING = byteArrayOf(0x7F) + "FLAC".ascii()
    private val FLAC_TAG_BLOCKS = setOf(1, 4, 6) // PADDING, VORBIS_COMMENT, PICTURE
}
