package com.tlisonbee.mediasync.media

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** Builds files of each format around the same payload and checks only the payload counts. */
class MediaHasherTest {
    private val dir: Path = Files.createTempDirectory("mediasync-hash-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun hash(bytes: ByteArray, extension: String): MediaHash {
        val file = Files.createTempFile(dir, "file", ".$extension")
        Files.write(file, bytes)
        return MediaHasher.hash(file)
    }

    // ---- MP3 ----

    @Test
    fun `mp3 frame lengths`() {
        assertEquals(417, Mp3Payload.frameLength(0xFFFB9000.toInt())) // MPEG 1 Layer III, 128 kbit/s, 44.1 kHz
        assertEquals(418, Mp3Payload.frameLength(0xFFFB9200.toInt())) // ... padded
        assertEquals(null, Mp3Payload.frameLength(0xFFFB0000.toInt())) // free format
    }

    @Test
    fun `mp3 hash ignores ID3v2, junk before the first frame, APE and ID3v1`() {
        val audio = mp3Frames(seed = 1)
        val bare = hash(audio, "mp3")
        assertEquals(MediaFormat.MP3, bare.format)
        assertEquals(bare, hash(id3v2(100) + audio + id3v1(), "mp3"))
        assertEquals(bare, hash(id3v2(2000) + ByteArray(50) + audio + apeTag() + id3v1(), "mp3"))
        assertNotEquals(bare, hash(id3v2(100) + mp3Frames(seed = 2) + id3v1(), "mp3"))
    }

    private fun mp3Frames(seed: Int, count: Int = 8): ByteArray {
        val random = Random(seed)
        val out = ByteArrayOutputStream()
        repeat(count) {
            out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
            out.write(random.nextBytes(413).map { if (it == 0xFF.toByte()) 0 else it }.toByteArray())
        }
        return out.toByteArray()
    }

    private fun id3v2(size: Int): ByteArray =
        "ID3".ascii() + byteArrayOf(3, 0, 0) + syncsafe(size) + ByteArray(size) { 'x'.code.toByte() }

    private fun id3v1(): ByteArray = "TAG".ascii() + ByteArray(125) { 'y'.code.toByte() }

    private fun apeTag(): ByteArray {
        val items = ByteArray(40) { 'z'.code.toByte() }
        val footer = "APETAGEX".ascii() + le32(2000) + le32(items.size + 32) + le32(1) + le32(0) + ByteArray(8)
        return items + footer
    }

    // ---- FLAC ----

    @Test
    fun `flac hash covers STREAMINFO and frames only`() {
        val streamInfo = Random(3).nextBytes(34)
        val frames = Random(4).nextBytes(5000)
        val plain = hash(flac(block(0, streamInfo), block(4, comment("A")), last = block(1, ByteArray(10))) + frames, "flac")
        assertEquals(MediaFormat.FLAC, plain.format)

        val retagged = flac(block(0, streamInfo), block(4, comment("Something much longer")), block(6, ByteArray(300)), last = block(1, ByteArray(4000))) + frames
        assertEquals(plain, hash(retagged, "flac"))
        assertEquals(plain, hash(id3v2(64) + flac(last = block(0, streamInfo)) + frames, "flac"))

        assertNotEquals(plain, hash(flac(last = block(0, Random(5).nextBytes(34))) + frames, "flac"))
        assertNotEquals(plain, hash(flac(last = block(0, streamInfo)) + Random(6).nextBytes(5000), "flac"))
    }

    @Test
    fun `truncated flac falls back to opaque`() {
        val result = hash("fLaC".ascii() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(10), "flac")
        assertEquals(MediaFormat.OPAQUE, result.format)
        assertNotNull(result.fallbackReason)
    }

    private fun flac(vararg blocks: ByteArray, last: ByteArray): ByteArray {
        last[0] = (last[0].toInt() or 0x80).toByte()
        return blocks.fold("fLaC".ascii()) { acc, b -> acc + b } + last
    }

