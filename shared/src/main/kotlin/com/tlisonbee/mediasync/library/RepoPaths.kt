package com.tlisonbee.mediasync.library

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Limits
import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.tags.TagField
import java.text.Normalizer
import java.util.Locale

/**
 * Repository paths: "/"-separated, relative, without the file extension, and safe to use as a file
 * name on FAT/exFAT, Windows, ext4 and Android alike.
 */
object RepoPaths {
    const val MUSIC = "music/"
    const val VIDEOS = "videos/"
    const val GAMES = "games/"

    /** Pictures of any sort; each subfolder is synced on its own (photos/, screenshots/, art/…). */
    const val IMAGES = "images/"

    /** The camera's photos (and the videos among them), filed by date; the rest of images/ keeps its paths. */
    const val PHOTOS = "images/photos/"

    /** Where server 0.4 kept photos, before images/: [fromOldPhotos] says where each path went. */
    const val OLD_PHOTOS = "photos/"

    /** The folder in images/photos/ for photos without a date. */
    const val UNDATED = "Undated"

    /** Video categories whose paths follow their fields; anything else in videos/ keeps its path. */
    const val MOVIES = "movies"
    const val SHOWS = "shows"
    const val OTHER = "other"
    val VIDEO_CATEGORIES = listOf(MOVIES, SHOWS, OTHER)

    /** Where filed movies and episodes live. */
    val FILED = listOf("videos/$MOVIES/", "videos/$SHOWS/")

    private val UNSAFE = Regex("""[<>:"/\\|?*\u0000-\u001F\u007F]""")
    private val RESERVED = Regex("""(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?""")

    /** Room left in a file name for the extension a rendition adds. */
    private const val EXTENSION_ROOM = 16

