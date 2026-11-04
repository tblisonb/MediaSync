package com.tlisonbee.mediasync.player.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.tlisonbee.mediasync.player.BuildConfig
import com.tlisonbee.mediasync.player.GainMode
import com.tlisonbee.mediasync.player.R
import com.tlisonbee.mediasync.player.library.Track
import kotlinx.coroutines.launch

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerRoot(vm: PlayerViewModel, hasAccess: Boolean) {
    if (!hasAccess) return PermissionScreen()
    val dataOrNull by vm.library.data.collectAsState()
    val playback by vm.playback.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<List<Track>?>(null) }
    val ui = remember(vm) { Ui(vm, pickPlaylist = { picking = it }, message = { text -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }) }
    val data = dataOrNull ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val route = vm.stack.last()
    val playing = playback.current?.mediaId

    BackHandler(enabled = vm.nowPlayingOpen || vm.stack.size > 1) { vm.back() }
    // The mini player grows into Now Playing and shrinks back (one shared container, and the cover).
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val shared = this
        Scaffold(
            topBar = { TopBar(ui, route, data) },
            bottomBar = {
                AnimatedVisibility(visible = !vm.nowPlayingOpen && playback.current != null, enter = fadeIn(tween(EXPAND_MS)), exit = fadeOut(tween(EXPAND_MS))) {
                    MiniPlayer(ui, playback, data, Transition(shared, this))
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (route) {
                    Route.Home -> HomeScreen(ui, data, playing)
                    is Route.AlbumPage -> data.albumsByKey[route.key]?.let { AlbumScreen(ui, it, playing) } ?: Missing("This album isn't in the library any more.")
                    is Route.ArtistPage -> ArtistScreen(ui, data, route.name, playing)
                    is Route.FolderPage -> FolderContent(ui, data, route.path, playing)
                    is Route.PlaylistPage -> PlaylistScreen(ui, route.path, playing)
                    Route.Search -> SearchResultsList(ui, data, vm.searchQuery, playing)
                    Route.Settings -> SettingsScreen(vm)
                }
            }
        }
        AnimatedVisibility(visible = vm.nowPlayingOpen, enter = fadeIn(tween(EXPAND_MS)), exit = fadeOut(tween(EXPAND_MS))) {
            NowPlayingScreen(ui, playback, data, Transition(shared, this))
        }
    }
    picking?.let { tracks -> AddToPlaylistDialog(vm, tracks) { done -> picking = null; done?.let(ui.message) } }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(ui: Ui, route: Route, data: com.tlisonbee.mediasync.player.library.LibraryData) {
    val vm = ui.vm
    val back: @Composable () -> Unit = { IconAction(R.drawable.ic_back, "Back") { vm.back() } }
    when (route) {
        Route.Home -> TopAppBar(
            title = { Text("Music") },
            actions = {
                IconAction(R.drawable.ic_search, "Search") { vm.navigate(Route.Search) }
                MoreMenu(listOf("Scan for changes" to { vm.rescan(full = false) }, "Settings" to { vm.navigate(Route.Settings) }))
            },
        )
        Route.Search -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            TopAppBar(
                navigationIcon = back,
                title = {
                    TextField(
                        vm.searchQuery, { vm.searchQuery = it },
                        placeholder = { Text("Songs, albums, artists") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = { if (vm.searchQuery.isNotEmpty()) IconAction(R.drawable.ic_close, "Clear") { vm.searchQuery = "" } },
            )
        }
        is Route.PlaylistPage -> PlaylistTopBar(ui, route.path, back)
        else -> TopAppBar(
            navigationIcon = back,
            title = {
                val title = when (route) {
                    is Route.AlbumPage -> data.albumsByKey[route.key]?.title ?: ""
                    is Route.ArtistPage -> route.name
                    is Route.FolderPage -> route.path.substringAfterLast('/')
                    Route.Settings -> "Settings"
                    else -> ""
                }
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
        )
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistTopBar(ui: Ui, path: String, back: @Composable () -> Unit) {
    val vm = ui.vm
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val name = path.substringAfterLast('/').substringBeforeLast('.')
    TopAppBar(
        navigationIcon = back,
        title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = { MoreMenu(listOf("Rename…" to { renaming = true }, "Delete…" to { deleting = true })) },
    )
    if (renaming) {
        var text by remember { mutableStateOf(name) }
        var error by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it; error = null }, singleLine = true, label = { Text("Name") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    scope.launch {
                        if (vm.library.renamePlaylist(path, text)) {
                            renaming = false
                            val renamed = vm.library.playlists.value.firstOrNull { it.name == com.tlisonbee.mediasync.library.RepoPaths.segment(text.trim(), 200) && it.path.substringBeforeLast('/') == path.substringBeforeLast('/') }
                            vm.stack.removeAt(vm.stack.lastIndex)
                            renamed?.let { vm.navigate(Route.PlaylistPage(it.path)) }
                        } else {
                            error = "A playlist has that name, or it couldn't be renamed."
                        }
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “$name”?") },
            text = { Text("This deletes the playlist file. The songs stay.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.library.deletePlaylist(path)
                        deleting = false
                        vm.back()
                        ui.message("Deleted $name")
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PermissionScreen() {
    val context = LocalContext.current
    Column(Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text("All files access", style = MaterialTheme.typography.titleLarge)
        Text(
            "The player reads the music in the phone's Music folder, and writes the playlists in it. " +
                "Android only allows that to apps with All files access.",
        )
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()))
        }) { Text("Allow All files access") }
    }
}

@UnstableApi
@Composable
private fun SettingsScreen(vm: PlayerViewModel) {
    val mode by vm.settings.gainMode.collectAsState()
    val data by vm.library.data.collectAsState()
    val scan by vm.library.scan.collectAsState()
    var confirmFull by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Volume levelling (ReplayGain)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Uses the ReplayGain tags in the files. Tracks without them play as they are. Changes apply from the next track.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val withGain = data?.tracks?.count { it.gain.trackGain != null } ?: 0
        Text("$withGain of ${data?.tracks?.size ?: 0} songs have ReplayGain tags.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GainMode.entries.forEach { m ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = m == mode, role = Role.RadioButton, onClick = { vm.setGainMode(m) }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = m == mode, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(m.label)
                    Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text("Library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        data?.let { d -> Text("${d.tracks.size} songs, ${d.albums.size} albums, ${d.artists.size} artists in ${vm.library.root.path}.") }
        Text(
            "New and changed files are found each time the player opens. A full scan reads every file again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = !scan.running, onClick = { vm.rescan(full = false) }) { Text("Scan for changes") }
            OutlinedButton(enabled = !scan.running, onClick = { confirmFull = true }) { Text("Full scan") }
        }
        if (scan.running) Text(if (scan.total > 0) "Reading ${scan.done} of ${scan.total}…" else "Looking for changes…")

        Text("About", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text("MediaSync Player ${BuildConfig.VERSION_NAME}. GPL-3.0-or-later; decodes with FFmpeg (GPL-3.0).", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmFull) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmFull = false },
            title = { Text("Read every file again?") },
            text = { Text("This takes about a minute per 10,000 songs. The library stays usable meanwhile.") },
            confirmButton = { TextButton(onClick = { confirmFull = false; vm.rescan(full = true) }) { Text("Scan") } },
            dismissButton = { TextButton(onClick = { confirmFull = false }) { Text("Cancel") } },
        )
    }
}
