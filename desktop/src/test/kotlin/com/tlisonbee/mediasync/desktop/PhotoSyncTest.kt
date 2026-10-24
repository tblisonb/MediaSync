package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.server.editTags
import com.tlisonbee.mediasync.server.moveToImages
import com.tlisonbee.mediasync.sync.FolderConfig
import com.tlisonbee.mediasync.sync.SyncEngine
import com.tlisonbee.mediasync.sync.followRenames
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
        val scan = world.repo.liveIn("images/photos/Undated").single()
        world.repo.editTags(world.repo.webDeviceId(), "Dated a scan", mapOf(scan.assetId to mapOf(TagField.DATE to listOf("2008"))))
        desktop.sync()
        laptop.sync()
        for (root in listOf(pictures, laptopPictures)) {
            assertEquals(listOf("2008/2008 scan_017.jpg", "2024/2024-07-14 IMG_20240714_153012.jpg", "Phone backup/notes.txt"), files(root))
        }
    }

    @Test
    fun aPhotosFolderFollowsTheMoveToImages() = TestWorld().use { world ->
        // As server 0.4 had it: photos/ in ~/Pictures, a folder kept by hand beside the years.
        val desktop = world.client("Desktop", photos = true, photoPrefix = RepoPaths.OLD_PHOTOS)
        val pictures = desktop.music.resolveSibling("Pictures")
        Files.createDirectories(pictures.resolve("2024"))
        Files.write(pictures.resolve("2024/2024-07-14 IMG_20240714_153012.jpg"), jpeg(1))
        Files.createDirectories(pictures.resolve("Mom's Stuff"))
        Files.writeString(pictures.resolve("Mom's Stuff/letter.txt"), "dear")
        assertEquals(2, desktop.sync().pushed)
        world.repo.db.exec("UPDATE device SET rescan = 0")

        world.repo.moveToImages()
        // Not updated yet, it leaves the moved files alone.
        val stale = desktop.sync()
        assertEquals(0, stale.deleted + stale.downloadedFiles + stale.renamed, stale.summary())
        assertEquals(listOf("2024/2024-07-14 IMG_20240714_153012.jpg", "Mom's Stuff/letter.txt"), files(pictures))

        // Updated, it follows the rename: the photos stay put, and the folder kept by hand, now
        // in images/other/, leaves this PC (the server still has it).
        val renamed = followRenames(desktop.config, desktop.api, desktop.index)!!
        assertEquals(listOf("music/", RepoPaths.PHOTOS), renamed.folders.map { it.prefix })
        assertEquals(desktop.config.folders.last().folder, renamed.folders.last().folder)
        val engine = SyncEngine(renamed, desktop.api, desktop.index, desktop.dirs.pictures, log = {}, clock = { System.currentTimeMillis() + 60_000 })
        val report = engine.sync()
        assertEquals(0, report.pushed + report.downloadedFiles + report.renamed, report.summary())
        assertEquals(listOf("2024/2024-07-14 IMG_20240714_153012.jpg"), files(pictures))
        assertEquals(null, followRenames(renamed, desktop.api, desktop.index), "once")
        assertTrue(engine.check().isEmpty, "nothing waits")

        // A PC that syncs all of images/ has both, each in its subfolder.
        val laptop = world.client("Laptop", photos = true, photoPrefix = RepoPaths.IMAGES)
        laptop.sync()
        assertEquals(
            listOf("other/Mom's Stuff/letter.txt", "photos/2024/2024-07-14 IMG_20240714_153012.jpg"),
            files(laptop.music.resolveSibling("Pictures")),
        )
    }

    @Test
    fun aSendOnlyCameraFolderKeepsItsNames() = TestWorld().use { world ->
        val phone = world.client("Phone")
        val dcim = Files.createDirectories(phone.music.resolveSibling("DCIM"))
        val config = phone.config.copy(folders = phone.config.folders + FolderConfig(RepoPaths.PHOTOS, dcim.toString(), SyncMode.SEND_ONLY))
        phone.api.putSubscription(config.subscription())
        val engine = SyncEngine(config, phone.api, phone.index, phone.dirs.pictures, log = {}, clock = { System.currentTimeMillis() + 60_000 })
        Files.createDirectories(dcim.resolve("Camera"))
        Files.write(dcim.resolve("Camera/PXL_20240714_153012345.jpg"), jpeg(1))
        assertEquals(1, engine.sync().pushed)
        assertEquals("images/photos/2024/2024-07-14 PXL_20240714_153012345", world.repo.liveUnder("images/photos").single().path)
        assertEquals(listOf("Camera/PXL_20240714_153012345.jpg"), files(dcim), "filing by date is for the library's copy")

        // Renamed on the phone, it stays renamed.
        Files.move(dcim.resolve("Camera/PXL_20240714_153012345.jpg"), dcim.resolve("Camera/beach.jpg"))
        engine.sync()
        assertEquals(listOf("Camera/beach.jpg"), files(dcim))
    }
}
