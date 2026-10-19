package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.server.startConversion
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
}
