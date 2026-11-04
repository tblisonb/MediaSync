package com.tlisonbee.mediasync.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.tlisonbee.mediasync.player.R
import com.tlisonbee.mediasync.player.library.Album
import com.tlisonbee.mediasync.player.library.LibraryData
import com.tlisonbee.mediasync.player.library.M3u
import com.tlisonbee.mediasync.player.library.Track
import kotlinx.coroutines.launch

/** What every screen needs: the view model, and the dialogs and messages the root shows. */
@UnstableApi
class Ui(val vm: PlayerViewModel, val pickPlaylist: (List<Track>) -> Unit, val message: (String) -> Unit) {
    val covers get() = vm.library.covers

    fun trackMenu(t: Track, album: Boolean = true, artist: Boolean = true, extra: List<Pair<String, () -> Unit>> = emptyList()) = TrackActions(
        playNext = { vm.playNext(listOf(t)); message("“${t.title}” plays next") },
        addToQueue = { vm.addToQueue(listOf(t)); message("Added “${t.title}” to the queue") },
        addToPlaylist = { pickPlaylist(listOf(t)) },
        goToAlbum = if (album) ({ vm.navigate(Route.AlbumPage(t.albumKey)) }) else null,
        goToArtist = if (artist) ({ vm.navigate(Route.ArtistPage(t.albumArtistOrArtist)) }) else null,
        extra = extra,
    ).list()

    fun listMenu(tracks: List<Track>, name: String) = TrackActions(
        playNext = { vm.playNext(tracks); message("$name plays next") },
        addToQueue = { vm.addToQueue(tracks); message("Added $name to the queue") },
        addToPlaylist = { pickPlaylist(tracks) },
    ).list()
}

@UnstableApi
@Composable
fun HomeScreen(ui: Ui, data: LibraryData, playing: String?) {
    val vm = ui.vm
    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = vm.tab.ordinal, edgePadding = 8.dp) {
            HomeTab.entries.forEach { t ->
                Tab(selected = vm.tab == t, onClick = { vm.tab = t }, text = { Text(t.label) })
            }
        }
        ScanBanner(vm)
        when (vm.tab) {
            HomeTab.ARTISTS -> LazyColumn(Modifier.fillMaxSize()) {
                items(data.artists, key = { it.name }) { a ->
                    EntryRow(
                        a.name,
                        listOfNotNull(a.albums.size.takeIf { it > 0 }?.let { if (it == 1) "1 album" else "$it albums" }, summary(a.trackCount, 0)).joinToString(" · "),
                        lead = { Cover(ui.covers, a.albums.firstNotNullOfOrNull { it.cover } ?: a.appearsOn.firstNotNullOfOrNull { it.cover }, 48.dp, corner = 24.dp) },
                        onClick = { vm.navigate(Route.ArtistPage(a.name)) },
                    )
                }
                emptyLibrary(data)
            }
            HomeTab.ALBUMS -> AlbumGrid(ui, data.albums)
            HomeTab.SONGS -> LazyColumn(Modifier.fillMaxSize()) {
                if (data.tracks.isNotEmpty()) {
                    item {
                        PlayButtons(
                            onPlay = { vm.play(data.tracks, 0, shuffle = false) },
                            onShuffle = { vm.play(data.tracks, 0, shuffle = true) },
                            label = summary(data.tracks.size, 0),
                        )
                    }
                }
                itemsIndexed(data.tracks, key = { _, t -> t.path }) { i, t ->
                    TrackRow(
                        t.title, "${t.artist} · ${t.albumOrUnknown}",
                        lead = { Cover(ui.covers, t.cover, 44.dp) },
                        durationMs = t.durationMs, playing = t.path == playing, remote = t.isRemote,
                        onClick = { vm.play(data.tracks, i) },
                        menu = ui.trackMenu(t),
                    )
                }
                emptyLibrary(data)
            }
            HomeTab.FOLDERS -> FolderContent(ui, data, vm.library.root.path, playing)
            HomeTab.PLAYLISTS -> PlaylistsTab(ui)
        }
    }
}

