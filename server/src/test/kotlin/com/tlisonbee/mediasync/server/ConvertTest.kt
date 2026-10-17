package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.RevertRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvertTest {
    private val fixtures = Path.of(System.getProperty("mediasync.fixtures"))

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
        assertEquals(2, repo.wmaTracks().size)
        assertTrue(repo.leftovers().isEmpty(), "tracks, not leftovers")

        val web = browser()
        web.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) })
        assertTrue("Convert 2 to MP3" in web.get("/cleanup").bodyAsText())
        val started = web.submitForm("/cleanup/wma", parameters { })
        assertEquals(HttpStatusCode.Found, started.status)
        val job = repo.conversion.get()!!
        val deadline = System.currentTimeMillis() + 60_000
        while (!job.finished && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(job.finished)
        assertEquals(listOf("broken.wma"), job.failed)

        val head = repo.headVersion(good.assetId)!!
        assertEquals(AssetKind.AUDIO, head.kind)
        assertEquals("music/Fixture Album Artist/Fixture Album/03 Fixture Tone", head.path)
        assertEquals(listOf("Fixture Tone"), head.tags["title"])
        assertEquals("mp3", repo.media(head.renditions.single())!!.format)
        assertEquals(1, head.pictures.size, "the cover came along")
        val page = web.get("/cleanup/wma").bodyAsText()
        assertTrue("Saved as" in page && "broken.wma" in page, page)
        assertEquals(1, repo.wmaTracks().size, "the broken one stays")

        repo.revert(repo.webDeviceId(), RevertRequest(rev = job.rev!!))
        assertEquals(good.path, repo.headVersion(good.assetId)!!.path)
        assertEquals(AssetKind.OTHER, repo.headVersion(good.assetId)!!.kind)
        assertEquals(good.renditions.map { it.mediaHash }, repo.headVersion(good.assetId)!!.renditions)
    }
}
