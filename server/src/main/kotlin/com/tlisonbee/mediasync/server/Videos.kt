package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.RepoPaths

/**
 * Videos: movies and episodes are filed by their fields (RepoPaths.video), and their subtitles
 * and posters are files of their own named after them, "{video}.en.srt" and "{video}-poster.jpg",
 * so VLC and media servers find them. Those follow their video wherever it moves.
 */

val SUBTITLE_FORMATS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "smi")
val POSTER_FORMATS = setOf("jpg", "png", "webp")

/** What follows a video's name in one of its sidecars' (".en", "-poster"); null if [name] isn't one. */
fun sidecarSuffix(videoName: String, name: String): String? {
    if (name.length <= videoName.length || !name.startsWith(videoName)) return null
    val rest = name.substring(videoName.length)
    return rest.takeIf { it[0] == '.' || it[0] == '-' }
}

fun Repository.formatOf(v: Version): String? = v.renditions.firstOrNull()?.let { media(it)?.format }

/** A video's subtitles and posters, with what follows its name in each. */
fun Repository.sidecarsOf(video: Version): List<Pair<Version, String>> =
    liveIn(RepoPaths.parent(video.path)).filter { it.assetId != video.assetId && it.kind != AssetKind.VIDEO }
        .mapNotNull { file -> sidecarSuffix(RepoPaths.name(video.path), RepoPaths.name(file.path))?.let { file to it } }

/** The path a file had on the device that imported it, with "/" separators. */
fun Repository.importSource(assetId: String): String? =
    db.one("SELECT source_path FROM asset_import WHERE asset_id = ?", assetId) { it.getString(1) }?.replace('\\', '/')

/** Assets imported from the same device folder as [assetId]. */
fun Repository.importedBeside(assetId: String): List<String> {
    val folder = importSource(assetId)?.substringBeforeLast('/', "") ?: return emptyList()
    val pattern = folder.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"
    return db.query("SELECT asset_id, source_path FROM asset_import WHERE source_path LIKE ? ESCAPE '\\'", pattern) { it.getString(1) to it.getString(2) }
        .filter { (id, source) -> id != assetId && source.replace('\\', '/').substringBeforeLast('/') == folder }
        .map { it.first }
}

/**
 * The video a subtitle or poster belongs to, and what followed the video's name in the file's
 * original name: a video imported from the same folder whose name starts the file's (the longest
 * such), or a video in the file's folder in the repository.
 */
fun Repository.ownerOf(file: Version): Pair<Version, String>? {
    val stem = (importSource(file.assetId) ?: file.path).substringAfterLast('/').let { name ->
        if (importSource(file.assetId) != null) name.substringBeforeLast('.') else name
    }
    fun match(videoStem: String) = when {
        stem == videoStem -> ""
        stem.startsWith(videoStem) && stem[videoStem.length] in ".-" -> stem.substring(videoStem.length)
        else -> null
    }
    val bySource = importedBeside(file.assetId).mapNotNull { id ->
        val video = headVersion(id)?.takeIf { it.kind == AssetKind.VIDEO && !it.deleted } ?: return@mapNotNull null
        val videoStem = importSource(id)!!.substringAfterLast('/').substringBeforeLast('.')
        match(videoStem)?.let { Triple(video, it, videoStem.length) }
    }
    val byFolder = liveIn(RepoPaths.parent(file.path)).filter { it.kind == AssetKind.VIDEO }.mapNotNull { video ->
        val videoStem = RepoPaths.name(video.path)
        match(videoStem)?.let { Triple(video, it, videoStem.length) }
    }
    return (bySource + byFolder).maxByOrNull { it.third }?.let { it.first to it.second }
}

/**
 * Adds a subtitle or poster for [video] from the web UI, or replaces the one already at that
 * name. [suffix] is ".en" for a subtitle, "-poster" for a poster.
 */
fun Repository.addSidecar(deviceId: String, video: Version, suffix: String, media: MediaRow): Long? = write {
    val path = video.path + suffix
    val kind = if (media.format in POSTER_FORMATS) AssetKind.IMAGE else AssetKind.OTHER
    val scope = RevisionScope(this, deviceId, "Added ${RepoPaths.name(path)}.${media.format}")
    val existing = liveAssetAt(path)?.let(::headVersion)?.takeIf { it.kind == kind }
    if (existing != null) {
        scope.write(existing.copy(renditions = listOf(media.mediaHash)))
    } else {
        scope.write(Version(java.util.UUID.randomUUID().toString(), 0, false, path, kind, null, listOf(media.mediaHash), emptyMap(), emptyList()), raiseCollision = false)
    }
    scope.finish()
}

/** Deletes an asset (recoverably: Recently deleted brings it back). */
fun Repository.deleteAsset(deviceId: String, assetId: String): Long? = write {
    val head = headVersion(assetId)?.takeIf { !it.deleted } ?: notFound("no asset $assetId")
    val scope = RevisionScope(this, deviceId, "Deleted ${RepoPaths.name(head.path)}")
    scope.write(head.copy(deleted = true))
    scope.finish()
}
