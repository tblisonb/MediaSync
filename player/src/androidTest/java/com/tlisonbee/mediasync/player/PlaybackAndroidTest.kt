package com.tlisonbee.mediasync.player

import android.app.NotificationManager
import android.content.ComponentName
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tlisonbee.mediasync.player.library.Track
import com.tlisonbee.mediasync.player.playback.MediaItems
import com.tlisonbee.mediasync.player.playback.PlaybackService
import com.tlisonbee.mediasync.player.playback.Queue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 1 on the phone, with its own Music folder: the scan, then the playback service through a
 * controller as the screens use it, muted (the player's volume is 0). Only reads the music. Lets
 * the player in with All files access for the test, then puts the setting back.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val thread = HandlerThread("controller").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var controller: MediaController

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    /** Runs [block] on the controller's thread and waits for it. */
    private fun <T> onController(block: (MediaController) -> T): T {
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        handler.post {
            result = runCatching { block(controller) }
            done.countDown()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, check: (MediaController) -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (onController(check)) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            onController { it.stop(); it.clearMediaItems() }
            Thread.sleep(1500) // the empty queue is saved
            handler.post { controller.release() }
        }
        thread.quitSafely()
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE default")
    }

    private fun upcomingIds() = onController { c -> Queue.upcoming(c).map { c.getMediaItemAt(it).mediaId } }

    @Test
    fun scanThenPlayAnAlbumShuffledWithAQueue() {
        val library = context.app.library
        assumeTrue("no Music folder", library.root.isDirectory)

        val start = System.nanoTime()
        runBlocking { library.scan() }
        val data = library.data.value!!
        Log.i(TAG, "scan: ${(System.nanoTime() - start) / 1_000_000} ms, ${data.tracks.size} tracks, ${data.albums.size} albums, " + "${data.artists.size} artists, ${library.playlists.value.size} playlists, ${data.tracks.count { it.cover != null }} with a cover")
        assertTrue(library.scan.value.lastError == null)
        val album = data.albums.filter { it.tracks.size >= 6 }.maxByOrNull { it.tracks.size }
        assumeTrue("no album with six tracks", album != null)
        album!!
        val others = data.tracks.filter { it.albumKey != album.key }.take(3)

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).setApplicationLooper(thread.looper).buildAsync()
        controller = future.get(10, TimeUnit.SECONDS)

        // Played from its third track, as tapping it would, muted.
        onController { c ->
            c.volume = 0f
            c.shuffleModeEnabled = false
            c.repeatMode = Player.REPEAT_MODE_OFF
            c.setMediaItems(album.tracks.map { MediaItems.request(it) }, 2, 0)
            c.prepare()
            c.play()
        }
        waitFor("playing") { it.isPlaying }
        assertEquals(album.tracks[2].path, onController { it.currentMediaItem?.mediaId })
        assertEquals(album.tracks.drop(3).map(Track::path), upcomingIds())
        // The service filled in the rest of the item from the library.
        assertEquals(album.title, onController { it.currentMediaItem?.mediaMetadata?.albumTitle?.toString() })

        // Shuffle on: the current track stays, everything else is still to come.
        onController { it.shuffleModeEnabled = true }
        waitFor("shuffled") { it.shuffleModeEnabled }
        val shuffled = upcomingIds()
        assertEquals(album.tracks.size - 1, shuffled.size)
        assertEquals((album.tracks.map(Track::path) - album.tracks[2].path).toSet(), shuffled.toSet())

        // Play next, then two added to the queue: they play in that order, before the shuffled rest.
        onController { c -> c.addMediaItems(c.currentMediaItemIndex + 1, listOf(MediaItems.request(others[0], queued = true))) }
        waitFor("play next") { it.mediaItemCount == album.tracks.size + 1 }
        onController { c -> c.addMediaItems(Queue.insertionIndex(c), listOf(MediaItems.request(others[1], queued = true))) }
        waitFor("queued") { it.mediaItemCount == album.tracks.size + 2 }
        onController { c -> c.addMediaItems(Queue.insertionIndex(c), listOf(MediaItems.request(others[2], queued = true))) }
        waitFor("queued again") { it.mediaItemCount == album.tracks.size + 3 }
        assertEquals(others.map(Track::path), upcomingIds().take(3))
        assertEquals(shuffled, upcomingIds().drop(3))

        // Shuffle off: the queued ones still come next, then the album from where it was.
        onController { it.shuffleModeEnabled = false }
        waitFor("unshuffled") { !it.shuffleModeEnabled }
        assertEquals(others.map(Track::path) + album.tracks.drop(3).map(Track::path), upcomingIds())

        // While playing, there's a media notification.
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue("no media notification", notifications.any { it.notification.extras.containsKey("android.mediaSession") })

        onController { it.seekToNext() }
        waitFor("next") { it.currentMediaItem?.mediaId == others[0].path }

        // The queue is on disk, for picking up where it left off.
        Thread.sleep(1500)
        val saved = File(context.filesDir, "queue/queue.txt").readLines().drop(2)
        assertEquals(album.tracks.size + 3, saved.size)
        assertEquals("q ${others[0].path}", saved[3])
        val position = File(context.filesDir, "queue/position.txt").readText().trim().split(' ')
        assertEquals("3", position[0])
    }

    private companion object {
        const val TAG = "PlayerPhase1"
    }
}
