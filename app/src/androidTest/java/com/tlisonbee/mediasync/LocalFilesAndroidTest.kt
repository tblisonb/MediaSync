package com.tlisonbee.mediasync

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.media.MediaHasher
import com.tlisonbee.mediasync.sync.FolderConfig
import com.tlisonbee.mediasync.sync.LocalFiles
import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * What the sync engine does to files, on the phone's shared storage (FUSE, case-insensitive):
 * read, retag, rename into the tag-based layout and delete. The JVM tests can't cover this layer.
 */
@RunWith(AndroidJUnit4::class)
class LocalFilesAndroidTest {
    @Test
    fun readRetagMoveAndDeleteOnSharedStorage() {
        AudioTags.useAndroidMode()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // App-specific external storage: the same FUSE filesystem as Music/, without needing a permission.
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "localfiles-test").apply { deleteRecursively(); mkdirs() }
        val folder = FolderConfig("music/", root.path)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val cover = Picture("image/jpeg", ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray())

        for (name in listOf("tone.mp3", "tone.flac", "tone-aac.m4a", "tone.ogg")) {
            val file = File(root, "rips/$name").toPath()
            Files.createDirectories(file.parent)
            instrumentation.context.assets.open("fixtures/$name").use { input -> Files.copy(input, file) }

            val before = LocalFiles.read(file)
            assertEquals(name, AssetKind.AUDIO, before.kind)
            assertTrue("$name: the fixture carries ripper junk", before.hasExtras)

            val tags = mapOf("title" to listOf("Airbag"), "artist" to listOf("Radiohead"), "album" to listOf("OK Computer"), "tracknumber" to listOf("1"))
            LocalFiles.writeTags(file, tags, cover)
            val after = LocalFiles.read(file)
            assertEquals(name, before.media, after.media)
            assertEquals(name, tags, after.tags)
            assertEquals(name, cover, after.cover)
            assertFalse(name, after.hasExtras)

            val target = LocalFiles.localPath(folder, "music/Radiohead/OK Computer/01 Airbag", name.substringAfterLast('.'))
            LocalFiles.move(file, target)
            assertTrue(Files.exists(target))
            assertEquals(before.media, MediaHasher.hash(target).hex)
            assertEquals("music/Radiohead/OK Computer/01 Airbag", LocalFiles.repoPath(folder, target))

            LocalFiles.delete(target, root.toPath())
            assertFalse("empty folders go with the last file", Files.exists(target.parent))
        }
        root.deleteRecursively()
    }
}
