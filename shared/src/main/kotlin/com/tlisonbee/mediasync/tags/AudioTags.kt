package com.tlisonbee.mediasync.tags

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.ID3v1Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.nio.file.Path
import java.util.Base64
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
        if (tag is ID3v1Tag) {
            // An MP3 with only the old fixed-size tag. jaudiotagger can't list its fields, so they
            // are the ones read above, plus the comment, the one thing a rewrite would lose.
            val comment = tag.firstComment.takeIf { it.isNotBlank() }
            val raw = fields + listOfNotNull(comment?.let { "COMMENT" to listOf(it) })
            return Inspection(TagSet(fields, null), raw, duration, hasExtras = comment != null)
        }
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
        val audio = if (path.toString().endsWith(".mp3", ignoreCase = true)) {
            // A fresh tag replaces the old one in a single write. Deleting first is unsafe for MP3:
            // jaudiotagger's delete only blanks the ID3v2 header, and reading the file again can then
            // take frame-like bytes in the old tag (a UTF-16 byte-order mark) for the start of the
            // audio, so the old tag would be written back as if it were audio.
            (AudioFileIO.read(file) as MP3File).apply {
                iD3v1Tag = null
                tag = createDefaultTag()
            }
        } else {
            AudioFileIO.delete(AudioFileIO.read(file))
            AudioFileIO.read(file)
        }
        val tag = audio.tagOrCreateAndSetDefault
        for ((name, values) in tags.fields) {
            val key = keys[name] ?: throw IllegalArgumentException("'$name' is not an allowed tag field")
            values.forEachIndexed { i, value -> if (i == 0) tag.setField(key, value) else tag.addField(key, value) }
        }
        tags.frontCover?.let { cover -> setCover(tag, cover) }
        audio.commit()
    }

    /**
     * FLAC and Vorbis comments store a cover with its width and height. jaudiotagger gets those by
     * decoding the image, which its Android mode can't do, so for them the picture block is built
     * here from the image header instead. Found by M0's run on a phone.
     */
    private fun setCover(tag: Tag, cover: Picture) {
        val (width, height) = ImageSize.of(cover.data) ?: (0 to 0)
        when (tag) {
            is FlacTag -> tag.setField(tag.createArtworkField(cover.data, PictureTypes.DEFAULT_ID, cover.mimeType, "", width, height, 24, 0))
            is VorbisCommentTag -> {
                val block = MetadataBlockDataPicture(cover.data, PictureTypes.DEFAULT_ID, cover.mimeType, "", width, height, 24, 0)
                val bytes = block.bytes.let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
                tag.setField(tag.createField(VorbisCommentFieldKey.METADATA_BLOCK_PICTURE, Base64.getEncoder().encodeToString(bytes)))
            }
            else -> {
                val artwork = ArtworkFactory.getNew()
                artwork.binaryData = cover.data
                artwork.mimeType = cover.mimeType
                artwork.pictureType = PictureTypes.DEFAULT_ID
                tag.setField(artwork)
            }
        }
    }
}
