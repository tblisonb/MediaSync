package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.server.editTags
import com.tlisonbee.mediasync.tags.TagField
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.name
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoSyncTest {
    /** A JPEG of noise, so two of them never compress to the same bytes (which would make them one photo). */
    private fun jpeg(seed: Int): ByteArray {
        val random = Random(seed)
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 16) for (x in 0 until 16) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun files(root: Path): List<String> = Files.walk(root).use { s ->
        s.filter { Files.isRegularFile(it) }.map { root.relativize(it).joinToString("/") { p -> p.name } }.sorted().toList()
    }

    @Test
    fun picturesAreFiledByYearOnEveryPc() = TestWorld().use { world ->
        val desktop = world.client("Desktop", photos = true)
        val pictures = desktop.music.resolveSibling("Pictures")
        Files.createDirectories(pictures.resolve("Phone backup"))
        Files.write(pictures.resolve("Phone backup/IMG_20240714_153012.jpg"), jpeg(1))
        Files.write(pictures.resolve("scan_017.jpg"), jpeg(2))
        Files.writeString(pictures.resolve("Phone backup/notes.txt"), "from the old phone")
        val report = desktop.sync()
        assertEquals(3, report.pushed, report.warnings.joinToString())
        assertEquals(
            listOf("2024/2024-07-14 IMG_20240714_153012.jpg", "Phone backup/notes.txt", "Undated/scan_017.jpg"),
            files(pictures),
            "photos are filed by their date; anything else stays where it was",
        )

        // Renamed by hand, it moves back, as a track does.
        Files.move(pictures.resolve("2024/2024-07-14 IMG_20240714_153012.jpg"), pictures.resolve("2024/beach.jpg"))
        desktop.sync()
        assertTrue(Files.exists(pictures.resolve("2024/2024-07-14 IMG_20240714_153012.jpg")), files(pictures).toString())

        // A second PC gets the same folders; dating the scan in the web UI moves it on both.
        val laptop = world.client("Laptop", photos = true)
        laptop.sync()
        val laptopPictures = laptop.music.resolveSibling("Pictures")
        assertEquals(files(pictures), files(laptopPictures))
        val scan = world.repo.liveIn("photos/Undated").single()
        world.repo.editTags(world.repo.webDeviceId(), "Dated a scan", mapOf(scan.assetId to mapOf(TagField.DATE to listOf("2008"))))
        desktop.sync()
        laptop.sync()
        for (root in listOf(pictures, laptopPictures)) {
            assertEquals(listOf("2008/2008 scan_017.jpg", "2024/2024-07-14 IMG_20240714_153012.jpg", "Phone backup/notes.txt"), files(root))
        }
    }
}
