package com.tlisonbee.mediasync.tags

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.TagPolicy
import com.tlisonbee.mediasync.media.MediaHasher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** ReplayGain values go into every format MediaSync tags, come back as written, and aren't extras. */
class ReplayGainTagsTest {
    private val gains = mapOf(
        TagField.REPLAYGAIN_TRACK_GAIN to listOf("-6.02 dB"),
        TagField.REPLAYGAIN_TRACK_PEAK to listOf("0.988000"),
        TagField.REPLAYGAIN_ALBUM_GAIN to listOf("-7.10 dB"),
        TagField.REPLAYGAIN_ALBUM_PEAK to listOf("1.000000"),
    )

    @Test
    fun `every tagged format keeps them`() {
        val dir = Files.createTempDirectory("mediasync-rg")
        try {
            for (name in listOf("tone.mp3", "tone-vbr-id3v24.mp3", "tone-id3v1.mp3", "tone.flac", "tone.ogg", "tone-aac.m4a")) {
                val file = dir.resolve(name)
                Files.copy(fixture(name), file, StandardCopyOption.REPLACE_EXISTING)
                val media = MediaHasher.hash(file)
                val tags = mapOf("title" to listOf("Tone"), "artist" to listOf("Someone")) + gains
                TagWriter.writeVerified(file, TagSet(tags))
                assertEquals(media, MediaHasher.hash(file), name)
                val read = AudioTags.inspect(file)
                assertEquals(tags, read.tags.fields, name)
                assertFalse(read.hasExtras, "$name: ReplayGain counted as an extra")
                assertEquals("-6.02 dB", read.replayGain["REPLAYGAIN_TRACK_GAIN"], name)
                // Written again unchanged, the file reads the same (no duplicate frames).
                TagWriter.writeVerified(file, TagSet(tags))
                assertEquals(tags, AudioTags.inspect(file).tags.fields, "$name, written twice")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a policy saved before ReplayGain still keeps it`() {
        val old = TagPolicy(fields = mapOf(AssetKind.AUDIO to listOf(TagField.TITLE)))
        assertEquals(mapOf(TagField.TITLE to listOf("x")) + gains, old.filter(AssetKind.AUDIO, mapOf(TagField.TITLE to listOf("x"), "comment" to listOf("y")) + gains))
    }

    private fun fixture(name: String): Path = Path.of(javaClass.getResource("/fixtures/$name")!!.toURI())
}