    private fun block(type: Int, data: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data

    private fun comment(text: String): ByteArray = text.toByteArray()

    // ---- MP4 ----

    @Test
    fun `mp4 hash covers mdat only, wherever it sits`() {
        val samples = Random(7).nextBytes(4000)
        val ftyp = box("ftyp", "M4A ".ascii() + ByteArray(4))
        val moovFirst = hash(ftyp + box("moov", Random(8).nextBytes(100)) + box("mdat", samples), "m4a")
        assertEquals(MediaFormat.MP4, moovFirst.format)
        assertEquals(moovFirst, hash(ftyp + box("mdat", samples) + box("moov", Random(9).nextBytes(300)) + box("free", ByteArray(50)), "m4a"))
        assertEquals(moovFirst, hash(ftyp + box("moov", ByteArray(20)) + largeBox("mdat", samples), "m4a"))
        assertNotEquals(moovFirst, hash(ftyp + box("moov", ByteArray(20)) + box("mdat", Random(10).nextBytes(4000)), "m4a"))
    }

    private fun box(type: String, payload: ByteArray): ByteArray = be32(8 + payload.size) + type.ascii() + payload

    private fun largeBox(type: String, payload: ByteArray): ByteArray =
        be32(1) + type.ascii() + be32(0) + be32(16 + payload.size) + payload

    // ---- Ogg ----

    @Test
    fun `ogg vorbis hash ignores the comment packet and pagination`() {
        val identification = byteArrayOf(1) + "vorbis".ascii() + Random(11).nextBytes(23)
        val setup = byteArrayOf(5) + "vorbis".ascii() + Random(12).nextBytes(600)
        val audio = (0 until 12).map { Random(100 + it).nextBytes(150 + 40 * it) }
        fun vorbis(comment: String) = listOf(identification, byteArrayOf(3) + "vorbis".ascii() + comment.toByteArray(), setup) + audio

        val onePerPage = hash(ogg(vorbis("short"), segmentsPerPage = 255, packetPerPage = true), "ogg")
        assertEquals(MediaFormat.OGG, onePerPage.format)
        assertEquals(onePerPage, hash(ogg(vorbis("a".repeat(70_000)), segmentsPerPage = 7, packetPerPage = false), "ogg"))

        val changedAudio = listOf(identification, byteArrayOf(3) + "vorbis".ascii(), setup) + audio.dropLast(1) + listOf(ByteArray(10))
        assertNotEquals(onePerPage, hash(ogg(changedAudio, segmentsPerPage = 255, packetPerPage = true), "ogg"))
    }

    @Test
    fun `ogg opus hash ignores OpusTags`() {
        val head = "OpusHead".ascii() + Random(13).nextBytes(11)
        val audio = (0 until 6).map { Random(200 + it).nextBytes(120) }
        fun opus(tags: String) = listOf(head, "OpusTags".ascii() + tags.toByteArray()) + audio
        assertEquals(
            hash(ogg(opus("x"), segmentsPerPage = 255, packetPerPage = true), "opus"),
            hash(ogg(opus("y".repeat(5000)), segmentsPerPage = 3, packetPerPage = false), "opus"),
        )
    }

    /** Paginates [packets] into one logical stream: lacing values split across pages as asked. */
    private fun ogg(packets: List<ByteArray>, segmentsPerPage: Int, packetPerPage: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        var sequence = 0
        val lacing = mutableListOf<Int>()
        val body = ByteArrayOutputStream()
        fun flush() {
            if (lacing.isEmpty()) return
            out.write("OggS".ascii() + byteArrayOf(0, 0) + ByteArray(8) + le32(0x1234) + le32(sequence++) + ByteArray(4))
            out.write(lacing.size)
            lacing.forEach { out.write(it) }
            out.write(body.toByteArray())
            lacing.clear()
            body.reset()
        }
        for (packet in packets) {
            var remaining = packet.size
            var offset = 0
            while (true) {
                val segment = minOf(remaining, 255)
                lacing += segment
                body.write(packet, offset, segment)
                offset += segment
                remaining -= segment
                if (lacing.size == segmentsPerPage) flush()
                if (segment < 255) break
            }
            if (packetPerPage) flush()
        }
        flush()
        return out.toByteArray()
    }

    // ---- Anything else ----

    @Test
    fun `unknown files are hashed whole`() {
        val bytes = Random(14).nextBytes(1000)
        val result = hash(bytes, "txt")
        assertEquals(MediaFormat.OPAQUE, result.format)
        assertEquals(sha256().digest(bytes).toHex(), result.hex)
    }

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
}
