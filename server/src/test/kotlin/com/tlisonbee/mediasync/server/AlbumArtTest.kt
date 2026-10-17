package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.RevertRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlbumArtTest {
    private val album = "music/Radiohead/OK Computer"

    private suspend fun ServerFixture.loggedIn(): HttpClient = browser().also {
        assertEquals(HttpStatusCode.Found, it.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) }).status)
    }

    /** Pushes a picture into the album's folder, as a device that had it there would. */
    private suspend fun TestDevice.picture(name: String, bytes: ByteArray): AssetDto {
        val file = TestDevice.file(name, bytes)
        upload(file)
        val change = PushChange(path = "$album/${name.substringBeforeLast('.')}", kind = AssetKind.IMAGE, mediaHash = file.mediaHash, sourcePath = "/home/test/Music/$name")
        return push(change).results.single().asset
    }

    private fun ServerFixture.head(asset: AssetDto) = repo.headVersion(asset.assetId)!!

    @Test
    fun picturesAreComparedByWhatTheyShow() {
        fun info(bytes: ByteArray) = Files.createTempFile("art", ".img").let { file ->
            try {
                Files.write(file, bytes)
                Images.info(file)!!
            } finally {
                Files.delete(file)
            }
        }
        val big = info(picture(seed = 1, width = 1200))
        assertEquals(1200, big.width)
        assertTrue(sameImage(big, info(picture(seed = 1, width = 150))), "a smaller copy")
        assertTrue(sameImage(big, info(picture(seed = 1, width = 500, format = "png"))), "a PNG copy")
        assertFalse(sameImage(big, info(picture(seed = 2, width = 1200))), "another picture")
        assertFalse(sameImage(big, info(picture(seed = 1, width = 1200, height = 800))), "another shape")
        assertFalse(sameImage(big, info(picture(seed = 1, width = 1200, sticker = true))), "a part differs")
        assertNull(Images.info(Files.createTempFile("art", ".jpg").also { Files.write(it, ByteArray(100)) }))
    }

    @Test
    fun copiesOfACoverComeDownToTheBestOne() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        desktop.add(TestDevice.fakeFile("a.mp3"), track("Airbag", 1))
        val folder = desktop.picture("folder.jpg", picture(seed = 1, width = 300))
        val cover = desktop.picture("cover.jpg", picture(seed = 1, width = 600))
        val small = desktop.picture("AlbumArt.png", picture(seed = 1, width = 200, format = "png"))
        val back = desktop.picture("back.jpg", picture(seed = 2, width = 600))

        val pictures = repo.albumPictures().single()
        assertEquals(2, pictures.groups.size, "the back is another picture")
        assertEquals(listOf(cover.assetId, folder.assetId, small.assetId), pictures.groups[pictures.cover!!].map { it.version.assetId })

        // Keeping the names: the biggest copy stays.
        val keep = repo.artPlans(ArtOptions()).single()
        assertEquals(setOf(folder.assetId, small.assetId), keep.deleted.map { it.version.assetId }.toSet())

        // Named folder: the file already called that keeps the name, and takes the best copy's image.
        val preview = loggedIn().get("/cleanup?art=1&dedupe=on&name=folder").bodyAsText()
        assertTrue("cover.jpg (600×600" in preview && "→ folder.jpg" in preview && "folder.jpg (300×300" in preview && " replaced" in preview, preview)
        val rev =repo.tidyAlbumArt(repo.webDeviceId(), ArtOptions(coverName = "folder"))!!
        assertEquals(folder.path, head(folder).path)
        assertEquals(cover.renditions.map { it.mediaHash }, head(folder).renditions)
        assertTrue(head(cover).deleted)
        assertTrue(head(small).deleted)
        assertEquals(back.rev, head(back).rev)
        assertTrue(repo.artPlans(ArtOptions(coverName = "folder")).none { it.changes })
        assertEquals("Tidied the album art in 1 album", repo.revision(rev)!!.message)

        repo.revert(repo.webDeviceId(), RevertRequest(rev = rev))
        assertEquals(folder.renditions.map { it.mediaHash }, head(folder).renditions)
        assertFalse(head(cover).deleted)
        assertFalse(head(small).deleted)
    }

    @Test
    fun theCoverIsRenamedAndConvertedFromTheWebPage() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        desktop.add(TestDevice.fakeFile("a.mp3"), track("Airbag", 1))
        val front = desktop.picture("front.jpg", picture(seed = 1, width = 600))
        val named = desktop.picture("Radiohead - OK Computer.jpg", picture(seed = 1, width = 250))
        val web = loggedIn()
        val choices: io.ktor.http.ParametersBuilder.() -> Unit = {
            append("art", "1")
            append("dedupe", "on")
            append("name", "cover")
            append("png", "on")
        }

        val preview = web.get("/cleanup?art=1&dedupe=on&name=cover&png=on").bodyAsText()
        assertTrue("1 album changes: 1 deleted, 1 renamed, 1 converted." in preview, preview)
        assertTrue("front.jpg (600×600" in preview && "→ cover.png" in preview)
        assertTrue("Every album’s art is already tidy" !in preview)

        web.submitForm("/cleanup/art", parameters(choices))
        val cover = head(front)
        assertEquals("$album/cover", cover.path)
        val media = repo.media(cover.renditions.single())!!
        assertEquals("png", media.format)
        val image = ImageIO.read(repo.dir.blob(media.baseBlob).toFile())
        assertEquals(600 to 600, image.width to image.height)
        assertTrue(head(named).deleted)
        assertTrue("already tidy" in web.get("/cleanup?art=1&dedupe=on&name=cover&png=on").bodyAsText())
    }

    @Test
    fun theTracksCoverSaysWhichPictureIsTheFront() = withServer {
        val desktop = pair("Desktop").apply { subscribeMusic() }
        desktop.add(TestDevice.fakeFile("a.mp3"), track("Airbag", 1))
        val scan1 = desktop.picture("scan1.jpg", picture(seed = 1, width = 600))
        val scan2 = desktop.picture("scan2.jpg", picture(seed = 2, width = 600))
        assertNull(repo.albumPictures().single().cover, "two pictures, neither named as the cover")
        assertTrue(repo.artPlans(ArtOptions(coverName = "folder")).none { it.changes }, "so nothing is renamed")

        // A track carrying the second picture as its front cover settles it.
        val embedded = TestDevice.file("front.jpg", picture(seed = 2, width = 300))
        desktop.upload(embedded)
        val audio = TestDevice.fakeFile("b.mp3")
        desktop.upload(audio)
        desktop.push(
            PushChange(
                path = "music/import/b", kind = AssetKind.AUDIO, mediaHash = audio.mediaHash, tags = track("Paranoid Android", 2),
                pictures = listOf(PictureRef("front", embedded.blobHash, "image/jpeg")), sourcePath = "/home/test/Music/b.mp3",
            ),
        )
        val pictures = repo.albumPictures().single()
        assertEquals(scan2.assetId, pictures.groups[pictures.cover!!].single().version.assetId)
        repo.tidyAlbumArt(repo.webDeviceId(), ArtOptions(coverName = "folder"))
        assertEquals("$album/folder", head(scan2).path)
        assertEquals(scan1.path, head(scan1).path)
    }

    /**
     * Seeded shapes drawn in proportion to the picture, so copies at other sizes look alike; with
     * [sticker], one corner holds a block of its own.
     */
    private fun picture(seed: Int, width: Int, height: Int = width, format: String = "jpg", sticker: Boolean = false): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(width / 100.0, height / 100.0)
        val random = Random(seed)
        g.color = Color(random.nextInt(0x1000000))
        g.fillRect(0, 0, 100, 100)
        repeat(8) {
            g.color = Color(random.nextInt(0x1000000))
            g.fillOval(random.nextInt(80), random.nextInt(80), 20 + random.nextInt(40), 20 + random.nextInt(40))
        }
        if (sticker) {
            g.color = Color.WHITE
            g.fillRect(0, 0, 14, 14)
        }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }
}
