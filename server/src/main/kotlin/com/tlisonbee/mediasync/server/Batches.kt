package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Choice
import com.tlisonbee.mediasync.api.IssueDto
import com.tlisonbee.mediasync.api.IssueKind
import com.tlisonbee.mediasync.api.ResolveRequest
import com.tlisonbee.mediasync.library.RepoPaths
import com.tlisonbee.mediasync.tags.TagField

/**
 * Ways to settle many resolve-queue entries at once, each with the choice it makes. Entries the
 * server can't judge on its own are left to be decided one by one.
 */
enum class BatchKind(val title: String, val help: String, val action: String, val alternative: String? = null) {
    GONE(
        "Nothing collides any more",
        "One of the two files has since been deleted or renamed, so there's nothing left to decide.",
        "Clear them",
    ),
    COPIES(
        "Identical copies",
        "Both files hold the same audio. The copy moved aside is removed from the library, and from the devices that have it.",
        "Remove the copies",
    ),
    FORMATS(
        "Same track in another format",
        "Two files of one track: another format, a file without tags, or an empty copy. Each pair becomes one track " +
            "under the tags already there, with only its MP3 (the other file is removed from the library, and from the devices " +
            "that have it), or with both files.",
        "Keep only the MP3s",
        "Keep both formats",
    ),
    NAMES(
        "Pictures that share a name",
        "Different files whose names differ only in their extension, such as 026.jpg and 026.avi. Each keeps its own path, one with “(2)”.",
        "OK",
    ),
    COSMETIC(
        "Same tags, written differently",
        "The imported file has the same audio, and tags that differ only in case, spacing or leading zeros, or lack some " +
            "of the library's. The library's tags stay.",
        "Keep the library's tags",
    ),
    FIXES(
        "Imported tags that fix the library's",
        "The library has an ID3 genre number such as (17), or a date with a time, where the import has the genre's name or the " +
            "plain date. Those are taken from the import; everything else stays.",
        "Take the fixes",
    ),
    OTHER_TAGS(
        "Other tag differences",
        "Same audio, different tags. The library's are usually the ones you cleaned up, so they stay, with any fixes as above " +
            "taken. Untick any you'd rather decide one by one.",
        "Keep the library's tags",
    ),
}

/** One entry of a batch: the choice the batch makes for it (or its [BatchKind.alternative]), and what differs, briefly. */
class BatchItem(val issue: IssueDto, val request: ResolveRequest, val notes: List<String>, val alternative: ResolveRequest = request)

class Batch(val kind: BatchKind, val items: List<BatchItem>)

/** The open entries that fall into a batch, by batch, in [BatchKind] order. */
fun Repository.batches(issues: List<IssueDto>): List<Batch> = db.read {
    issues.mapNotNull { suggest(it) }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (kind, items) -> Batch(kind, items) }
}

private fun Repository.suggest(issue: IssueDto): Pair<BatchKind, BatchItem>? = when (issue.kind) {
    IssueKind.PATH_COLLISION -> collision(issue)
    IssueKind.DUPLICATE -> duplicate(issue)
    else -> null
}

private fun Repository.collision(issue: IssueDto): Pair<BatchKind, BatchItem>? {
    val (there, aside) = issue.candidates.map { c -> c.assetId?.let(::headVersion) ?: return null }
    fun item(choice: Choice, vararg notes: String) = BatchItem(issue, ResolveRequest(choice), notes.toList())
    if (there.deleted || aside.deleted || Repository.pathKey(stem(aside.path)) != Repository.pathKey(there.path)) {
        return BatchKind.GONE to item(Choice.KEEP)
    }
    if (!isTrack(there) || !isTrack(aside) || there.kind != AssetKind.AUDIO && aside.kind != AssetKind.AUDIO) {
        val pictures = there.path.startsWith(RepoPaths.IMAGES) && aside.path.startsWith(RepoPaths.IMAGES)
        return if (pictures) BatchKind.NAMES to item(Choice.KEEP, "${formats(there)} and ${formats(aside)}") else null
    }
    if (there.renditions.any { it in aside.renditions }) return BatchKind.COPIES to item(Choice.LINK, formats(aside))
    // The same track in another format: nothing the other one says contradicts the track (the one
    // already there, unless only the one moved aside is tagged as a track).
    val (track, other) = if (there.kind == AssetKind.AUDIO) there to aside else aside to there
    val sameTrack = track.tags[TagField.TITLE] != null &&
        listOf(TagField.TITLE, TagField.ARTIST, TagField.ALBUM, TagField.TRACK_NUMBER).all { field ->
            other.tags[field] == null || loose(field, other.tags[field]) == loose(field, track.tags[field])
        }
    val formatSet = { v: Version -> v.renditions.mapNotNull { media(it)?.format }.toSet() }
    val empty = other.renditions.all { (media(it)?.size ?: 0) == 0L }
    if (sameTrack && (empty || formatSet(track).none { it in formatSet(other) })) {
        val untagged = if (empty) " (empty)" else if (other.tags.isEmpty()) " (untagged)" else ""
        val note = listOf("${formats(track)} + ${formats(other)}$untagged")
        return BatchKind.FORMATS to BatchItem(issue, ResolveRequest(Choice.LINK, keepFormat = "mp3"), note, ResolveRequest(Choice.LINK))
    }
    return null
}

