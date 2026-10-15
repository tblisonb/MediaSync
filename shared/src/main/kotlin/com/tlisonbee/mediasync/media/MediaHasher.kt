package com.tlisonbee.mediasync.media

import java.nio.file.Path
import java.security.MessageDigest

enum class MediaFormat { MP3, FLAC, MP4, OGG, OPAQUE }

/**
 * Identity of a file's media payload (audio frames, image data) with its tags left out, so a tag
 * edit never changes it. For [MediaFormat.OPAQUE] files it is the SHA-256 of the whole file.
 */
data class MediaHash(
    val format: MediaFormat,
    val hex: String,
    /** Why a file of a known format was hashed as opaque instead, if it was. */
    val fallbackReason: String? = null,
) {
    override fun toString() = "${format.name.lowercase()}:$hex"
}

object MediaHasher {
    // Bump when a parser changes what it covers: every stored media hash depends on it.
    private const val VERSION = "mediasync-media-v1"

    /**
     * [name] supplies the extension when the file itself has none, as with the server's blobs,
     * which are named by their hash.
     */
    fun hash(path: Path, name: String = path.fileName.toString()): MediaHash = FileBytes(path).use { file ->
        val format = detect(name, file)
        if (format == MediaFormat.OPAQUE) return opaque(file, fallbackReason = null)
        try {
            val digest = sha256()
            digest.update("$VERSION\n${format.name}\n".ascii())
            when (format) {
                MediaFormat.MP3 -> Mp3Payload.digest(file, digest)
                MediaFormat.FLAC -> FlacPayload.digest(file, digest)
                MediaFormat.MP4 -> Mp4Payload.digest(file, digest)
                MediaFormat.OGG -> OggPayload.digest(file, digest)
                MediaFormat.OPAQUE -> error("unreachable")
            }
            MediaHash(format, digest.digest().toHex())
        } catch (e: MalformedMediaException) {
            opaque(file, fallbackReason = "${format.name}: ${e.message}")
        }
    }

    /** Sniffs the content; the extension only decides for MP3, whose frame sync is too weak alone. */
    internal fun detect(name: String, file: FileBytes): MediaFormat {
        val afterId3 = Id3v2.skipLeading(file, 0)
        return when {
            file.startsWith(afterId3, "fLaC".ascii()) -> MediaFormat.FLAC
            file.startsWith(4, "ftyp".ascii()) -> MediaFormat.MP4
            file.startsWith(0, "OggS".ascii()) -> MediaFormat.OGG
            name.substringAfterLast('.', "").equals("mp3", ignoreCase = true) -> MediaFormat.MP3
            else -> MediaFormat.OPAQUE
        }
    }

    private fun opaque(file: FileBytes, fallbackReason: String?): MediaHash {
        val digest = sha256()
        file.digestRange(digest, 0, file.size)
        return MediaHash(MediaFormat.OPAQUE, digest.digest().toHex(), fallbackReason)
    }
}

internal fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun MessageDigest.updateLength(length: Long) {
    for (shift in 56 downTo 0 step 8) update((length ushr shift).toByte())
}
