package com.tlisonbee.mediasync.tags

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.media.MediaHasher
import com.tlisonbee.mediasync.sync.LocalFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An MP3 with only an ID3v1 tag, as rips from around 2000 often have. jaudiotagger can't list such
 * a tag's fields, and that used to make the whole file a plain file: no tags, never renamed.
 */
class Id3v1Test {
    @Test
    fun `an MP3 with only an ID3v1 tag is a track`() {
        val state = LocalFiles.read(fixture())
        assertEquals(AssetKind.AUDIO, state.kind)
        assertEquals(listOf("Fixture Tone"), state.tags["title"])
        assertEquals(listOf("Fixture Artist"), state.tags["artist"])
        assertEquals(listOf("3"), state.tags["tracknumber"])
        val inspection = AudioTags.inspect(fixture())
        assertTrue(inspection.hasExtras, "the comment would go on a rewrite")
        assertEquals(listOf("Ripped by SomeRipper 1.0"), inspection.raw["COMMENT"])
    }

    @Test
    fun `writing its tags gives it an ID3v2 tag and leaves the audio alone`() {
        val dir = Files.createTempDirectory("mediasync-id3v1")
        try {
            val file = Files.copy(fixture(), dir.resolve("track.mp3"), REPLACE_EXISTING)
            val before = MediaHasher.hash(file)
            TagWriter.writeVerified(file, TagSet(mapOf("title" to listOf("Flirtin' With Disaster"), "artist" to listOf("Molly Hatchet"))))
            assertEquals(before, MediaHasher.hash(file))
            assertEquals(listOf("Flirtin' With Disaster"), AudioTags.read(file).fields["title"])
            assertEquals('I'.code.toByte(), Files.readAllBytes(file)[0], "an ID3v2 tag now starts the file")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun fixture(): Path = Path.of(javaClass.getResource("/fixtures/tone-id3v1.mp3")!!.toURI())
}
