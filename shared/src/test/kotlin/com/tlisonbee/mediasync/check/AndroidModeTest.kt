package com.tlisonbee.mediasync.check

import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.Picture
import org.jaudiotagger.tag.TagOptionSingleton
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The fixture round trip with jaudiotagger in its Android mode, which can't decode images. M0 on a
 * phone found FLAC and Ogg covers failing there; this keeps it from coming back without a phone.
 */
class AndroidModeTest {
    @Test
    fun `covers round-trip in android mode`() {
        val fixtures = Path.of(javaClass.getResource("/fixtures")!!.toURI())
        val scratch = Files.createTempDirectory("mediasync-android-mode")
        AudioTags.useAndroidMode()
        try {
            for (format in listOf("jpg", "png")) {
                val cover = cover(format)
                val files = RoundTripCheck.audioFilesUnder(fixtures).filterNot { it.toString().endsWith(".opus") }
                val failures = files.map { RoundTripCheck.run(it, scratch, cover) }.filterNot { it.ok }
                assertTrue(failures.isEmpty(), "$format cover: " + failures.joinToString("\n") { "${it.file.fileName}: ${it.problems}" })
            }
        } finally {
            TagOptionSingleton.getInstance().isAndroid = false
            scratch.toFile().deleteRecursively()
        }
    }

    private fun cover(format: String): Picture {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB), format, it) }
        return Picture(if (format == "png") "image/png" else "image/jpeg", bytes.toByteArray())
    }
}
