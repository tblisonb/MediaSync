package com.tlisonbee.mediasync.tags

import com.tlisonbee.mediasync.media.MediaHasher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An ID3v2 tag whose UTF-16 text happens to look like MPEG frames: each byte-order mark FF FE
 * followed by "C" reads as a Layer I frame header, and two of them sit one frame length apart.
 * 53 real tracks were like this. Deleting the tag and reading the file again let jaudiotagger take
 * the first one for the start of the audio, so every write kept the old tag inside the audio and
 * TagWriter refused it.
 */
class Mp3TagWriteTest {
    @Test
    fun `a tag that looks like audio is replaced, not kept`() {
        val dir = Files.createTempDirectory("mediasync-mp3")
        try {
            val file = dir.resolve("trap.mp3")
            Files.write(file, trapTag() + audioOf(fixture("tone.mp3")))
            val before = MediaHasher.hash(file)

            TagWriter.writeVerified(file, TagSet(mapOf("title" to listOf("Catalyst"), "artist" to listOf("Aviators"))))
            assertEquals(before, MediaHasher.hash(file))
            assertEquals(listOf("Catalyst"), AudioTags.read(file).fields["title"])
            // Nothing between the new tag and the first frame: the old tag is gone.
            val bytes = Files.readAllBytes(file)
            val tagEnd = 10 + synchsafe(bytes, 6)
            assertEquals(0xFF, bytes[tagEnd].toInt() and 0xFF)
            // The frames, as in the fixture; an ID3v1 tag at the end is dropped, as it always was.
            assertEquals(audioOf(fixture("tone.mp3")).size - id3v1Size(fixture("tone.mp3")), Files.size(file) - tagEnd - id3v1Size(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun fixture(name: String): Path = Path.of(javaClass.getResource("/fixtures/$name")!!.toURI())

    /** The file without its leading ID3v2 tag. */
    private fun audioOf(path: Path): ByteArray {
        val bytes = Files.readAllBytes(path)
        val start = if (bytes.decodeToString(0, 3) == "ID3") 10 + synchsafe(bytes, 6) else 0
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun id3v1Size(path: Path): Long {
        val bytes = Files.readAllBytes(path)
        return if (bytes.size >= 128 && bytes.decodeToString(bytes.size - 128, bytes.size - 125) == "TAG") 128 else 0
    }

    /**
     * ID3v2.3 with TIT2 (UTF-16, starting with "C") at byte 10, so its byte-order mark is at 21;
     * TRCK; and TPE1 (UTF-16, "C…") placed so its mark is at 161, one 140-byte Layer I frame later.
     */
    private fun trapTag(): ByteArray {
        fun frame(id: String, body: ByteArray) = id.toByteArray() + intBytes(body.size) + byteArrayOf(0, 0) + body
        fun utf16(text: String) = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val frames = frame("TIT2", utf16("C" + "x".repeat(56))) + frame("TRCK", byteArrayOf(0) + "12".toByteArray()) + frame("TPE1", utf16("Catalyst"))
        val body = frames + ByteArray(256)
        val size = body.size
        val header = "ID3".toByteArray() + byteArrayOf(3, 0, 0) +
            byteArrayOf((size shr 21 and 127).toByte(), (size shr 14 and 127).toByte(), (size shr 7 and 127).toByte(), (size and 127).toByte())
        return (header + body).also { check(it[21] == 0xFF.toByte() && it[161] == 0xFF.toByte()) }
    }

    private fun intBytes(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    private fun synchsafe(bytes: ByteArray, at: Int) =
        (0..3).fold(0) { acc, i -> (acc shl 7) or (bytes[at + i].toInt() and 127) }
}
