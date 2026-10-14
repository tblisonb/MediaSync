package com.tlisonbee.mediasync.media

import java.security.MessageDigest

/**
 * FLAC: the STREAMINFO block plus every audio frame. The other metadata blocks (Vorbis comments,
 * pictures, padding, seek table, application data) are left out; tag writers rewrite them freely.
 */
internal object FlacPayload {
    private const val STREAMINFO = 0

    fun digest(file: FileBytes, digest: MessageDigest) {
        var pos = Id3v2.skipLeading(file, 0)
        if (!file.startsWith(pos, "fLaC".ascii())) throw MalformedMediaException("missing fLaC marker")
        pos += 4

        var sawStreamInfo = false
        while (true) {
            val header = file.read(pos, 4)
            val last = header.u8(0) and 0x80 != 0
            val type = header.u8(0) and 0x7F
            val length = header.u24be(1)
            val dataEnd = pos + 4 + length
            if (dataEnd > file.size) throw MalformedMediaException("metadata block runs past the end of the file")
            if (type == STREAMINFO) {
                file.digestRange(digest, pos + 4, dataEnd)
                sawStreamInfo = true
            }
            pos = dataEnd
            if (last) break
        }
        if (!sawStreamInfo) throw MalformedMediaException("no STREAMINFO block")

        // Some taggers append an ID3v1 tag to FLAC files too.
        val end = if (file.size - 128 >= pos && file.startsWith(file.size - 128, "TAG".ascii())) file.size - 128 else file.size
        file.digestRange(digest, pos, end)
    }
}
