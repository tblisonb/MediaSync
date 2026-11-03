package com.tlisonbee.mediasync.player

import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tlisonbee.mediasync.tags.AudioTags
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 0: how long the index's first scan of the phone's Music folder would take with :shared's
 * tag reading. Only reads files. Lets the player in with All files access for the test (as the user
 * would), then puts the setting back.
 */
@RunWith(AndroidJUnit4::class)
class ScanSpeedAndroidTest {
    private fun shell(command: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Test
    fun readTheTagsOfMusic() {
        AudioTags.useAndroidMode()
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        try {
            val music = File(Environment.getExternalStorageDirectory(), "Music")
            assumeTrue("no Music folder", music.isDirectory)

            val walkStart = System.nanoTime()
            val audio = music.walkTopDown().filter { it.isFile && it.extension.lowercase() in AUDIO }.toList()
            val walkMs = (System.nanoTime() - walkStart) / 1_000_000
            assumeTrue("no music in $music", audio.isNotEmpty())

            val sample = audio.shuffled(java.util.Random(1)).take(SAMPLE)
            var failed = 0
            var withCover = 0
            val readStart = System.nanoTime()
            for (file in sample) {
                runCatching { AudioTags.inspect(file.toPath()) }
                    .onSuccess { if (it.tags.frontCover != null) withCover++ }
                    .onFailure { failed++ }
            }
            val perFileMs = (System.nanoTime() - readStart) / 1e6 / sample.size
            Log.i(
                TAG,
                "Music: ${audio.size} audio files, listed in $walkMs ms. Tags of ${sample.size}: " +
                    "%.1f ms a file (%d failed, %d with a cover), so about %.0f s for all.".format(perFileMs, failed, withCover, perFileMs * audio.size / 1000),
            )
        } finally {
            shell("appops set $pkg MANAGE_EXTERNAL_STORAGE default")
        }
    }

    private companion object {
        const val TAG = "PlayerPhase0"
        const val SAMPLE = 400
        val AUDIO = setOf("mp3", "flac", "m4a", "ogg", "opus")
    }
}
