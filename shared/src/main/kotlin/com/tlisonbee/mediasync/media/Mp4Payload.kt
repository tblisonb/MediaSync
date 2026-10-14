package com.tlisonbee.mediasync.media

import java.security.MessageDigest

/**
 * MP4/M4A: the contents of every top-level `mdat` box, in order. Tags live in `moov/udta/meta/ilst`;
 * when a tag writer grows `moov` it shifts the chunk offsets, but the sample data is untouched.
 */
internal object Mp4Payload {
    fun digest(file: FileBytes, digest: MessageDigest) {
        var pos = 0L
        var sawMdat = false
        while (pos + 8 <= file.size) {
            val header = file.read(pos, 8)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            val boxSize = when (val size32 = header.u32be(0)) {
                0L -> file.size - pos // runs to the end of the file
                1L -> {
                    headerSize = 16
                    file.read(pos + 8, 8).u64be(0)
                }
                else -> size32
            }
            if (boxSize < headerSize || pos + boxSize > file.size) {
                throw MalformedMediaException("box '$type' at $pos has a bad size ($boxSize)")
            }
            if (type == "mdat") {
                file.digestRange(digest, pos + headerSize, pos + boxSize)
                sawMdat = true
            }
            pos += boxSize
        }
        if (!sawMdat) throw MalformedMediaException("no mdat box")
    }
}