private fun LazyListScope.emptyLibrary(data: LibraryData) {
    if (data.tracks.isEmpty()) {
        item {
            Text(
                "No music yet. The player shows what's in the phone's Music folder.",
                Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@UnstableApi
@Composable
private fun ScanBanner(vm: PlayerViewModel) {
    val scan by vm.library.scan.collectAsState()
    if (scan.running && scan.total > 0) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Reading new music: ${scan.done} of ${scan.total}", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
    scan.lastError?.let { Text("The last scan failed: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
}

@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, label: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onPlay) {
            Icon(R.drawable.ic_play, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Play")
        }
        FilledTonalButton(onClick = onShuffle) {
            Icon(R.drawable.ic_shuffle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Shuffle")
        }
        if (label != null) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@UnstableApi
@Composable
fun AlbumGrid(ui: Ui, albums: List<Album>) {
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(albums, key = { it.key }) { a ->
            Column(Modifier.clickable { ui.vm.navigate(Route.AlbumPage(a.key)) }) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Cover(ui.covers, a.cover, maxWidth, corner = 8.dp)
                }
                Text(a.title, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@UnstableApi
@Composable
fun AlbumScreen(ui: Ui, album: Album, playing: String?) {
    val vm = ui.vm
    val multiDisc = album.discs > 1
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(ui.covers, album.cover, 240.dp, corner = 8.dp)
                Spacer(Modifier.height(16.dp))
                Text(album.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    album.artist, Modifier.clickable { vm.navigate(Route.ArtistPage(album.artist)) }.padding(4.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                )
                Text(listOf(album.year, summary(album.tracks.size, album.durationMs)).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PlayButtons(onPlay = { vm.play(album.tracks, 0, shuffle = false) }, onShuffle = { vm.play(album.tracks, 0, shuffle = true) }) }
                MoreMenu(ui.listMenu(album.tracks, album.title))
            }
        }
        var disc: Int? = -1
        album.tracks.forEachIndexed { i, t ->
            if (multiDisc && t.discNumber != disc) {
                disc = t.discNumber
                item(key = "disc$disc") { SectionHeader("Disc ${t.discNumber ?: "?"}") }
            }
            item(key = t.path) {
                TrackRow(
                    t.title,
                    if (t.artist != album.artist) t.artist else "",
                    lead = { Text(t.trackNumber?.toString() ?: "", Modifier.width(28.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    durationMs = t.durationMs, playing = t.path == playing, remote = t.isRemote,
                    onClick = { vm.play(album.tracks, i) },
                    menu = ui.trackMenu(t, album = false),
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@UnstableApi
@Composable
fun ArtistScreen(ui: Ui, data: LibraryData, name: String, playing: String?) {
    val vm = ui.vm
    val artist = data.artist(name) ?: return Missing("This artist isn't in the library any more.")
    val tracks = artist.tracks
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PlayButtons(onPlay = { vm.play(tracks, 0, shuffle = false) }, onShuffle = { vm.play(tracks, 0, shuffle = true) }, label = summary(tracks.size, 0)) }
                MoreMenu(ui.listMenu(tracks, artist.name))
            }
        }
        if (artist.albums.isNotEmpty()) item { SectionHeader("Albums") }
        items(artist.albums, key = { it.key }) { a ->
            EntryRow(
                a.title, listOf(a.year, summary(a.tracks.size, a.durationMs)).filter { it.isNotEmpty() }.joinToString(" · "),
                lead = { Cover(ui.covers, a.cover, 56.dp) },
                onClick = { vm.navigate(Route.AlbumPage(a.key)) },
                menu = ui.listMenu(a.tracks, a.title),
            )
        }
        if (artist.appearsOn.isNotEmpty()) item { SectionHeader("Appears on") }
        itemsIndexed(artist.appearsOn, key = { _, t -> "on:" + t.path }) { i, t ->
            TrackRow(
                t.title, "${t.albumOrUnknown} · ${t.albumArtistOrArtist}",
                lead = { Cover(ui.covers, t.cover, 44.dp) },
                durationMs = t.durationMs, playing = t.path == playing, remote = t.isRemote,
                onClick = { vm.play(artist.appearsOn, i) },
                menu = ui.trackMenu(t, artist = false),
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@UnstableApi
@Composable
fun FolderContent(ui: Ui, data: LibraryData, path: String, playing: String?) {
    val vm = ui.vm
    val listing = remember(data, path) { data.folder(path) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(listing.folders, key = { it.path }) { f ->
            EntryRow(f.name, summary(f.trackCount, 0), lead = { RoundIcon(R.drawable.ic_folder) }, onClick = { vm.navigate(Route.FolderPage(f.path)) })
        }
        if (listing.folders.isNotEmpty() && listing.tracks.isNotEmpty()) item { HorizontalDivider() }
        itemsIndexed(listing.tracks, key = { _, t -> t.path }) { i, t ->
            TrackRow(
                t.fileName, "${t.artist} · ${t.title}",
                lead = { Cover(ui.covers, t.cover, 44.dp) },
                durationMs = t.durationMs, playing = t.path == playing, remote = t.isRemote,
                onClick = { vm.play(listing.tracks, i) },
                menu = ui.trackMenu(t),
            )
        }
        if (listing.folders.isEmpty() && listing.tracks.isEmpty()) item { Missing("Nothing to play here.") }
    }
}

@UnstableApi
@Composable
private fun PlaylistsTab(ui: Ui) {
    val playlists by ui.vm.library.playlists.collectAsState()
    LazyColumn(Modifier.fillMaxSize()) {
        item { EntryRow("New playlist…", "", lead = { RoundIcon(R.drawable.ic_add) }, onClick = { ui.pickPlaylist(emptyList()) }) }
        items(playlists, key = { it.path }) { p ->
            EntryRow(p.name, p.path.removePrefix(ui.vm.library.root.path + "/").substringBeforeLast('/', ""), lead = { RoundIcon(R.drawable.ic_playlist) }, onClick = { ui.vm.navigate(Route.PlaylistPage(p.path)) })
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "Playlists are .m3u8 files in the Music folder. New ones go in Music/Playlists, where MediaSync can sync them like any other file.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@UnstableApi
@Composable
fun PlaylistScreen(ui: Ui, path: String, playing: String?) {
    val vm = ui.vm
    val playlists by vm.library.playlists.collectAsState()
    val data by vm.library.data.collectAsState()
    val file = playlists.firstOrNull { it.path == path }
    // Read again whenever the file changes or the library does.
    val entries by produceState<List<Pair<M3u.Entry, Track?>>?>(null, path, file?.mtime, data) {
        value = runCatching { vm.library.readPlaylist(path) }.getOrDefault(emptyList())
    }
    val list = entries ?: return
    val playable = list.mapNotNull { it.second }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PlayButtons(
                onPlay = { vm.play(playable, 0, shuffle = false) }, onShuffle = { vm.play(playable, 0, shuffle = true) },
                label = summary(playable.size, playable.sumOf { it.durationMs ?: 0 }),
            )
        }
        itemsIndexed(list) { i, (entry, track) ->
            val edits = listOfNotNull(
                ("Move up" to { scope.launch { vm.library.moveInPlaylist(path, i, i - 1) }; Unit }).takeIf { i > 0 },
                ("Move down" to { scope.launch { vm.library.moveInPlaylist(path, i, i + 1) }; Unit }).takeIf { i < list.lastIndex },
                "Remove from playlist" to { scope.launch { vm.library.removeFromPlaylist(path, i) }; Unit },
            )
            if (track == null) {
                TrackRow(
                    entry.title ?: entry.path.substringAfterLast('/'), "Not in the library", lead = { Cover(ui.covers, null, 44.dp) },
                    durationMs = entry.durationSec?.let { it * 1000L }, playing = false, enabled = false, onClick = {}, menu = edits,
                )
            } else {
                TrackRow(
                    track.title, "${track.artist} · ${track.albumOrUnknown}", lead = { Cover(ui.covers, track.cover, 44.dp) },
                    durationMs = track.durationMs, playing = track.path == playing, remote = track.isRemote,
                    onClick = { vm.play(playable, playable.indexOf(track).coerceAtLeast(0)) },
                    menu = ui.trackMenu(track, extra = edits),
                )
            }
        }
        if (list.isEmpty()) item { Missing("This playlist is empty. Add songs to it from any song's menu.") }
    }
}

@UnstableApi
@Composable
fun SearchResultsList(ui: Ui, data: LibraryData, query: String, playing: String?) {
    val vm = ui.vm
    val results = remember(data, query) { data.search(query) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (query.isNotBlank() && results.isEmpty) item { Missing("Nothing matches “$query”.") }
        if (results.artists.isNotEmpty()) item { SectionHeader("Artists") }
        items(results.artists.take(20), key = { "a:" + it.name }) { a ->
            EntryRow(a.name, summary(a.trackCount, 0), lead = { Cover(ui.covers, a.albums.firstNotNullOfOrNull { it.cover }, 48.dp, corner = 24.dp) }, onClick = { vm.navigate(Route.ArtistPage(a.name)) })
        }
        if (results.albums.isNotEmpty()) item { SectionHeader("Albums") }
        items(results.albums.take(50), key = { "b:" + it.key }) { a ->
            EntryRow(a.title, listOf(a.artist, a.year).filter { it.isNotEmpty() }.joinToString(" · "), lead = { Cover(ui.covers, a.cover, 48.dp) }, onClick = { vm.navigate(Route.AlbumPage(a.key)) })
        }
        if (results.tracks.isNotEmpty()) item { SectionHeader("Songs") }
        itemsIndexed(results.tracks.take(300), key = { _, t -> "t:" + t.path }) { i, t ->
            TrackRow(
                t.title, "${t.artist} · ${t.albumOrUnknown}", lead = { Cover(ui.covers, t.cover, 44.dp) },
                durationMs = t.durationMs, playing = t.path == playing, remote = t.isRemote,
                onClick = { vm.play(results.tracks, i) },
                menu = ui.trackMenu(t),
            )
        }
    }
}

@Composable
fun Missing(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
}