    /** Makes one path segment safe; never returns an empty string. */
    fun segment(text: String, maxBytes: Int = 255): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFC).replace(UNSAFE, "_").trim()
        s = truncateUtf8(s, maxBytes).trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") s = "_"
        if (RESERVED.matches(s)) s = "${s}_"
        return s
    }

    /** Sanitizes every segment of a device-supplied path; the last one leaves room for an extension. */
    fun clean(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "empty path" }
        return parts.mapIndexed { i, part ->
            segment(part, if (i == parts.lastIndex) 255 - EXTENSION_ROOM else 255)
        }.joinToString("/")
    }

    /** A path is acceptable as given if cleaning leaves it untouched. */
    fun validate(path: String) {
        require(path.toByteArray().size <= Limits.MAX_PATH_BYTES) { "path too long" }
        require(clean(path) == path) { "path has unsafe or empty segments: $path" }
    }

    fun isMusic(path: String) = path.startsWith(MUSIC)

    /**
     * Whether an asset's path is worked out from its fields rather than kept as given: music,
     * movies and episodes, photos (and the videos among them), and games whose platform is known.
     * A device can't move these by renaming; a retag moves them.
     */
    fun followsFields(kind: AssetKind, path: String, category: String?): Boolean =
        (kind == AssetKind.AUDIO && isMusic(path)) ||
            (kind == AssetKind.VIDEO && path.startsWith(VIDEOS) && (category == MOVIES || category == SHOWS)) ||
            isPhoto(kind, path) ||
            (kind == AssetKind.GAME && path.startsWith(GAMES) && Platforms.byId(category) != null)

    /**
     * `games/{platform}/{title} ({region})/{name}`: a game's files share its folder, each keeping
     * its own name, since a .cue or .m3u refers to the others by theirs. Null for an unknown
     * platform. Without a title, the folder is the one the file is in now.
     */
    fun game(tags: Tags, platformId: String?, path: String): String? {
        val platform = Platforms.byId(platformId) ?: return null
        val title = first(tags, TagField.TITLE)?.replace(": ", " - ")
        val region = first(tags, TagField.REGION)
        val folder = when {
            title == null -> name(parent(path)).takeIf { parent(path).count { it == '/' } >= 2 } ?: name(path).substringBeforeLast('.')
            region == null || title.endsWith("($region)") -> title
            else -> "$title ($region)"
        }
        return listOf("games", segment(platform.name), segment(folder), name(path)).joinToString("/")
    }

    /**
     * Whether a path keeps its file's extension, rather than each rendition adding its own. Game
     * files do: a game's files often share a name ("Game.cue" and "Game.bin"), and refer to each
     * other by their full names, so neither can be renamed to make room.
     */
    fun keepsExtension(path: String) = path.startsWith(GAMES)

    /** The game folder [path] is in (games/{platform}/{game}), or null outside one. */
    fun gameFolder(path: String): String? {
        if (!path.startsWith(GAMES)) return null
        val parts = path.split('/')
        return if (parts.size >= 4) parts.take(3).joinToString("/") else null
    }

    /** A photo, or a video kept with the photos (a phone's clip, say). */
    fun isPhoto(kind: AssetKind, path: String) = (kind == AssetKind.IMAGE || kind == AssetKind.VIDEO) && path.startsWith(PHOTOS)

    /**
     * `images/photos/{year}/{date} {name}`, the date being as precise as it's known ("2019",
     * "2019-07" or "2019-07-14"); `images/photos/Undated/{name}` without one.
     */
    fun photo(tags: Tags, originalName: String): String {
        val date = first(tags, TagField.DATE)?.let(PhotoDates::normalize)
            ?: return PHOTOS + listOf(UNDATED, segment(originalName, 255 - EXTENSION_ROOM)).joinToString("/")
        val name = PhotoDates.withoutDate(originalName).ifEmpty { originalName }
        return PHOTOS + listOf(date.take(4), segment("$date $name", 255 - EXTENSION_ROOM)).joinToString("/")
    }

    /** Whether a folder of photos is one they're filed in: a year, or Undated. */
    fun isDateFolder(name: String) = name == UNDATED || (name.length == 4 && name.all { it.isDigit() })

    /**
     * Where a path in the old photos/ went: the year and Undated folders to images/photos/, any
     * other folder (one kept by hand, such as "Mom's Stuff") to images/other/; photos/ itself is
     * images/photos/. Other paths stay.
     */
    fun fromOldPhotos(path: String): String {
        if (!path.startsWith(OLD_PHOTOS)) return path
        val rest = path.removePrefix(OLD_PHOTOS)
        return if (rest.isEmpty() || isDateFolder(rest.substringBefore('/'))) PHOTOS + rest else "${IMAGES}other/$rest"
    }

    /**
     * `videos/movies/{title} ({year})/{title} ({year})`, or for an episode
     * `videos/shows/{show}/Season {nn}/{show} - S{nn}E{nn} - {title}`; null for other categories.
     */
    fun video(tags: Tags, category: String?, originalName: String): String? {
        val title = first(tags, TagField.TITLE)
        return when (category) {
            MOVIES -> {
                val name = (title ?: originalName) + (first(tags, TagField.YEAR)?.let { " ($it)" } ?: "")
                listOf("videos", MOVIES, segment(name), segment(name, 255 - EXTENSION_ROOM)).joinToString("/")
            }
            SHOWS -> {
                val show = first(tags, TagField.SHOW) ?: "Unknown Show"
                val season = number(first(tags, TagField.SEASON))
                val episode = number(first(tags, TagField.EPISODE))
                val name = if (season != null && episode != null) {
                    "$show - S%02dE%02d".format(Locale.ROOT, season, episode) + (title?.let { " - $it" } ?: "")
                } else {
                    title ?: originalName
                }
                (listOf("videos", SHOWS, segment(show)) + listOfNotNull(season?.let { "Season %02d".format(Locale.ROOT, it) }) +
                    segment(name, 255 - EXTENSION_ROOM)).joinToString("/")
            }
            else -> null
        }
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    /** `music/{albumartist}/{album}/{track:02} {title}`, with the fallbacks in the design. */
    fun music(tags: Tags, originalName: String, multiDisc: Boolean): String {
        val albumArtist = first(tags, TagField.ALBUM_ARTIST) ?: first(tags, TagField.ARTIST) ?: "Unknown Artist"
        val album = first(tags, TagField.ALBUM) ?: "Unknown Album"
        val title = first(tags, TagField.TITLE) ?: originalName
        val track = number(first(tags, TagField.TRACK_NUMBER))
        val disc = number(first(tags, TagField.DISC_NUMBER))
        val prefix = when {
            track == null -> ""
            multiDisc && disc != null -> "$disc-%02d ".format(Locale.ROOT, track)
            else -> "%02d ".format(Locale.ROOT, track)
        }
        return listOf(
            "music",
            segment(albumArtist),
            segment(album),
            segment(prefix + title, 255 - EXTENSION_ROOM),
        ).joinToString("/")
    }

    /** True if the tags alone say the album has more than one disc ("2", or "1/2"). */
    fun saysMultiDisc(tags: Tags): Boolean {
        val value = first(tags, TagField.DISC_NUMBER) ?: return false
        val disc = number(value) ?: return false
        val total = value.substringAfter('/', "").trim().toIntOrNull()
        return disc > 1 || (total != null && total > 1)
    }

    /** The folder a music asset's tags put it in: tracks there are one album. */
    fun musicAlbumFolder(tags: Tags): String = parent(music(tags, "x", multiDisc = false))

    private fun first(tags: Tags, field: String) = tags[field]?.firstOrNull { it.isNotBlank() }?.trim()

    /** The leading number of "7", "07" or "7/12". */
    private fun number(value: String?): Int? =
        value?.trim()?.takeWhile { it.isDigit() }?.take(6)?.toIntOrNull()

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray().size <= maxBytes) return s
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray().size
            if (bytes + n > maxBytes) break
            out.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
