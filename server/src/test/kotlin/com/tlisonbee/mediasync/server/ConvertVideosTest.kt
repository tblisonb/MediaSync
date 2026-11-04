package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.PushChange
import com.tlisonbee.mediasync.api.RevertRequest
import com.tlisonbee.mediasync.library.SubscriptionRule
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConvertVideosTest {
    /** A two-second VP8/Vorbis WebM, its tone at [hz] so each is a different file. */
    private fun webm(hz: Int): ByteArray {
        val out = Files.createTempFile("mediasync-test", ".webm")
        try {
            val process = ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=24:duration=2", "-f", "lavfi", "-i", "sine=frequency=$hz:duration=2",
                "-c:v", "libvpx", "-b:v", "200k", "-c:a", "libvorbis", "-f", "webm", out.toString(),
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg couldn't make a WebM" }
            return Files.readAllBytes(out)
        } finally {
            Files.deleteIfExists(out)
        }
    }

    private suspend fun ServerFixture.loggedIn(): HttpClient = browser().also {
        it.submitForm("/login", parameters { append("password", ADMIN_PASSWORD) })
    }

    /** Pushes [bytes] as the video at [source] (a path on the PC), the way the clients do. */
    private suspend fun TestDevice.import(source: String, bytes: ByteArray): AssetDto {
        val file = TestDevice.file(source.substringAfterLast('/'), bytes)
        upload(file)
        val path = "videos/" + source.removePrefix("/home/test/Videos/").substringBeforeLast('.')
        return push(PushChange(path = path, kind = AssetKind.VIDEO, mediaHash = file.mediaHash, sourcePath = source)).results.single().asset
    }

    private fun ServerFixture.formatOf(id: String) = repo.media(repo.headVersion(id)!!.renditions.single())!!

    @Test
    fun webmVideosBecomeHevcMkvsAFolderARevision() = withServer {
        if (!Ffmpeg.available) return@withServer // needs ffmpeg, like the other conversion tests
        repo.inBackground = { _, work -> work() }
        val pc = pair("PC").apply { subscribe(SubscriptionRule("videos/", "/home/test/Videos")) }
        val one = pc.import("/home/test/Videos/TV Shows/Show/Show.S01E01.webm", webm(440))
        val two = pc.import("/home/test/Videos/TV Shows/Show/Show.S01E02.webm", webm(550))
        val movie = pc.import("/home/test/Videos/Movies/Film (2010).webm", webm(660))
        val broken = pc.import("/home/test/Videos/Movies/Broken (2011).webm", TestDevice.fakeFile("Broken (2011).webm").bytes)
        val mp4 = pc.import("/home/test/Videos/Movies/Kept (2012).mp4", TestDevice.fakeFile("Kept (2012).mp4").bytes)
        assertEquals(4, repo.convertibleVideos()["webm"]!!.size)
        assertEquals(setOf("webm"), repo.convertibleVideos().keys, "MP4s are left as they are")

        val web = loggedIn()
        assertTrue("Convert the ticked formats to HEVC" in web.get("/cleanup").bodyAsText())
        val started = web.submitForm("/cleanup/videos", parameters { append("format", "webm"); append("later", "on") })
        assertEquals(HttpStatusCode.Found, started.status)
        val job = repo.videoConversion.get()!!
        assertTrue(job.finished)
        assertNull(job.error)
        assertEquals(3, job.done)
        assertEquals(1, job.failed.size, job.failed.joinToString())
        assertTrue(job.failed.single().startsWith("Broken (2011).webm"), job.failed.single())
        assertEquals(listOf("webm"), repo.settings.convertVideos)

        for (video in listOf(one, two, movie)) {
            val head = repo.headVersion(video.assetId)!!
            assertEquals(video.path, head.path, "it keeps its place")
            assertEquals(video.tags, head.tags)
            val media = formatOf(video.assetId)
            assertEquals("mkv", media.format)
            val probe = Ffmpeg.probe(repo.dir.blob(media.baseBlob))!!
            assertEquals("hevc", probe.video)
            assertEquals(listOf("aac"), probe.audio)
        }
        assertEquals("webm", formatOf(broken.assetId).format, "the broken one stays")
        assertEquals("mp4", formatOf(mp4.assetId).format)
        val messages = repo.revisions(null, 2).revisions.map { it.message }
        // Folders go in path order (movies/ before shows/), the newest revision first.
        assertEquals(listOf("Converted 2 videos in shows/Show/Season 01 to HEVC (2 WEBM)", "Converted Film (2010) to HEVC (1 WEBM)"), messages)
        assertTrue("Finished: 3 of 4 converted" in web.get("/cleanup/videos").bodyAsText())

        // One added later is converted when it arrives.
        val later = pc.import("/home/test/Videos/TV Shows/Show/Show.S01E03.webm", webm(770))
        assertEquals("mkv", formatOf(later.assetId).format)

        // Reverting a folder's revision brings its originals back.
        repo.revert(repo.webDeviceId(), RevertRequest(rev = job.revs.last()))
        assertEquals(one.renditions.map { it.mediaHash }, repo.headVersion(one.assetId)!!.renditions)
        assertEquals("webm", formatOf(two.assetId).format)

        // Stop turns converting on arrival off.
        web.submitForm("/cleanup/videos/stop", parameters { })
        assertEquals(emptyList(), repo.settings.convertVideos)
    }

    @Test
    fun aStoppedRunWritesNothing() = withServer {
        if (!Ffmpeg.available) return@withServer
        val pc = pair("PC").apply { subscribe(SubscriptionRule("videos/", "/home/test/Videos")) }
        val video = pc.import("/home/test/Videos/Movies/Film (2010).webm", webm(440))
        val videos = repo.convertibleVideos().getValue("webm")
        val job = VideoConversion(videos.size, 2.0).apply { stop() }
        repo.convertVideos(repo.webDeviceId(), videos, mapOf(video.assetId to 2.0), job)
        assertEquals(0, job.done)
        assertEquals(emptyList(), job.revs)
        assertEquals("webm", formatOf(video.assetId).format)
    }
}
