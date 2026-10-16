package com.tlisonbee.mediasync.tags

/** Canonical tag field names, as stored in the repository. */
object TagField {
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val ALBUM_ARTIST = "albumartist"
    const val DATE = "date"
    const val GENRE = "genre"
    const val TRACK_NUMBER = "tracknumber"
    const val DISC_NUMBER = "discnumber"

    /** The default music tag policy: everything else is stripped. */
    val MUSIC = listOf(TITLE, ARTIST, ALBUM, ALBUM_ARTIST, DATE, GENRE, TRACK_NUMBER, DISC_NUMBER)

    // Videos rarely carry usable tags, so theirs live in the repository only.
    const val YEAR = "year"
    const val SHOW = "show"
    const val SEASON = "season"
    const val EPISODE = "episode"

    /** A movie's title and year; an episode's show, season, episode and title. */
    val VIDEO = listOf(TITLE, YEAR, SHOW, SEASON, EPISODE)
}

class Picture(val mimeType: String, val data: ByteArray) {
    override fun equals(other: Any?) = other is Picture && mimeType == other.mimeType && data.contentEquals(other.data)
    override fun hashCode() = 31 * mimeType.hashCode() + data.contentHashCode()
    override fun toString() = "Picture($mimeType, ${data.size} bytes)"
}

/** The allowed tags of one asset: field name to its values (fields like artist may repeat). */
data class TagSet(
    val fields: Map<String, List<String>>,
    val frontCover: Picture? = null,
) {
    operator fun get(field: String): List<String> = fields[field].orEmpty()

    companion object {
        val EMPTY = TagSet(emptyMap())
    }
}
