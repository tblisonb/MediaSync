package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.tags.TagField
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoudnessTest {
    /** A 3 s stereo tone at [volume] (1 is full scale), as an MP3. */
    private fun tone(dir: Path, name: String, volume: Double, hz: Int = 440): Path {
        val out = dir.resolve(name)
        val p = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "sine=frequency=$hz:duration=3",
            "-af", "volume=$volume", "-ac", "2", "-c:a", "libmp3lame", "-b:a", "192k", out.toString(),
        ).redirectErrorStream(true).start()
        p.waitFor(60, TimeUnit.SECONDS)
        return out
    }

    /** ffmpeg's own integrated loudness of [file], from its summary. */
    private fun ffmpegIntegrated(file: Path): Double {
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin", "-i", file.toString(), "-af", "ebur128", "-f", "null", "-")
            .redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return Regex("""I:\s*(-?\d+\.\d) LUFS""").findAll(log).last().groupValues[1].toDouble()
    }

    @Test
    fun theGatedLoudnessMatchesFfmpegs() {
        if (!Ffmpeg.available) return
        val dir = Files.createTempDirectory("loudness")
        try {
            val peaks = listOf("loud.mp3" to 0.9, "quiet.mp3" to 0.05).map { (name, volume) ->
                val file = tone(dir, name, volume)
                val m = assertNotNull(Loudness.measure(file))
                val mine = assertNotNull(Loudness.integrated(listOf(m)))
                assertTrue(abs(mine - ffmpegIntegrated(file)) < 0.15, "$name: $mine against ffmpeg's ${ffmpegIntegrated(file)}")
                m.peak
            }
            // (The source's own level isn't full scale, so the peaks are compared with each other.)
            assertEquals(0.9 / 0.05, peaks[0] / peaks[1], 0.5)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun albumGatingAndTags() {
        // Two tracks of one block level each: the album is the energy mean, and silence doesn't count.
        val a = Loudness.Measurement(0.5, mapOf(-200 to 100))
        val b = Loudness.Measurement(0.9, mapOf(-100 to 100))
        val silent = Loudness.Measurement(0.0, emptyMap())
        assertEquals(-20.0, Loudness.integrated(listOf(a))!!, 0.01)
        // -10 and -20 LUFS blocks: the ungated mean is -12.6, so the gate is at -22.6 and both count.
        assertEquals(-12.6, Loudness.integrated(listOf(a, b))!!, 0.05)
        assertNull(Loudness.integrated(listOf(silent)))
        // A 40 LU quieter passage falls below the relative gate.
        assertEquals(-10.0, Loudness.integrated(listOf(b, Loudness.Measurement(0.1, mapOf(-500 to 100))))!!, 0.01)
        val tags = Loudness.tags(listOf(a), listOf(a, b))!!
        assertEquals(listOf("2.00 dB"), tags[TagField.REPLAYGAIN_TRACK_GAIN])
        assertEquals(listOf("0.500000"), tags[TagField.REPLAYGAIN_TRACK_PEAK])
        assertEquals(listOf("-5.40 dB"), tags[TagField.REPLAYGAIN_ALBUM_GAIN])
        assertEquals(listOf("0.900000"), tags[TagField.REPLAYGAIN_ALBUM_PEAK])
        assertEquals(b.blocks, Loudness.Measurement.decode(0.9, b.encode()).blocks)
    }

    @Test
    fun tracksGetTheirValuesAsARevisionAndAgainWhenTheirAlbumChanges() = withServer {
        if (!Ffmpeg.available) return@withServer
        repo.inBackground = { _, work -> work() }
        val dir = Files.createTempDirectory("loudness")
        try {
            val pc = pair("PC").apply { subscribeMusic() }
            val loud = pc.add(TestDevice.file("loud.mp3", Files.readAllBytes(tone(dir, "loud.mp3", 0.9))), track("Loud", 1))
            val quiet = pc.add(TestDevice.file("quiet.mp3", Files.readAllBytes(tone(dir, "quiet.mp3", 0.05, 220))), track("Quiet", 2))
            val broken = pc.add(TestDevice.fakeFile("broken.mp3"), track("Broken", 3))
            repo.updateSettings(repo.settings.copy(measureLoudness = true))

            val job = assertNotNull(repo.startMeasuring())
            assertTrue(job.finished)
            assertEquals(3, job.done)
            assertEquals(1, job.failed.size, "the fake file can't be decoded")
            val loudTags = repo.headVersion(loud.assetId)!!.tags
            val quietTags = repo.headVersion(quiet.assetId)!!.tags
            assertEquals(loudTags[TagField.REPLAYGAIN_ALBUM_GAIN], quietTags[TagField.REPLAYGAIN_ALBUM_GAIN], "one album, one album gain")
            assertTrue(loudTags[TagField.REPLAYGAIN_TRACK_GAIN] != quietTags[TagField.REPLAYGAIN_TRACK_GAIN])
            assertNull(repo.headVersion(broken.assetId)!!.tags[TagField.REPLAYGAIN_TRACK_GAIN])
            assertEquals("Measured the loudness of 2 tracks (ReplayGain)", repo.revisions(null, 1).revisions.single().message)
            assertEquals(listOf("Loud"), loudTags["title"], "the other tags stay")

            // Nothing new: no revision.
            val head = repo.head()
            repo.startMeasuring()
            assertEquals(head, repo.head())

            // The quiet track moves to an album of its own: both albums' values change, without decoding again.
            pc.push(pc.retag(quiet.assetId, track("Quiet", 1, album = "Kid A") + quietTags.filterKeys { it in TagField.REPLAYGAIN }))
            val again = assertNotNull(repo.startMeasuring())
            assertEquals(0, again.done)
            val alone = repo.headVersion(quiet.assetId)!!.tags
            assertEquals(alone[TagField.REPLAYGAIN_TRACK_GAIN], alone[TagField.REPLAYGAIN_ALBUM_GAIN])
            val loudAlone = repo.headVersion(loud.assetId)!!.tags
            assertEquals(loudAlone[TagField.REPLAYGAIN_TRACK_GAIN], loudAlone[TagField.REPLAYGAIN_ALBUM_GAIN])

            // The settings page says how far it got.
            assertTrue(repo.loudnessStatus()!!.startsWith("2 of 3 tracks have values."), repo.loudnessStatus())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
