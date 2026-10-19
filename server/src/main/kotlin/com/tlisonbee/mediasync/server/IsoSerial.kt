package com.tlisonbee.mediasync.server

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

/**
 * Reads which game a PlayStation or PlayStation 2 disc image is: the boot file its SYSTEM.CNF names
 * ("BOOT2 = cdrom0:\SLUS_203.12;1") is the game's serial. Works on .iso images (2048-byte sectors)
 * and raw .bin images (2352-byte sectors, Mode 1 or Mode 2), so a dump that isn't byte-for-byte the
 * one in the Redump list is still recognized.
 */
object IsoSerial {
    data class Boot(val serial: String, val ps2: Boolean)

    private class Layout(val sectorSize: Int, val dataOffset: Int)

    private val LAYOUTS = listOf(Layout(2048, 0), Layout(2352, 24), Layout(2352, 16))
    // Not anchored to a line: inside a raw image, the file's first line follows other bytes.
    private val BOOT = Regex("""(?i)\b(BOOT2?)\s*=\s*cdrom0?:\\?([^;\s]+)""")

    fun read(image: Path): Boot? = try {
        FileChannel.open(image, READ).use { ch -> LAYOUTS.firstNotNullOfOrNull { read(ch, it) } ?: scan(ch) }
    } catch (e: java.io.IOException) {
        null
    }

    /** How far into an image [scan] looks: SYSTEM.CNF is among a disc's first files. */
    private const val SCAN_BYTES = 8 shl 20

    /**
     * An image whose filesystem can't be read as it stands, such as one packed with ECM (Error
     * Code Modeler), which drops each sector's error correction but leaves its data as it was:
     * SYSTEM.CNF's BOOT line is still there to be found.
     */
    private fun scan(ch: FileChannel): Boot? {
        val buf = ByteBuffer.allocate(minOf(SCAN_BYTES.toLong(), ch.size()).toInt())
        while (buf.hasRemaining()) if (ch.read(buf, buf.position().toLong()) < 0) break
        return boot(String(buf.array(), 0, buf.position(), Charsets.ISO_8859_1))
    }

    private fun boot(text: String): Boot? {
        val m = BOOT.find(text) ?: return null
        val file = m.groupValues[2].substringAfterLast('\\').substringAfterLast('/')
        if (!SERIAL.matches(file)) return null
        return Boot(file, ps2 = m.groupValues[1].equals("BOOT2", ignoreCase = true))
    }

    /** What a boot file of a licensed disc looks like: "SLUS_203.12", "SCES_123.45". */
    private val SERIAL = Regex("""[A-Z]{4}[_-]\d{3}\.?\d{2}""")

    private fun read(ch: FileChannel, layout: Layout): Boot? {
        val pvd = sector(ch, layout, 16) ?: return null
        if (pvd[0].toInt() != 1 || String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return null
        val root = ByteBuffer.wrap(pvd, 156, 34).order(ByteOrder.LITTLE_ENDIAN)
        val rootLba = root.getInt(156 + 2)
        val rootSize = root.getInt(156 + 10).coerceIn(0, 64 * 2048)
        val entry = findFile(ch, layout, rootLba, rootSize, "SYSTEM.CNF") ?: return null
        val text = readFile(ch, layout, entry.first, entry.second.coerceAtMost(8192)) ?: return null
        return boot(String(text, Charsets.ISO_8859_1))
    }

    /** The extent and size of [name] in the directory at [lba]. */
    private fun findFile(ch: FileChannel, layout: Layout, lba: Int, size: Int, name: String): Pair<Int, Int>? {
        val dir = readFile(ch, layout, lba, size) ?: return null
        var pos = 0
        while (pos < dir.size) {
            val len = dir[pos].toInt() and 0xff
            if (len == 0) {
                // Records don't cross sectors: the rest of this one is padding.
                pos = (pos / 2048 + 1) * 2048
                continue
            }
            if (pos + 33 > dir.size) break
            val record = ByteBuffer.wrap(dir, pos, len).order(ByteOrder.LITTLE_ENDIAN)
            val nameLen = dir[pos + 32].toInt() and 0xff
            val fileName = String(dir, pos + 33, nameLen.coerceAtMost(dir.size - pos - 33), Charsets.US_ASCII).substringBefore(';')
            if (fileName.equals(name, ignoreCase = true)) return record.getInt(pos + 2) to record.getInt(pos + 10)
            pos += len
        }
        return null
    }

    private fun readFile(ch: FileChannel, layout: Layout, lba: Int, size: Int): ByteArray? {
        if (lba < 0 || size <= 0) return null
        val out = ByteArray(size)
        var done = 0
        var sector = lba
        while (done < size) {
            val data = sector(ch, layout, sector++) ?: return null
            val n = minOf(2048, size - done)
            System.arraycopy(data, 0, out, done, n)
            done += n
        }
        return out
    }

    private fun sector(ch: FileChannel, layout: Layout, lba: Int): ByteArray? {
        val pos = lba.toLong() * layout.sectorSize + layout.dataOffset
        if (pos + 2048 > ch.size()) return null
        val buf = ByteBuffer.allocate(2048)
        while (buf.hasRemaining()) if (ch.read(buf, pos + buf.position()) < 0) return null
        return buf.array()
    }
}
