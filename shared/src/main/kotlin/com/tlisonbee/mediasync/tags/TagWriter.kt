package com.tlisonbee.mediasync.tags

import com.tlisonbee.mediasync.media.MediaFormat
import com.tlisonbee.mediasync.media.MediaHash
import com.tlisonbee.mediasync.media.MediaHasher
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class MediaChangedException(val path: Path, val before: MediaHash, val after: MediaHash) :
    IOException("writing tags changed the media of $path ($before -> $after)")

/** The only way MediaSync writes tags: the media payload is guaranteed to come out unchanged. */
object TagWriter {
    /**
     * Writes [tags] to a copy of [path], checks the copy's media hash, then swaps it in atomically.
     * On any failure the original file is left exactly as it was.
     */
    fun writeVerified(path: Path, tags: TagSet): MediaHash {
        val before = MediaHasher.hash(path)
        if (before.format == MediaFormat.OPAQUE) {
            throw IOException("can't safely write tags to $path: ${before.fallbackReason ?: "unsupported format"}")
        }
        // jaudiotagger picks its reader by extension, so the copy keeps the original name at the end.
        val copy = Files.createTempFile(path.parent, ".mediasync-", "-${path.fileName}")
        try {
            Files.copy(path, copy, REPLACE_EXISTING)
            AudioTags.write(copy, tags)
            val after = MediaHasher.hash(copy)
            if (after != before) throw MediaChangedException(path, before, after)
            Files.move(copy, path, REPLACE_EXISTING, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(copy)
        }
        return before
    }
}
