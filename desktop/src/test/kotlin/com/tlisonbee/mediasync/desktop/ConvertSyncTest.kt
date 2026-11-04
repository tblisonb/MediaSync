package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.server.startConversion
import com.tlisonbee.mediasync.server.startVideoConversion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import com.tlisonbee.mediasync.tags.AudioTags
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvertSyncTest {
    private val ffmpeg = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            .let { it.waitFor(30, TimeUnit.SECONDS) && it.exitValue() == 0 }
    }.getOrDefault(false)

    @Test
    fun aTrackConvertedToMp3ReplacesTheOriginalOnEveryPc() = TestWorld().use { world ->
        if (!ffmpeg) return@use // like the server's conversion tests
        val pc = world.client("PC")
        val m4a = Fixtures.copy("tone-aac.m4a", pc.file("rips/airbag.m4a"))
        Fixtures.retag(m4a, Fixtures.album("Radiohead", "OK Computer", "Airbag").single())
        pc.sync()
        val laptop = world.client("Laptop")
        laptop.sync()
        assertEquals(listOf("Radiohead/OK Computer/01 Airbag.m4a"), pc.files())
        assertEquals(pc.files(), laptop.files())

        val job = world.repo.startConversion(world.repo.webDeviceId(), setOf("m4a")) { work -> work() }
        assertTrue(job.rev != null, "converted: ${job.failed}")

        for (client in listOf(pc, laptop)) {
            val report = client.sync()
            assertTrue(report.warnings.isEmpty(), report.warnings.joinToString())
            assertEquals(listOf("Radiohead/OK Computer/01 Airbag.mp3"), client.files(), "the MP3 replaces the .m4a on ${client.name}")
            val tags = AudioTags.inspect(client.file("Radiohead/OK Computer/01 Airbag.mp3")).tags.fields
            assertEquals(listOf("Airbag"), tags["title"])
            assertEquals(listOf("OK Computer"), tags["album"])
            assertEquals(0, client.sync().pushed, "nothing to send back")
        }
    }

    @Test
    fun aVideoConvertedToHevcReplacesTheWebmOnEveryPcAndKeepsItsSubtitles() = TestWorld().use { world ->
        if (!ffmpeg) return@use
        world.repo.inBackground = { _, work -> work() }
        val pc = world.client("PC", videos = false)
        val videos = pc.music.resolveSibling("Videos")
        val source = videos.resolve("TV Shows/Show/Show.S01E01.webm")
        Files.createDirectories(source.parent)
        val made = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=160x90:rate=24:duration=2", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-c:v", "libvpx", "-b:v", "200k", "-c:a", "libvorbis", "-f", "webm", source.toString(),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assertTrue(made.waitFor(60, TimeUnit.SECONDS) && made.exitValue() == 0)
        Files.writeString(source.resolveSibling("Show.S01E01.en.srt"), "1\n00:00:00,000 --> 00:00:01,000\nHello\n")
        pc.sync()
        val laptop = world.client("Laptop", videos = false)
        laptop.sync()
        val filed = listOf("shows/Show/Season 01/Show - S01E01.en.srt", "shows/Show/Season 01/Show - S01E01.webm")
        assertEquals(filed, files(videos))
        assertEquals(filed, files(laptop.music.resolveSibling("Videos")))

        val job = world.repo.startVideoConversion(world.repo.webDeviceId(), setOf("webm"))
        assertTrue(job.finished && job.done == 1, "converted: ${job.failed} ${job.error}")

        for (client in listOf(pc, laptop)) {
            val report = client.sync()
            assertTrue(report.warnings.isEmpty(), report.warnings.joinToString())
            val folder = client.music.resolveSibling("Videos")
            assertEquals(listOf("shows/Show/Season 01/Show - S01E01.en.srt", "shows/Show/Season 01/Show - S01E01.mkv"), files(folder), "the MKV replaces the .webm on ${client.name}")
            assertEquals(0, client.sync().pushed, "nothing to send back")
        }
    }

    private fun files(root: Path): List<String> = Files.walk(root).use { s ->
        s.filter { Files.isRegularFile(it) }.map { root.relativize(it).joinToString("/") { p -> p.name } }.sorted().toList()
    }
}
