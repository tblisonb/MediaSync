package com.tlisonbee.mediasync.spike

import com.tlisonbee.mediasync.check.RoundTripCheck
import com.tlisonbee.mediasync.tags.Picture
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val USAGE = """Usage: ./gradlew :spike:run --args="<music folder> [max files]"

Checks every audio file under the folder: copies it, rewrites its tags several times with
jaudiotagger and verifies the media hash never changes. Originals are only read."""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help")) {
        println(USAGE)
        exitProcess(if (args.isEmpty()) 2 else 0)
    }
    val root = System.getenv("MEDIASYNC_START_DIR")?.let { Path.of(it).resolve(args[0]) } ?: Path.of(args[0])
    val limit = args.getOrNull(1)?.toInt() ?: Int.MAX_VALUE
    if (!Files.isDirectory(root)) {
        System.err.println("Not a folder: $root")
        exitProcess(2)
    }

    val files = RoundTripCheck.audioFilesUnder(root).take(limit)
    println("Checking ${files.size} audio files under $root\n")
    val scratch = Files.createTempDirectory(Path.of("").toAbsolutePath(), "m0-")
    val cover = testCover()
    val results = try {
        files.map { file ->
            RoundTripCheck.run(file, scratch, cover).also { result ->
                val status = if (result.ok) "OK  " else "FAIL"
                val format = result.format?.name?.lowercase() ?: "?"
                println("$status %-6s %6d ms  ${root.relativize(file)}".format(format, result.millis))
                result.problems.forEach { println("       - $it") }
                result.notes.filter { "read back" in it }.forEach { println("       note: $it") }
            }
        }
    } finally {
        Files.deleteIfExists(scratch)
    }

    println("\nBy format:")
    results.groupBy { it.format?.name?.lowercase() ?: "?" }.toSortedMap().forEach { (format, group) ->
        println("  %-6s %4d ok, %4d failed".format(format, group.count { it.ok }, group.count { !it.ok }))
    }
    val failed = results.count { !it.ok }
    println(if (failed == 0) "\nAll ${results.size} files passed." else "\n$failed of ${results.size} files failed.")
    exitProcess(if (failed == 0) 0 else 1)
}

private fun testCover(): Picture {
    val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        color = Color(0x3F51B5)
        fillRect(0, 0, 64, 64)
        dispose()
    }
    val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    return Picture("image/jpeg", bytes)
}
