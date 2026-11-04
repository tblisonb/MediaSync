package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.server.startMeasuring
import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.TagField
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoudnessSyncTest {
    private val ffmpeg = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            .let { it.waitFor(30, TimeUnit.SECONDS) && it.exitValue() == 0 }
    }.getOrDefault(false)

    @Test
    fun theServersReplayGainReachesTheFilesAndStaysPut() = TestWorld().use { world ->
        if (!ffmpeg) return@use
        val pc = world.client("PC")
        val tags = Fixtures.album("Radiohead", "OK Computer", "Airbag", "Paranoid Android")
        listOf("tone.mp3", "tone.flac").forEachIndexed { i, name -> Fixtures.retag(Fixtures.copy(name, pc.file("rips/$name")), tags[i]) }
        pc.sync()
        val laptop = world.client("Laptop")
        laptop.sync()

        world.repo.inBackground = { _, work -> work() }
        world.repo.updateSettings(world.repo.settings.copy(measureLoudness = true))
        val job = world.repo.startMeasuring()!!
        assertTrue(job.finished && job.failed.isEmpty(), "measured: ${job.failed}")
        assertEquals(2, job.written)

        for (client in listOf(pc, laptop)) {
            val report = client.sync()
            assertTrue(report.warnings.isEmpty(), report.warnings.joinToString())
            for (file in client.files()) {
                val read = AudioTags.inspect(client.file(file))
                assertEquals(TagField.REPLAYGAIN.map { it.uppercase() }.toSet(), read.replayGain.keys, "$file on ${client.name}")
                assertTrue(!read.hasExtras, "$file: ReplayGain isn't an extra")
            }
            assertEquals(0, client.sync().pushed, "nothing to send back from ${client.name}")
        }
        // The two tracks share an album, so they share an album gain.
        val albumGains = pc.files().map { AudioTags.inspect(pc.file(it)).replayGain["REPLAYGAIN_ALBUM_GAIN"] }.distinct()
        assertEquals(1, albumGains.size, "$albumGains")
    }
}
