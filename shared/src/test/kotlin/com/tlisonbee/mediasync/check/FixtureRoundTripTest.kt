package com.tlisonbee.mediasync.check

import com.tlisonbee.mediasync.tags.Picture
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real encoded files from scripts/make-fixtures.sh, retagged through jaudiotagger. */
class FixtureRoundTripTest {
    @Test
    fun `tags round-trip without touching the media`() {
        val fixtures = javaClass.getResource("/fixtures")?.let { Path.of(it.toURI()) }
        assumeTrue("no fixtures: run scripts/make-fixtures.sh (needs ffmpeg)", fixtures != null)
        // jaudiotagger 3.0.1 has no Opus support at all; `:spike:run` on the fixtures still shows it.
        val files = RoundTripCheck.audioFilesUnder(fixtures!!).filterNot { it.toString().endsWith(".opus") }
        assumeTrue("no fixtures: run scripts/make-fixtures.sh (needs ffmpeg)", files.isNotEmpty())

        val scratch = Files.createTempDirectory("mediasync-fixtures")
        try {
            val results = files.map { RoundTripCheck.run(it, scratch, cover()) }
            val failures = results.filterNot { it.ok }.joinToString("\n") { "${it.file.fileName}: ${it.problems}" }
            assertTrue(failures.isEmpty(), "round trip failed:\n$failures")
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun cover(): Picture {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpg", it) }
        return Picture("image/jpeg", bytes.toByteArray())
    }
}
