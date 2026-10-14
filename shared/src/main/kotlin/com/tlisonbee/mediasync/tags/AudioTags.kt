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

    fun read(path: Path): TagSet {
        val tag = AudioFileIO.read(path.toFile()).tag ?: return TagSet.EMPTY
        val fields = keys.mapNotNull { (name, key) ->
            val values = tag.getAll(key).filter { it.isNotBlank() }
            if (values.isEmpty()) null else name to values
        }.toMap()
        val cover = tag.artworkList.firstOrNull { it.pictureType == PictureTypes.DEFAULT_ID } ?: tag.firstArtwork
        return TagSet(fields, cover?.let { Picture(it.mimeType, it.binaryData) })
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
