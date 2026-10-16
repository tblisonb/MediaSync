package com.tlisonbee.mediasync.library

import com.tlisonbee.mediasync.api.Tags
import com.tlisonbee.mediasync.tags.TagField

/** What a video's file name and folders suggest it is; a first guess to correct in the editor. */
data class VideoGuess(val category: String, val tags: Tags)

/**
 * Reads a movie's title and year, or an episode's show, season and episode, out of the names people
 * and release groups give video files: "Inception (2010)", "Megamind.2010.1080p.BluRay.x264",
 * "Fringe.S01E01", "The Owl House S3E1", "The Justin Show - Episode 2".
 */
object VideoNames {
    private val EPISODE = Regex("""(?i)(?:^|[\s._-])S(\d{1,2})[\s._-]?E(\d{1,3})(?![0-9])""")
    private val CROSS = Regex("""(?i)(?:^|[\s._-])(\d{1,2})x(\d{2,3})(?![0-9])""")
    private val NUMBERED = Regex("""(?i)(?:^|[\s._-])(?:Episode|Ep)\.?\s*(\d{1,3})(?![0-9])""")
    private val YEAR = Regex("""[(\[]?\b((?:19|20)\d{2})\b[)\]]?""")

    /** Release-group words that end a title: resolution, source, codecs, groups. */
    private val JUNK = Regex(
        """(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr|bluray|blu-ray|brrip|bdrip|dvdrip|webrip|web-dl|webdl|web|hdrip|hdtv|""" +
            """x264|x265|h\.?264|h\.?265|hevc|xvid|aac|ac3|dts|ddp?5\.1|5\.1|yify|yts|rarbg|extended|remastered|proper|repack)\b.*""",
    )
    private val SHOW_FOLDERS = Regex("""(?i)\b(tv|shows?|series)\b""")
    private val MOVIE_FOLDERS = Regex("""(?i)\b(movies?|films?)\b""")

    /** [path] is the file's path on the device that imported it, or its repository path. */
    fun guess(path: String): VideoGuess {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val folders = parts.dropLast(1)
        val name = readable(parts.lastOrNull().orEmpty().substringBeforeLast('.'))

        (EPISODE.find(name) ?: CROSS.find(name))?.let { m ->
            return episode(name, m, season = m.groupValues[1].toInt(), episode = m.groupValues[2].toInt(), folders)
        }
        NUMBERED.find(name)?.let { m -> return episode(name, m, season = 1, episode = m.groupValues[1].toInt(), folders) }

        val inShows = folders.any { SHOW_FOLDERS.containsMatchIn(it) }
        val inMovies = folders.any { MOVIE_FOLDERS.containsMatchIn(it) }
        if (inShows && !inMovies && folders.size >= 2) {
            // "TV Shows/Fringe/Pilot": the folder under the shows folder is the show.
            val show = folders[folders.indexOfLast { SHOW_FOLDERS.containsMatchIn(it) } + 1].takeIf { !SHOW_FOLDERS.containsMatchIn(it) }
            if (show != null) return VideoGuess(RepoPaths.SHOWS, tags(TagField.SHOW to readable(show), TagField.TITLE to clean(name)))
        }
        if (!inMovies) return VideoGuess(RepoPaths.OTHER, tags(TagField.TITLE to clean(name)))

        // A movie: the title runs up to its year, or up to the first release-group word.
        val year = YEAR.findAll(name).lastOrNull { it.range.first > 0 }
        val title = clean(if (year != null) name.substring(0, year.range.first) else name)
        return VideoGuess(RepoPaths.MOVIES, tags(TagField.TITLE to title.ifEmpty { name }, TagField.YEAR to year?.groupValues?.get(1)))
    }

    private fun episode(name: String, match: MatchResult, season: Int, episode: Int, folders: List<String>): VideoGuess {
        val before = clean(name.substring(0, match.range.first))
        val after = clean(name.substring(match.range.last + 1))
        val show = before.ifEmpty { folders.lastOrNull { !SHOW_FOLDERS.containsMatchIn(it) && !Regex("""(?i)^season""").containsMatchIn(it) }?.let(::readable) }
        return VideoGuess(
            RepoPaths.SHOWS,
            tags(TagField.SHOW to show, TagField.SEASON to season.toString(), TagField.EPISODE to episode.toString(), TagField.TITLE to after.ifEmpty { null }),
        )
    }

    /** "Speed.Racer.2008" and "Whiplash_1080p" use dots or underscores for spaces. */
    private fun readable(text: String): String =
        if (' ' !in text && (text.count { it == '.' } >= 2 || '_' in text)) text.replace('.', ' ').replace('_', ' ') else text.replace('_', ' ')

    /** Drops release-group words and the separators left around a title. */
    private fun clean(text: String): String {
        var s = text.replace(JUNK, "").replace(Regex("""\s+"""), " ")
        while (true) {
            val before = s
            s = s.trim(' ', '-', '.', '_').removeSuffix("(").removeSuffix("[").removePrefix(")").removePrefix("]")
            // A bracket closed here but opened before the cut, as "(2010" leaves: drop it.
            if (s.endsWith(")") && s.count { it == '(' } < s.count { it == ')' }) s = s.dropLast(1)
            if (s.endsWith("]") && s.count { it == '[' } < s.count { it == ']' }) s = s.dropLast(1)
            if (s == before) return s
        }
    }

    private fun tags(vararg pairs: Pair<String, String?>): Tags = pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to listOf(it) } }.toMap()
}
