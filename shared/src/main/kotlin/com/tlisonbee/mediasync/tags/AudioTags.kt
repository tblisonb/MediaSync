package com.tlisonbee.mediasync.tags

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads and writes the allowed tags of an audio file through jaudiotagger. Writing replaces every
 * tag in the file, so fields outside the allowlist disappear. Callers should go through
 * [TagWriter], which protects the media payload.
 */
object AudioTags {
    private val keys = mapOf(
        TagField.TITLE to FieldKey.TITLE,
        TagField.ARTIST to FieldKey.ARTIST,
        TagField.ALBUM to FieldKey.ALBUM,
        TagField.ALBUM_ARTIST to FieldKey.ALBUM_ARTIST,
        TagField.DATE to FieldKey.YEAR,
        TagField.GENRE to FieldKey.GENRE,
        TagField.TRACK_NUMBER to FieldKey.TRACK,
        TagField.DISC_NUMBER to FieldKey.DISC_NO,
    )

    // jaudiotagger logs routine events at every level, up to SEVERE for growing an M4A's metadata,
    // so its log is useless to us; failures still arrive as exceptions. Held here because
    // java.util.logging only keeps weak references to loggers, and a collected one would forget its level.
    @Suppress("unused")
    private val jaudiotaggerLog = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }

    /** Android has no java.awt or javax.imageio; jaudiotagger must avoid them there. */
    fun useAndroidMode() {
        TagOptionSingleton.getInstance().isAndroid = true
    }

    /** Canonical names this class can read and write. */
    val FIELDS: Set<String> = keys.keys

    fun read(path: Path): TagSet = inspect(path).tags

    /** Everything MediaSync wants from one parse of the file. */
    class Inspection(
        val tags: TagSet,
        /** Every field the file's tag holds, by its format's own id, before any policy. */
        val raw: Map<String, List<String>>,
        val durationMs: Long?,
        /**
         * The file holds something besides the fields in [FIELDS] and one front cover: a comment,
         * an encoder note, a second picture. Writing [tags] back would remove it.
         */
        val hasExtras: Boolean,
    )

    fun inspect(path: Path): Inspection {
        val audio = AudioFileIO.read(path.toFile())
        val duration = runCatching { (audio.audioHeader.preciseTrackLength * 1000).toLong() }.getOrNull()?.takeIf { it > 0 }
        val tag = audio.tag ?: return Inspection(TagSet.EMPTY, emptyMap(), duration, hasExtras = false)
        val fields = keys.mapNotNull { (name, key) ->
            val values = tag.getAll(key).filter { it.isNotBlank() }
            if (values.isEmpty()) null else name to values
        }.toMap()
        val cover = tag.artworkList.firstOrNull { it.pictureType == PictureTypes.DEFAULT_ID } ?: tag.firstArtwork
        val raw = LinkedHashMap<String, MutableList<String>>()
        for (field in tag.fields) {
            val value = if (field.isBinary) "(binary)" else field.toString()
            raw.getOrPut(field.id) { mutableListOf() } += value
        }
        // Counted rather than listed: formats name their fields differently, but every field outside
        // the allowed ones adds to the total.
        val allowedCount = keys.values.sumOf { key -> tag.getFields(key).size } + minOf(tag.artworkList.size, 1)
        // Vorbis comments carry the encoder's VENDOR string, which is part of the format, not a tag.
        val vendor = raw["VENDOR"]?.size ?: 0
        val hasExtras = tag.artworkList.size > 1 || tag.fieldCount - vendor > allowedCount
        return Inspection(TagSet(fields, cover?.let { Picture(it.mimeType, it.binaryData) }), raw, duration, hasExtras)
    }

    fun write(path: Path, tags: TagSet) {
        val file = path.toFile()
        AudioFileIO.delete(AudioFileIO.read(file))
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        for ((name, values) in tags.fields) {
            val key = keys[name] ?: throw IllegalArgumentException("'$name' is not an allowed tag field")
            values.forEachIndexed { i, value -> if (i == 0) tag.setField(key, value) else tag.addField(key, value) }
        }
        tags.frontCover?.let { cover ->
            val artwork = ArtworkFactory.getNew()
            artwork.binaryData = cover.data
            artwork.mimeType = cover.mimeType
            artwork.pictureType = PictureTypes.DEFAULT_ID
            tag.setField(artwork)
        }
        audio.commit()
    }
}
