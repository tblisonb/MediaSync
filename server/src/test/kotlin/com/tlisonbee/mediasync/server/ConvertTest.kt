package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PictureRef
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.library.TagPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvertTest {
    private val fixtures = Path.of(System.getProperty("mediasync.fixtures"))

    private suspend fun ServerFixture.loggedIn(): HttpClient = browser().also {
        it.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) })
    }

    private fun ServerFixture.awaitConversion(): Conversion {
        val job = repo.conversion.get()!!
        val deadline = System.currentTimeMillis() + 60_000
        while (!job.finished && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(job.finished)
        return job
    }

    @Test
    fun wmaTracksBecomeMp3sFiledByTheirTags() = withServer {
        if (!Ffmpeg.available) return@withServer // needs ffmpeg, like the fixtures
        val phone = pair("Phone").apply { subscribeMusic() }
        suspend fun wma(name: String, bytes: ByteArray) = TestDevice.file(name, bytes).let { file ->
            phone.upload(file)
            phone.push(PushChange(path = "music/rips/${name.substringBeforeLast('.')}", kind = AssetKind.OTHER, mediaHash = file.mediaHash, sourcePath = "/x/$name"))
                .results.single().asset
        }
        val good = wma("tone.wma", Files.readAllBytes(fixtures.resolve("tone.wma")))
        wma("broken.wma", TestDevice.fakeFile("broken.wma").bytes)
        assertEquals(2, repo.nonMp3Tracks()["wma"]!!.size)
        assertTrue(repo.leftovers().isEmpty(), "tracks, not leftovers")

        val web = loggedIn()
        assertTrue("Convert the ticked formats to MP3" in web.get("/cleanup").bodyAsText())
        val started = web.submitForm("/cleanup/convert", parameters { append("format", "wma") })
        assertEquals(HttpStatusCode.Found, started.status)
        val job = awaitConversion()
        assertEquals(listOf("broken.wma"), job.failed)
        assertEquals(emptyList(), repo.settings.convertToMp3, "later wasn't ticked")

        val head = repo.headVersion(good.assetId)!!
        assertEquals(AssetKind.AUDIO, head.kind)
        assertEquals("music/Fixture Album Artist/Fixture Album/03 Fixture Tone", head.path)
        assertEquals(listOf("Fixture Tone"), head.tags["title"])
        assertEquals("mp3", repo.media(head.renditions.single())!!.format)
        assertEquals(1, head.pictures.size, "the cover came along")
        assertEquals("Converted 1 track to MP3 (1 WMA)", repo.revisions(null, 1).revisions.single().message)
        val page = web.get("/cleanup/convert").bodyAsText()
        assertTrue("Saved as" in page && "broken.wma" in page, page)
        assertEquals(1, repo.nonMp3Tracks()["wma"]!!.size, "the broken one stays")

        repo.revert(repo.webDeviceId(), RevertRequest(rev = job.rev!!))
        assertEquals(good.path, repo.headVersion(good.assetId)!!.path)
        assertEquals(AssetKind.OTHER, repo.headVersion(good.assetId)!!.kind)
        assertEquals(good.renditions.map { it.mediaHash }, repo.headVersion(good.assetId)!!.renditions)
    }

    @Test
    fun tracksKeepTheirTagsCoverAndPlace() = withServer {
        if (!Ffmpeg.available) return@withServer
        val pc = pair("PC").apply { subscribeMusic() }
        val m4a = TestDevice.file("song.m4a", Files.readAllBytes(fixtures.resolve("tone-aac.m4a")))
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val cover = TestDevice.file("cover.jpg", jpeg)
        pc.upload(cover)
        val aac = pc.add(m4a, track("Airbag", 1))
        pc.push(pc.retag(aac.assetId, track("Airbag", 1)).copy(pictures = listOf(PictureRef(TagPolicy.FRONT_COVER, cover.blobHash, "image/jpeg"))))
        assertEquals(1, repo.headVersion(aac.assetId)!!.pictures.size)
        val flac = pc.add(TestDevice.file("lucky.flac", Files.readAllBytes(fixtures.resolve("tone.flac"))), track("Lucky", 11))
        pc.add(TestDevice.file("song.mp3", Files.readAllBytes(fixtures.resolve("tone.mp3"))), track("Paranoid Android", 2))
        assertEquals(setOf("m4a", "flac"), repo.nonMp3Tracks().keys, "MP3s are left out")
        val before = repo.headVersion(aac.assetId)!!

        val web = loggedIn()
        val page = web.get("/cleanup").bodyAsText()
        assertTrue("(lossless)" in page, "FLAC is marked lossless")
        assertTrue(Regex("""value="flac"(?![^>]*checked)""").containsMatchIn(page), "and left unticked")
        web.submitForm("/cleanup/convert", parameters { append("format", "m4a") })
        val job = awaitConversion()
        assertEquals(emptyList(), job.failed)

        val head = repo.headVersion(aac.assetId)!!
        assertEquals("mp3", repo.media(head.renditions.single())!!.format)
        assertEquals(before.path, head.path)
        assertEquals(before.tags, head.tags, "MediaSync's tags, not what ffmpeg read")
        assertEquals(before.pictures, head.pictures)
        assertEquals("flac", repo.formatOf(repo.headVersion(flac.assetId)!!), "an unticked format stays")
        assertEquals(setOf("flac"), repo.nonMp3Tracks().keys)

        repo.revert(repo.webDeviceId(), RevertRequest(rev = job.rev!!))
        assertEquals("m4a", repo.formatOf(repo.headVersion(aac.assetId)!!))
    }

    @Test
    fun tracksAddedLaterAreConvertedAsTheyArrive() = withServer {
        if (!Ffmpeg.available) return@withServer
        val pc = pair("PC").apply { subscribeMusic() }
        val web = loggedIn()
        pc.add(TestDevice.file("first.m4a", Files.readAllBytes(fixtures.resolve("tone-aac.m4a"))), track("Airbag", 1))
        web.submitForm("/cleanup/convert", parameters { append("format", "m4a"); append("later", "on") })
        awaitConversion()
        assertEquals(listOf("m4a"), repo.settings.convertToMp3)
        assertTrue("are converted to MP3 as they arrive" in web.get("/cleanup").bodyAsText())

        val later = pc.add(TestDevice.file("later.m4a", Files.readAllBytes(fixtures.resolve("tone-alac.m4a"))), track("Lucky", 11))
        val job = awaitConversion()
        assertTrue(job.rev != null)
        assertEquals("mp3", repo.formatOf(repo.headVersion(later.assetId)!!))

        web.submitForm("/cleanup/convert-later", parameters { })
        assertEquals(emptyList(), repo.settings.convertToMp3)
    }
}