private fun Repository.duplicate(issue: IssueDto): Pair<BatchKind, BatchItem>? {
    val head = headVersion(issue.assetId)?.takeIf { !it.deleted } ?: return null
    // Against the library as it is now: it may have been retagged since the entry was raised.
    val library = tagFields(head)
    val imported = issue.candidates.getOrNull(1)?.fields ?: return null
    val fixes = ArrayList<String>()
    val notes = ArrayList<String>()
    var other = false
    for (field in (library.keys + imported.keys).sorted()) {
        val ours = library[field]
        val theirs = imported[field]
        if (ours == theirs) continue
        when {
            // Written differently, or not there at all: the import has nothing to offer.
            theirs == null || ours != null && loose(field, ours) == loose(field, theirs) ->
                notes += "$field: ${show(ours)} · imported ${show(theirs)}"
            fixes(field, ours, theirs) -> {
                fixes += field
                notes.add(0, "$field: ${show(ours)} → ${show(theirs)}")
            }
            else -> {
                other = true
                notes.add(fixes.size, "$field: ${show(ours)} · imported ${show(theirs)}")
            }
        }
    }
    val request = if (fixes.isEmpty()) ResolveRequest(Choice.KEEP) else ResolveRequest(Choice.MERGE, fixes.associateWith { 1 })
    val kind = when {
        other -> BatchKind.OTHER_TAGS
        fixes.isNotEmpty() -> BatchKind.FIXES
        else -> BatchKind.COSMETIC
    }
    return kind to BatchItem(issue, request, notes.take(3))
}

/** Whether the imported value is plainly better: a genre's name for its ID3 number, or a date without the time. */
private fun fixes(field: String, ours: List<String>?, theirs: List<String>?): Boolean {
    val a = ours?.singleOrNull() ?: return false
    val b = theirs?.singleOrNull() ?: return false
    return when (field) {
        TagField.GENRE -> GENRE_NUMBER.matches(a) && !GENRE_NUMBER.matches(b) && b.isNotBlank()
        TagField.DATE -> DATE_TIME.matches(a) && a.startsWith(b) && PLAIN_DATE.matches(b)
        else -> false
    }
}

/**
 * Tags as they compare when only case, spacing and leading zeros in numbers differ: the same
 * tags written differently, which an import of the same audio needn't raise.
 */
fun looseTags(tags: Map<String, List<String>>): Map<String, List<String>> = tags.mapValues { (field, values) -> loose(field, values)!! }

private fun loose(field: String, values: List<String>?): List<String>? = values?.map { value ->
    val v = value.trim().replace(SPACES, " ").lowercase()
    if (field == TagField.TRACK_NUMBER || field == TagField.DISC_NUMBER) v.split('/').joinToString("/") { it.trimStart('0').ifEmpty { "0" } } else v
}

/**
 * A track, or what may be one: a file in the music folder in an audio format that the client which
 * sent it couldn't read, so it came in as a plain file.
 */
internal fun Repository.isTrack(v: Version) = v.kind == AssetKind.AUDIO ||
    v.kind == AssetKind.OTHER && v.path.startsWith(RepoPaths.MUSIC) && v.renditions.isNotEmpty() &&
    v.renditions.all { media(it)?.format.let { format -> format == "mp3" || format in CONVERTIBLE_FORMATS } }

private fun stem(path: String) = COPY_SUFFIX.find(path)?.let { path.substring(0, it.range.first) } ?: path

private fun Repository.formats(v: Version) = v.renditions.mapNotNull { media(it)?.format }.distinct().joinToString("+").ifEmpty { "?" }

private fun show(values: List<String>?) = values?.joinToString("; ")?.ifEmpty { "(empty)" } ?: "(none)"

private val SPACES = Regex("\\s+")
private val COPY_SUFFIX = Regex(" \\(\\d+\\)$")
private val GENRE_NUMBER = Regex("\\(\\d+\\)")
private val DATE_TIME = Regex("\\d{4}-\\d{2}-\\d{2}T.*")
private val PLAIN_DATE = Regex("\\d{4}(-\\d{2}(-\\d{2})?)?")
