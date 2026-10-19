package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.library.SubscriptionRule
import com.tlisonbee.mediasync.tags.TagField
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotosTest {
    private suspend fun TestDevice.addPhoto(name: String, bytes: ByteArray, kind: AssetKind = AssetKind.IMAGE): AssetDto {
        val file = TestDevice.file(name, bytes)
        upload(file)
        val change = PushChange(path = "photos/import/${name.substringBeforeLast('.')}", kind = kind, mediaHash = file.mediaHash, sourcePath = "/home/test/Pictures/$name")
        return push(change).results.single().asset
    }

    @Test
    fun photosAreDatedByTheCameraOrTheirNameAndFiledByYear() = withServer {
        val pc = pair("PC").apply { subscribe(SubscriptionRule("photos/", "/home/test/Pictures"), SubscriptionRule("music/", "/home/test/Music")) }

        val exif = pc.addPhoto("IMG_1234.jpg", jpeg(seed = 1, taken = "2019:07:14 12:30:05"))
        assertEquals("photos/2019/2019-07-14 IMG_1234", exif.path)
        assertEquals(listOf("2019-07-14"), exif.tags[TagField.DATE])

        // EXIF wins over the name; without EXIF, the name tells.
        assertEquals("photos/2018/2018-01-02 IMG_20240714_153012", pc.addPhoto("IMG_20240714_153012.jpg", jpeg(seed = 2, taken = "2018:01:02 08:00:00")).path)
        assertEquals("photos/2024/2024-07-14 PXL_20240714_153012345", pc.addPhoto("PXL_20240714_153012345.jpg", jpeg(seed = 3)).path)
        assertEquals("photos/2023/2023-12-25 VID_20231225_090000", pc.addPhoto("VID_20231225_090000.mp4", TestDevice.fakeFile("v").bytes, AssetKind.VIDEO).path)

        val scan = pc.addPhoto("scan_017.png", png(seed = 1))
        assertEquals("photos/Undated/scan_017", scan.path)
        assertEquals(null, scan.tags[TagField.DATE])

        // Dating it later moves it; a year is enough.
        repo.editTags(repo.webDeviceId(), "Dated a scan", mapOf(scan.assetId to mapOf(TagField.DATE to listOf("2008"))), mapOf(scan.assetId to scan.rev))
        assertEquals("photos/2008/2008 scan_017", repo.headVersion(scan.assetId)!!.path)

        // A rename on a device doesn't stick: the date names it.
        val renamed = pc.push(PushChange(exif.assetId, exif.rev, "photos/elsewhere/holiday", AssetKind.IMAGE, exif.renditions.single().mediaHash, exif.renditions.single().mediaHash, tags = exif.tags))
        assertEquals("photos/2019/2019-07-14 IMG_1234", renamed.results.single().asset.path)

        // Pictures outside photos/ (album art) are left where they are.
        assertEquals("music/Radiohead/OK Computer/cover", pc.addPhotoAt("cover.jpg", jpeg(seed = 4, taken = "2019:07:14 12:30:05"), "music/Radiohead/OK Computer/cover").path)
    }

    private suspend fun ServerFixture.loggedIn(): HttpClient = browser().also {
        assertEquals(HttpStatusCode.Found, it.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) }).status)
    }

    @Test
    fun photosAreDatedInTheWebUi() = withServer {
        val pc = pair("PC").apply { subscribe(SubscriptionRule("photos/", "/home/test/Pictures")) }
        val first = pc.addPhoto("scan_017.png", png(seed = 1))
        val second = pc.addPhoto("scan_018.png", png(seed = 2))
        val web = loggedIn()

        assertTrue("Date these photos" in web.get("/browse?path=photos/Undated").bodyAsText())
        val page = web.get("/photos?path=photos/Undated").bodyAsText()
        assertTrue("scan_017" in page && "scan_018" in page && "No date" in page, page)

        val thumb = web.get("/web/thumbs/${first.renditions.single().blob}")
        assertEquals(HttpStatusCode.OK, thumb.status)
        assertEquals(ContentType.Image.JPEG, thumb.contentType()?.withoutParameters())
        assertTrue(ImageIO.read(thumb.bodyAsBytes().inputStream()) != null)

        fun form(which: String, date: String, vararg ticked: AssetDto) = parameters {
            append("folder", "photos/Undated")
            append("which", which)
            append("date", date)
            listOf(first, second).forEach { append("rev.${it.assetId}", repo.headVersion(it.assetId)!!.rev.toString()) }
            ticked.forEach { append("asset", it.assetId) }
        }

        val refused = web.submitForm("/photos/date", form("selected", "May", first))
        assertTrue("isn't a date" in URLDecoder.decode(refused.headers["Location"]!!, Charsets.UTF_8))
        assertEquals("photos/Undated/scan_017", repo.headVersion(first.assetId)!!.path)

        web.submitForm("/photos/date", form("selected", "2008-05", first))
        assertEquals("photos/2008/2008-05 scan_017", repo.headVersion(first.assetId)!!.path)
        assertEquals("Dated 1 photo 2008-05", repo.revisions(null, 1).revisions.single().message)

        // "All on this page" covers what's left in Undated.
        web.submitForm("/photos/date", form("page", "2010"))
        assertEquals("photos/2010/2010 scan_018", repo.headVersion(second.assetId)!!.path)
    }

    @Test
    fun thumbnailsAreTheRightWayUp() = withServer {
        val pc = pair("PC").apply { subscribe(SubscriptionRule("photos/", "/home/test/Pictures")) }
        // Stored 40×20 and marked "turn a quarter clockwise", as phones do with portrait photos.
        val sideways = pc.addPhoto("IMG_0001.jpg", jpeg(seed = 5, taken = "2020:01:01 10:00:00", orientation = 6, width = 40, height = 20))
        val thumb = ImageIO.read(repo.thumbnail(sideways.renditions.single().blob)!!.toFile())
        assertEquals(20, thumb.width)
        assertEquals(40, thumb.height)
    }

    private suspend fun TestDevice.addPhotoAt(name: String, bytes: ByteArray, path: String): AssetDto {
        val file = TestDevice.file(name, bytes)
        upload(file)
        return push(PushChange(path = path, kind = AssetKind.IMAGE, mediaHash = file.mediaHash, sourcePath = "/home/test/$name")).results.single().asset
    }

    companion object {
        /**
         * A small JPEG; with [taken], an EXIF block holding DateTimeOriginal ("2019:07:14 12:30:05")
         * and, if given, the orientation.
         */
        fun jpeg(seed: Int, taken: String? = null, orientation: Int? = null, width: Int = 16, height: Int = 16): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, seed * 7919) }
            val plain = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
            if (taken == null) return plain
            return plain.copyOfRange(0, 2) + exifSegment(taken, orientation) + plain.copyOfRange(2, plain.size)
        }

        fun png(seed: Int): ByteArray =
            ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, seed) }, "png", it) }.toByteArray()

        // APP1 "Exif": a big-endian TIFF header; IFD0 with the orientation (if any) and the Exif
        // sub-IFD pointer; that sub-IFD with only DateTimeOriginal (19 characters and a NUL, after it).
        private fun exifSegment(taken: String, orientation: Int?): ByteArray {
            val entries = if (orientation == null) 1 else 2
            val subIfd = 8 + 2 + 12 * entries + 4
            val value = subIfd + 18
            val tiff = ByteBuffer.allocate(value + 20)
            tiff.put("MM".toByteArray()).putShort(42).putInt(8)
            tiff.putShort(entries.toShort())
            orientation?.let { tiff.putShort(0x0112).putShort(3).putInt(1).putShort(it.toShort()).putShort(0) }
            tiff.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(subIfd).putInt(0)
            tiff.putShort(1).putShort(0x9003.toShort()).putShort(2).putInt(20).putInt(value).putInt(0)
            tiff.put(taken.toByteArray()).put(0)
            val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.array().copyOf(tiff.position())
            val length = body.size + 2
            return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
        }
    }
}
