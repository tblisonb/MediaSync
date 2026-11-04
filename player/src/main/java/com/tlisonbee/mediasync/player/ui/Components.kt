package com.tlisonbee.mediasync.player.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.tlisonbee.mediasync.player.R
import com.tlisonbee.mediasync.player.library.Covers
import com.tlisonbee.mediasync.player.library.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Icon(@DrawableRes id: Int, description: String?, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = description, modifier = modifier)

@Composable
fun IconAction(@DrawableRes id: Int, description: String, onClick: () -> Unit) =
    IconButton(onClick = onClick) { Icon(id, description) }

/** A stored cover, or a placeholder with a note while it loads or when there's none. */
@Composable
fun Cover(covers: Covers, key: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 4.dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, key, px) {
        value = key?.let { withContext(Dispatchers.IO) { covers.load(it, px)?.asImageBitmap() } }
    }
    val shape = RoundedCornerShape(corner)
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(R.drawable.ic_note, null, Modifier.size(size / 2.5f))
        }
    }
}

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "12 songs · 48 min". */
fun summary(count: Int, ms: Long): String {
    val songs = if (count == 1) "1 song" else "$count songs"
    val minutes = ms / 60_000
    return when {
        minutes <= 0 -> songs
        minutes < 60 -> "$songs · $minutes min"
        else -> "$songs · ${minutes / 60} h ${minutes % 60} min"
    }
}

/** What a track's menu can do; null leaves an action out. */
class TrackActions(
    val playNext: (() -> Unit)? = null,
    val addToQueue: (() -> Unit)? = null,
    val addToPlaylist: (() -> Unit)? = null,
    val goToAlbum: (() -> Unit)? = null,
    val goToArtist: (() -> Unit)? = null,
    val extra: List<Pair<String, () -> Unit>> = emptyList(),
)

@Composable
fun MoreMenu(actions: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_more, "More") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, action) in actions) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

fun TrackActions.list(): List<Pair<String, () -> Unit>> = listOfNotNull(
    playNext?.let { "Play next" to it },
    addToQueue?.let { "Add to queue" to it },
    addToPlaylist?.let { "Add to playlist…" to it },
    goToAlbum?.let { "Go to album" to it },
    goToArtist?.let { "Go to artist" to it },
) + extra

/**
 * A track in a list: its number or cover, title, a second line, its length and its menu. The
 * playing track's title is in the accent colour.
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    lead: @Composable () -> Unit,
    durationMs: Long?,
    playing: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    menu: List<Pair<String, () -> Unit>>,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = enabled, onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lead()
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.SemiBold else null,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    playing -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (menu.isNotEmpty()) MoreMenu(menu) else Box(Modifier.width(8.dp))
    }
}

/** A list row for an artist, album, folder or playlist. */
@Composable
fun EntryRow(title: String, subtitle: String, lead: @Composable () -> Unit, onClick: () -> Unit, menu: List<Pair<String, () -> Unit>> = emptyList()) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        lead()
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (menu.isNotEmpty()) MoreMenu(menu) else Box(Modifier.width(12.dp))
    }
}

@Composable
fun RoundIcon(@DrawableRes id: Int, size: Dp = 48.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 2)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(id, null, Modifier.size(size / 2))
    }
}

/** Picks one of the playlists for [tracks], or makes a new one. */
@UnstableApi
@Composable
fun AddToPlaylistDialog(viewModel: PlayerViewModel, tracks: List<Track>, onDone: (String?) -> Unit) {
    val playlists by viewModel.library.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(tracks.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val what = if (tracks.size == 1) "“${tracks[0].title}”" else "${tracks.size} songs"
    if (creating) {
        AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text("New playlist") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") }, singleLine = true)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch {
                        val path = runCatching { viewModel.library.createPlaylist(name, tracks) }.getOrNull()
                        if (path == null) error = "A playlist has that name, or it couldn't be written." else onDone(if (tracks.isEmpty()) "Made the playlist $name" else "Added $what to $name")
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text("Add $what to") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item {
                        EntryRow("New playlist…", "", lead = { RoundIcon(R.drawable.ic_add, 40.dp) }, onClick = { creating = true })
                    }
                    items(playlists, key = { it.path }) { p ->
                        EntryRow(p.name, "", lead = { RoundIcon(R.drawable.ic_playlist, 40.dp) }, onClick = {
                            scope.launch {
                                val ok = runCatching { viewModel.library.addToPlaylist(p.path, tracks) }.isSuccess
                                onDone(if (ok) "Added $what to ${p.name}" else "Couldn't change ${p.name}")
                            }
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
        )
    }
}
