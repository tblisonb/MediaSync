package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.library.RepoPaths

/**
 * Playlists (`.m3u`/`.m3u8` files in music/) keep pointing at their tracks when MediaSync renames
 * them: the revision that moves a track rewrites the entries that named it. Entries are paths
 * relative to the playlist's folder, as Segue and fooyin write them; absolute paths
 * and URLs are a device's own business and are left alone.
 */
object Playlists {
    val FORMATS = setOf("m3u", "m3u8")

    /**
     * [text] with the entries naming a moved file changed: [moves] maps repository paths (no
     * extension) from old to new; [folder] is the playlist's own folder. Null if nothing changed.
     */
    fun rewrite(text: String, folder: String, moves: Map<String, String>): String? {
        var changed = false
        val out = text.split('\n').joinToString("\n") { raw ->
            val line = raw.removeSuffix("\r")
            val cr = if (raw.endsWith("\r")) "\r" else ""
            val entry = line.trim().removePrefix("﻿")
            if (entry.isEmpty() || entry.startsWith("#") || entry.startsWith("/") || "://" in entry || entry.matches(Regex("^[A-Za-z]:.*"))) return@joinToString raw
            val windows = '\\' in entry && '/' !in entry
            val slashed = entry.replace('\\', '/')
            val resolved = resolve(folder, slashed) ?: return@joinToString raw
            val dot = resolved.lastIndexOf('.')
            val (stem, ext) = if (dot > resolved.lastIndexOf('/')) resolved.substring(0, dot) to resolved.substring(dot) else resolved to ""
            val moved = moves[stem] ?: return@joinToString raw
            changed = true
            val relative = relativize(folder, moved + ext)
            val written = if (windows) relative.replace('/', '\\') else relative
            line.takeWhile { it.isWhitespace() || it == '\uFEFF' } + written + cr
        }
        return out.takeIf { changed }
    }

    /** [path] (relative, with "..") from [folder]; null if it climbs out of the repository. */
    fun resolve(folder: String, path: String): String? {
        val parts = folder.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    fun relativize(folder: String, path: String): String {
        val from = folder.split('/').filter { it.isNotEmpty() }
        val to = path.split('/').filter { it.isNotEmpty() }
        val common = from.zip(to).takeWhile { (a, b) -> a == b }.size
        return (List(from.size - common) { ".." } + to.drop(common)).joinToString("/")
    }
}

/**
 * Rewrites the playlists in music/ for the files [moves] took elsewhere (old path to new), as part
 * of the revision [scope] is writing.
 */
internal fun Repository.followMoves(scope: RevisionScope, moves: Map<String, String>) {
    if (moves.isEmpty()) return
    for (playlist in liveUnder(RepoPaths.MUSIC.removeSuffix("/"))) {
        if (playlist.kind == AssetKind.AUDIO) continue
        val media = playlist.renditions.firstOrNull()?.let(::media) ?: continue
        if (media.format !in Playlists.FORMATS) continue
        val bytes = java.nio.file.Files.readAllBytes(dir.blob(media.baseBlob))
        val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: continue
        val rewritten = Playlists.rewrite(text, RepoPaths.parent(playlist.path), moves) ?: continue
        val stored = blobs.putBytes(rewritten.toByteArray(), RepoPaths.name(playlist.path) + "." + media.format)
        scope.write(playlist.copy(renditions = listOf(stored.mediaHash)), place = false)
    }
}
