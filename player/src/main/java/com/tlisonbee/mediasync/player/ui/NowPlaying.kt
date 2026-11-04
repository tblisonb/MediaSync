package com.tlisonbee.mediasync.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tlisonbee.mediasync.player.R
import com.tlisonbee.mediasync.player.library.LibraryData
import kotlinx.coroutines.delay

/** The position in the current track, updated twice a second while [active]. */
@UnstableApi
@Composable
private fun rememberPosition(vm: PlayerViewModel, active: Boolean): Long {
    var position by remember { mutableLongStateOf(vm.positionMs()) }
    LaunchedEffect(active) {
        while (true) {
            position = vm.positionMs()
            if (!active) break
            delay(500)
        }
    }
    return position
}

/** The bar above the bottom of the screens: what's playing, play/pause and next. */
@UnstableApi
@Composable
fun MiniPlayer(ui: Ui, state: PlaybackState, data: LibraryData) {
    val vm = ui.vm
    val item = state.current ?: return
    val track = data.byPath[item.mediaId]
    val position = rememberPosition(vm, state.isPlaying)
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier.fillMaxWidth().clickable { vm.nowPlayingOpen = true }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cover(ui.covers, track?.cover, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(item.mediaMetadata.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.mediaMetadata.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconAction(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (state.isPlaying) "Pause" else "Play") { vm.togglePlay() }
                IconAction(R.drawable.ic_next, "Next") { vm.next() }
            }
        }
    }
}

@UnstableApi
@Composable
fun NowPlayingScreen(ui: Ui, state: PlaybackState, data: LibraryData) {
    val vm = ui.vm
    val item = state.current
    val track = item?.let { data.byPath[it.mediaId] }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_collapse, "Close") { if (vm.queueOpen) vm.queueOpen = false else vm.nowPlayingOpen = false }
                Text(if (vm.queueOpen) "Queue" else "Now playing", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { vm.queueOpen = !vm.queueOpen }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_queue), "Queue",
                        tint = if (vm.queueOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (item == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Missing("Nothing is playing.") }
                return@Column
            }
            if (vm.queueOpen) {
                QueueList(ui, state, data, Modifier.weight(1f))
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Cover(ui.covers, track?.cover, min(maxWidth, maxHeight), corner = 12.dp)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(item.mediaMetadata.title?.toString() ?: "", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.mediaMetadata.artist?.toString() ?: "",
                        Modifier.clickable(enabled = track != null) { track?.let { vm.navigate(Route.ArtistPage(it.albumArtistOrArtist)) } },
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(item.mediaMetadata.albumTitle?.toString(), track?.year?.takeIf { it.isNotEmpty() }).joinToString(" · "),
                        Modifier.clickable(enabled = track != null) { track?.let { vm.navigate(Route.AlbumPage(it.albumKey)) } },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Seek(vm, state)
            Controls(vm, state)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@UnstableApi
@Composable
private fun Seek(vm: PlayerViewModel, state: PlaybackState) {
    val position = rememberPosition(vm, state.isPlaying)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val shown = if (dragging) (dragValue * duration).toLong() else position
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Slider(
            value = if (dragging) dragValue else (position.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { vm.seekTo((dragValue * duration).toLong()); dragging = false },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(shown).ifEmpty { "0:00" }, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(state.durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@UnstableApi
@Composable
private fun Controls(vm: PlayerViewModel, state: PlaybackState) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.toggleShuffle() }) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_shuffle), if (state.shuffle) "Shuffle on" else "Shuffle off", tint = if (state.shuffle) on else off)
        }
        IconButton(onClick = { vm.previous() }, Modifier.size(56.dp)) { Icon(R.drawable.ic_previous, "Previous", Modifier.size(36.dp)) }
        FilledIconButton(onClick = { vm.togglePlay() }, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
            Icon(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (state.isPlaying) "Pause" else "Play", Modifier.size(40.dp))
        }
        IconButton(onClick = { vm.next() }, Modifier.size(56.dp)) { Icon(R.drawable.ic_next, "Next", Modifier.size(36.dp)) }
        IconButton(onClick = { vm.cycleRepeat() }) {
            val (icon, label) = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one to "Repeat this song"
                Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat to "Repeat all"
                else -> R.drawable.ic_repeat to "Repeat off"
            }
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), label, tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) on else off)
        }
    }
}

@UnstableApi
@Composable
private fun QueueList(ui: Ui, state: PlaybackState, data: LibraryData, modifier: Modifier) {
    val vm = ui.vm
    val current = state.current ?: return
    LazyColumn(modifier.fillMaxWidth()) {
        item { SectionHeader("Now playing") }
        item {
            val t = data.byPath[current.mediaId]
            TrackRow(
                current.mediaMetadata.title?.toString() ?: "", current.mediaMetadata.artist?.toString() ?: "",
                lead = { Cover(ui.covers, t?.cover, 44.dp) }, durationMs = t?.durationMs, playing = true, onClick = {}, menu = emptyList(),
            )
        }
        if (state.upNext.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionHeader("Up next · ${state.upNext.size}") }
                    TextButton(onClick = { vm.clearUpNext() }, Modifier.padding(end = 8.dp)) { Text("Clear") }
                }
            }
        }
        // Long queues show their first few hundred; the rest still plays.
        items(state.upNext.take(500), key = { "q${it.index}:${it.item.mediaId}" }) { e ->
            val t = data.byPath[e.item.mediaId]
            TrackRow(
                e.item.mediaMetadata.title?.toString() ?: "",
                listOfNotNull("Queued".takeIf { e.queued }, e.item.mediaMetadata.artist?.toString()).joinToString(" · "),
                lead = { Cover(ui.covers, t?.cover, 44.dp) }, durationMs = t?.durationMs, playing = false,
                onClick = { vm.jumpTo(e.index) },
                menu = listOf("Play next" to { vm.moveToNext(e.index) }, "Remove from queue" to { vm.removeFromQueue(e.index); Unit }),
            )
        }
    }
}
