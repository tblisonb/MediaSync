package com.tlisonbee.mediasync.player

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tlisonbee.mediasync.player.audio.GainProcessor
import com.tlisonbee.mediasync.player.audio.PlayerRenderers
import com.tlisonbee.mediasync.player.audio.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Phase 0: plays the fixtures from player/make-fixtures.sh (one 220 Hz tone cut in two) through
 * ExoPlayer with the player's audio sink, records what reaches the audio output, and checks the cut.
 * Muted: the player's volume is 0, which is applied after the recording.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class GaplessAndroidTest {
    private class Run(
        val frames: ShortArray,
        val audioTracks: Int,
        val underruns: Int,
        val switches: List<Pair<String, Float>>,
    ) {
        /** The largest step between neighbouring samples within [range]. */
        fun maxStep(range: IntRange): Int =
            range.filter { it + 1 < frames.size }.maxOfOrNull { abs(frames[it + 1] - frames[it]) } ?: 0

        /** The loudest sample in each window of one tone period, from [from] for [count] windows. */
        fun peaks(from: Int, count: Int): List<Int> = (0 until count).map { w ->
            val start = from + w * PERIOD
            (start until minOf(start + PERIOD, frames.size)).maxOfOrNull { abs(frames[it].toInt()) } ?: 0
        }
    }

    private fun play(names: List<String>, gains: List<Float>): Run {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "gapless").apply { mkdirs() }
        val files = names.map { name ->
            File(dir, name).also { f -> instrumentation.context.assets.open("gapless/$name").use { i -> f.outputStream().use { i.copyTo(it) } } }
        }

        val pcm = ByteArrayOutputStream()
        var encoding = C.ENCODING_INVALID
        var channels = 0
        val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding0: Int) {
                encoding = encoding0
                channels = channelCount
            }

            override fun handleBuffer(buffer: ByteBuffer) {
                val bytes = ByteArray(buffer.remaining())
                buffer.duplicate().get(bytes)
                pcm.write(bytes)
            }
        })
        val gain = GainProcessor { item -> ReplayGain.factor(item.mediaMetadata.extras?.getFloat(ReplayGain.EXTRA_GAIN_DB) ?: 0f) }

        val thread = HandlerThread("gapless-test").apply { start() }
        val handler = Handler(thread.looper)
        val ended = CountDownLatch(1)
        var error: PlaybackException? = null
        var audioTracks = 0
        var underruns = 0
        lateinit var player: ExoPlayer
        handler.post {
            player = ExoPlayer.Builder(instrumentation.targetContext, PlayerRenderers(instrumentation.targetContext, listOf(gain, tee)))
                .setLooper(thread.looper)
                .build()
            player.volume = 0f
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                    audioTracks++
                }

                override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                    underruns++
                }
            })
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) ended.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    ended.countDown()
                }
            })
            player.setMediaItems(files.mapIndexed { i, f ->
                MediaItem.Builder().setUri(Uri.fromFile(f)).setMediaId(f.name)
                    .setMediaMetadata(MediaMetadata.Builder().setExtras(bundleOf(ReplayGain.EXTRA_GAIN_DB to gains[i])).build())
                    .build()
            })
            player.prepare()
            player.play()
        }
        assertTrue("playback didn't end", ended.await(60, TimeUnit.SECONDS))
        handler.post { player.release() }
        thread.quitSafely()
        thread.join()
        error?.let { throw it }

        assertEquals("16-bit output", C.ENCODING_PCM_16BIT, encoding)
        assertEquals(2, channels)
        val shorts = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.nativeOrder()).asShortBuffer()
        val left = ShortArray(shorts.remaining() / 2) { shorts.get(it * 2) }
        return Run(left, audioTracks, underruns, gain.switches.toList())
    }

    private fun report(name: String, run: Run) {
        Log.i(
            TAG,
            "$name: ${run.frames.size} frames (the tone has $TOTAL), ${run.audioTracks} audio track(s), " +
                "${run.underruns} underrun(s), largest step at the cut ${run.maxStep(CUT - 3000..CUT + 3000)}, " +
                "elsewhere ${run.maxStep(10_000..CUT - 10_000)}; gain switches ${run.switches}",
        )
    }

    /** The two halves play as the tone they were cut from: every sample, no step at the cut. */
    @Test
    fun withTheEncoderHeaderTheCutIsSeamless() {
        val run = play(listOf("with-header-1.mp3", "with-header-2.mp3"), listOf(0f, 0f))
        report("with header", run)
        assertTrue("frames: ${run.frames.size}, want $TOTAL", abs(run.frames.size - TOTAL) <= 8)
        assertEquals("one audio track for both", 1, run.audioTracks)
        val elsewhere = run.maxStep(10_000..CUT - 10_000)
        val atCut = run.maxStep(CUT - 3000..CUT + 3000)
        assertTrue("step at the cut $atCut, elsewhere $elsewhere", atCut <= elsewhere * 3 / 2)
    }

    /** The control: without the header the padding plays, so the check must see a gap. */
    @Test
    fun withoutTheHeaderTheCheckSeesTheGap() {
        val run = play(listOf("no-header-1.mp3", "no-header-2.mp3"), listOf(0f, 0f))
        report("no header", run)
        assertTrue("frames: ${run.frames.size}, should be more than $TOTAL", run.frames.size > TOTAL + 1000)
        val elsewhere = run.maxStep(10_000..CUT - 10_000)
        val near = run.maxStep(CUT - 3000..CUT + 6000)
        val silent = (CUT - 3000 until CUT + 6000).count { abs(run.frames[it].toInt()) < 100 }
        assertTrue("no gap seen: step $near (elsewhere $elsewhere), $silent near-silent frames", near > elsewhere * 2 || silent > 500)
    }

    /** The second half is at -6.02 dB: half the level from its first sample on, not before. */
    @Test
    fun theGainSwitchesExactlyAtTheCut() {
        val run = play(listOf("with-header-1.mp3", "with-header-2.mp3"), listOf(0f, -6.0206f))
        report("gain", run)
        assertEquals("one audio track for both", 1, run.audioTracks)
        val before = run.peaks(CUT - 20 * PERIOD, 19).average()
        val after = run.peaks(CUT + PERIOD, 19).average()
        assertTrue("level before $before, after $after", abs(after / before - 0.5) < 0.03)
        // The first full period at half level starts at the cut, give or take one period.
        val firstHalf = run.peaks(CUT - 10 * PERIOD, 20).indexOfFirst { it < before * 0.75 }
        val at = CUT - 10 * PERIOD + firstHalf * PERIOD
        assertTrue("gain switched at frame $at, the cut is at $CUT", abs(at - CUT) <= 2 * PERIOD)
        assertEquals(listOf(1f, 0.5f), run.switches.map { Math.round(it.second * 100) / 100f }.distinct())
    }

    private companion object {
        const val TAG = "PlayerPhase0"
        const val CUT = 132301 // as in player/make-fixtures.sh
        const val TOTAL = 6 * 44100
        const val PERIOD = 200 // samples in one 220 Hz period, rounded down
    }
}
