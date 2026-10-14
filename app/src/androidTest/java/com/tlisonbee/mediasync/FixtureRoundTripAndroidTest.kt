package com.tlisonbee.mediasync

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tlisonbee.mediasync.check.RoundTripCheck
import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.Picture
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** The fixture round trip from :shared, on Android, where jaudiotagger runs in its Android mode. */
@RunWith(AndroidJUnit4::class)
class FixtureRoundTripAndroidTest {
    @Test
    fun tagsRoundTripWithoutTouchingTheMedia() {
        AudioTags.useAndroidMode()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        // jaudiotagger 3.0.1 has no Opus support, as in :shared's fixture test.
        val names = assets.list("fixtures").orEmpty()
            .filter { it.substringAfterLast('.') in RoundTripCheck.AUDIO_EXTENSIONS && !it.endsWith(".opus") }
        assumeTrue("no fixtures: run scripts/make-fixtures.sh (needs ffmpeg)", names.isNotEmpty())

        val cache = instrumentation.targetContext.cacheDir
        val fixtures = File(cache, "fixtures").apply { deleteRecursively(); mkdirs() }
        val scratch = File(cache, "scratch").apply { deleteRecursively(); mkdirs() }
        for (name in names) {
            assets.open("fixtures/$name").use { input -> File(fixtures, name).outputStream().use { input.copyTo(it) } }
        }

        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val cover = Picture("image/jpeg", ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray())

        val results = names.map { RoundTripCheck.run(File(fixtures, it).toPath(), scratch.toPath(), cover) }
        val failures = results.filterNot { it.ok }.joinToString("\n") { "${it.file.fileName}: ${it.problems}" }
        assertTrue("round trip failed:\n$failures", failures.isEmpty())
    }
}
