package com.tlisonbee.mediasync.check

import com.tlisonbee.mediasync.media.MediaFormat
import com.tlisonbee.mediasync.media.MediaHasher
import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.Picture
import com.tlisonbee.mediasync.tags.TagField
import com.tlisonbee.mediasync.tags.TagSet
import com.tlisonbee.mediasync.tags.TagWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.stream.Collectors

/**
 * Milestone 0's question for one file: can tags be rewritten without touching the media? Works on a
 * copy in a scratch folder; the original is only ever read.
 */
object RoundTripCheck {
    val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "mp4", "ogg", "oga", "opus")

    data class Result(
        val file: Path,
        val format: MediaFormat?,
        val problems: List<String>,
        val notes: List<String>,
        val millis: Long,
    ) {
        val ok get() = problems.isEmpty()
    }

    /** Every audio file under [root], sorted, for running the check over a library. */
    fun audioFilesUnder(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.extension() in AUDIO_EXTENSIONS }.sorted().collect(Collectors.toList())
    }

    fun run(original: Path, scratchDir: Path, cover: Picture): Result {
        val started = System.nanoTime()
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var format: MediaFormat? = null
        val copy = scratchDir.resolve("check.${original.extension()}")
        try {
            Files.copy(original, copy, REPLACE_EXISTING)
            val hash = MediaHasher.hash(copy)
            format = hash.format
            if (hash.format == MediaFormat.OPAQUE) {
                problems += "media payload not found (${hash.fallbackReason ?: "format not recognised"})"
                return Result(original, format, problems, notes, elapsed(started))
            }

            val originalTags = AudioTags.read(copy)
            notes += "had ${originalTags.fields.size} allowed fields" + if (originalTags.frontCover != null) " and a cover" else ""

            val testTags = testTags(cover)
            TagWriter.writeVerified(copy, testTags)
            compare("after writing test tags", testTags, AudioTags.read(copy), problems)

            // Repeating fields are a nice-to-have, so a mismatch is only noted.
            val multi = testTags.copy(fields = testTags.fields + (TagField.ARTIST to listOf("First Artist", "Second Artist")))
            TagWriter.writeVerified(copy, multi)
            val readMulti = AudioTags.read(copy)[TagField.ARTIST]
            if (readMulti != multi[TagField.ARTIST]) notes += "two artists read back as $readMulti"

            TagWriter.writeVerified(copy, originalTags)
            compare("after restoring the original tags", originalTags, AudioTags.read(copy), problems)

            if (MediaHasher.hash(copy) != hash) problems += "media hash differs at the end"
        } catch (e: Exception) {
            problems += "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            Files.deleteIfExists(copy)
        }
        return Result(original, format, problems, notes, elapsed(started))
    }

    private fun testTags(cover: Picture) = TagSet(
        mapOf(
            TagField.TITLE to listOf("Round Trip — Ünïcödé 日本語"),
            TagField.ARTIST to listOf("Test Artist"),
            TagField.ALBUM to listOf("Test Album"),
            TagField.ALBUM_ARTIST to listOf("Test Album Artist"),
            TagField.DATE to listOf("1997"),
            TagField.GENRE to listOf("Test Genre"),
            TagField.TRACK_NUMBER to listOf("7"),
            TagField.DISC_NUMBER to listOf("2"),
        ),
        cover,
    )

    private fun compare(stage: String, wrote: TagSet, read: TagSet, problems: MutableList<String>) {
        for (field in (wrote.fields.keys + read.fields.keys).sorted()) {
            if (wrote[field] != read[field]) problems += "$stage: $field wrote ${wrote[field]}, read ${read[field]}"
        }
        if (wrote.frontCover != read.frontCover) {
            problems += "$stage: cover wrote ${wrote.frontCover}, read ${read.frontCover}"
        }
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000

    private fun Path.extension() = fileName.toString().substringAfterLast('.', "").lowercase()
}
