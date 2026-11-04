package com.tlisonbee.mediasync.player

import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tlisonbee.mediasync.player.audio.PlayerRenderers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * TIDAL's streams as Segue plays them: a v1 DASH manifest (FLAC in fragmented MP4) through the
 * player's own audio path, muted, from the start and after a seek. Needs a fresh manifest, whose
 * segment addresses are signed: `-e mpd <base64>` (the fooyin plugin's probe saves one).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TidalDashAndroidTest {
    @Test
    fun playsAndSeeks() {
        val mpd = InstrumentationRegistry.getArguments().getString("mpd")
        assumeTrue("no -e mpd", !mpd.isNullOrEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "tidal-test.mpd").apply { writeBytes(Base64.decode(mpd, Base64.DEFAULT)) }

        val frames = AtomicLong()
        val loud = AtomicLong()
        var rate = 0
        val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                rate = sampleRateHz
            }

            override fun handleBuffer(buffer: ByteBuffer) {
                val b = buffer.duplicate().order(ByteOrder.nativeOrder())
                val n = b.remaining() / 4
                frames.addAndGet(n.toLong())
                for (i in 0 until n) if (abs(b.short.toInt()) > 1000) loud.incrementAndGet().also { b.short } else b.short
            }
        })
        val thread = HandlerThread("tidal-test").apply { start() }
        val handler = Handler(thread.looper)
        lateinit var player: ExoPlayer
        var error: PlaybackException? = null
        fun onPlayer(block: () -> Unit) {
            val done = java.util.concurrent.CountDownLatch(1)
            handler.post { block(); done.countDown() }
            done.await()
        }
        onPlayer {
            player = ExoPlayer.Builder(context, PlayerRenderers(context, listOf(tee)))
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context)))
                .setLooper(thread.looper).build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = e
                }
            })
            player.setMediaItem(MediaItem.Builder().setUri(android.net.Uri.fromFile(file)).setMimeType(MimeTypes.APPLICATION_MPD).build())
            player.prepare()
            player.play()
        }
        fun waitFrames(n: Long) {
            val end = System.currentTimeMillis() + 30_000
            while (frames.get() < n && error == null && System.currentTimeMillis() < end) Thread.sleep(100)
        }
        try {
            waitFrames(5 * 44_100L)
            error?.let { throw it }
            var duration = 0L
            onPlayer { duration = player.duration }
            Log.i(TAG, "start: ${frames.get()} frames at $rate Hz, ${loud.get()} loud samples, duration $duration ms")
            assertTrue("played ${frames.get()} frames", frames.get() >= 5 * 44_100L)
            assertTrue("silent", loud.get() > 10_000)
            assertTrue("duration $duration", duration > 60_000)

            onPlayer { player.seekTo(120_000) }
            frames.set(0)
            loud.set(0)
            waitFrames(3 * 44_100L)
            error?.let { throw it }
            var position = 0L
            onPlayer { position = player.currentPosition }
            Log.i(TAG, "after the seek: at $position ms, ${frames.get()} frames, ${loud.get()} loud samples")
            assertTrue("at $position ms", position in 120_000..140_000)
            assertTrue("silent after the seek", loud.get() > 5_000)
        } finally {
            onPlayer { player.release() }
            thread.quitSafely()
            file.delete()
        }
    }

    private companion object {
        const val TAG = "SegueTidal"
    }
}
