package com.tlisonbee.mediasync.media

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Positional reads over a file, for the payload parsers. Never modifies the file. */
internal class FileBytes(path: Path) : Closeable {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    val size: Long = channel.size()

    /** Exactly [length] bytes at [position]; throws if the file ends first. */
    fun read(position: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position + buffer.position())
            if (n < 0) throw MalformedMediaException("unexpected end of file at ${position + buffer.position()}")
        }
        return buffer.array()
    }

    /** Up to [length] bytes at [position]; shorter only at the end of the file. */
    fun readUpTo(position: Long, length: Int): ByteArray =
        read(position, minOf(length.toLong(), (size - position).coerceAtLeast(0)).toInt())

    fun startsWith(position: Long, magic: ByteArray): Boolean =
        position >= 0 && position + magic.size <= size && read(position, magic.size).contentEquals(magic)

    /** Feeds bytes [start, end) into [digest]. */
    fun digestRange(digest: MessageDigest, start: Long, end: Long) {
        val buffer = ByteBuffer.allocate(CHUNK)
        var position = start
        while (position < end) {
            buffer.clear()
            buffer.limit(minOf(CHUNK.toLong(), end - position).toInt())
            val n = channel.read(buffer, position)
            if (n < 0) throw MalformedMediaException("unexpected end of file at $position")
            buffer.flip()
            digest.update(buffer)
            position += n
        }
    }

    override fun close() = channel.close()

    private companion object {
        const val CHUNK = 1 shl 16
    }
}

class MalformedMediaException(message: String) : IOException(message)

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u24be(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)

internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u32le(i: Int): Long =
    (u8(i + 3).toLong() shl 24) or (u8(i + 2).toLong() shl 16) or (u8(i + 1).toLong() shl 8) or u8(i).toLong()

internal fun ByteArray.u64be(i: Int): Long = (u32be(i) shl 32) or u32be(i + 4)

/** ID3v2 sizes use 7 bits per byte so they never contain an MPEG frame sync. */
internal fun ByteArray.syncsafe(i: Int): Int = (u8(i) shl 21) or (u8(i + 1) shl 14) or (u8(i + 2) shl 7) or u8(i + 3)

internal fun String.ascii(): ByteArray = toByteArray(Charsets.US_ASCII)
