package com.tlisonbee.mediasync.player.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.tlisonbee.mediasync.player.R
import com.tlisonbee.mediasync.player.library.Track
import com.tlisonbee.mediasync.player.tidal.TidalItem
import com.tlisonbee.mediasync.player.tidal.TidalList
import com.tlisonbee.mediasync.player.tidal.TidalLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun TidalItem.subtitle() = listOf(artist, album).filter { it.isNotEmpty() }.joinToString(" · ")

/** A TIDAL track's row; tapping plays [list] from it. */
@UnstableApi
@Composable
private fun TidalTrackRow(
    ui: Ui, item: TidalItem, list: List<TidalItem>, index: Int, playing: String?, numbered: Boolean = false,
    extra: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val track = item.track ?: return
    TrackRow(
        item.title, if (numbered) item.artist else item.subtitle(),
        lead = {
            if (numbered) {
                Text(track.trackNumber?.toString().orEmpty(), Modifier.padding(end = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Cover(ui.covers, item.cover, 44.dp)
            }
        },
        durationMs = item.durationMs, playing = track.path == playing,
        onClick = { ui.vm.play(list.mapNotNull { it.track }, list.take(index).count { it.track != null }) },
        menu = ui.tidalTrackMenu(listOf(track), "“${item.title}”", extra),
    )
}

@UnstableApi
fun Ui.tidalTrackMenu(tracks: List<Track>, name: String, extra: List<Pair<String, () -> Unit>> = emptyList()) = TrackActions(
    playNext = { vm.playNext(tracks); message("$name plays next") },
    addToQueue = { vm.addToQueue(tracks); message("Added $name to the queue") },
    addToPlaylist = { pickPlaylist(tracks) },
    extra = tidalActions(tracks) + extra,
).list()

/** A row for a TIDAL album, artist or playlist. */
@UnstableApi
@Composable
private fun TidalEntryRow(ui: Ui, item: TidalItem) {
    val route = when (item.kind) {
        TidalItem.Kind.ALBUM -> Route.TidalAlbum(item.id, item.title)
        TidalItem.Kind.ARTIST -> Route.TidalArtist(item.id, item.title)
        TidalItem.Kind.PLAYLIST -> Route.TidalPlaylist(item.id, item.title)
        TidalItem.Kind.TRACK -> return
    }
    EntryRow(
        item.title, listOf(item.artist, item.info).filter { it.isNotEmpty() }.joinToString(" · "),
        lead = {
            if (item.kind == TidalItem.Kind.ARTIST) RoundIcon(R.drawable.ic_note) else Cover(ui.covers, item.cover, 52.dp)
        },
        onClick = { ui.vm.navigate(route) },
        menu = if (item.kind == TidalItem.Kind.PLAYLIST && ui.vm.tidal.account.user in item.owners) emptyList() else {
            val favorite = item.id in ui.vm.tidalBrowser.favoriteIds(item.kind)
            listOf(
                if (favorite) "Remove from TIDAL favorites" to { ui.tidalEditor.favorite(item, add = false) }
                else "Add to TIDAL favorites" to { ui.tidalEditor.favorite(item, add = true) },
            )
        },
    )
}

private fun LazyListScope.listStatus(state: TidalListState, empty: String) {
    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
    state.error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
    if (!state.loading && state.error == null && state.items.isEmpty()) item { Missing(empty) }
}

/** The TIDAL tab: search, and your favorites and playlists. */
@UnstableApi
@Composable
fun TidalTabContent(ui: Ui, playing: String?) {
    val vm = ui.vm
    val browser = vm.tidalBrowser
    val login by vm.tidal.state.collectAsState()
    if (!login.account) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TIDAL", style = MaterialTheme.typography.titleLarge)
            Text("Search TIDAL and play it with your own music: log in to TIDAL in Settings first.")
            Button(onClick = { vm.navigate(Route.Settings) }) { Text("Settings") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            browser.query, { browser.query = it; if (it.isEmpty()) browser.clearSearch() },
            placeholder = { Text("Search TIDAL") }, singleLine = true,
            leadingIcon = { Icon(R.drawable.ic_search, null) },
            trailingIcon = { if (browser.query.isNotEmpty()) IconAction(R.drawable.ic_close, "Clear") { browser.clearSearch() } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { browser.runSearch() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val results = browser.search
        if (browser.searching || results != null || browser.searchError != null) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (browser.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
                browser.searchError?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                if (results != null) {
                    if (results.tracks.isNotEmpty()) item { SectionHeader("Tracks") }
                    itemsIndexed(results.tracks, key = { _, it -> "t" + it.id }) { i, it -> TidalTrackRow(ui, it, results.tracks, i, playing) }
                    if (results.albums.isNotEmpty()) item { SectionHeader("Albums") }
                    items(results.albums, key = { "b" + it.id }) { TidalEntryRow(ui, it) }
                    if (results.artists.isNotEmpty()) item { SectionHeader("Artists") }
                    items(results.artists, key = { "a" + it.id }) { TidalEntryRow(ui, it) }
                    if (results.playlists.isNotEmpty()) item { SectionHeader("Playlists") }
                    items(results.playlists, key = { "p" + it.id }) { TidalEntryRow(ui, it) }
                }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TidalTab.entries.forEach { t -> FilterChip(selected = browser.tab == t, onClick = { browser.tab = t }, label = { Text(t.label) }) }
        }
        val list = browser.tab.list
        LaunchedEffect(list) { browser.load(list) }
        val state = browser.state(list)
        LazyColumn(Modifier.fillMaxSize()) {
            if (browser.tab == TidalTab.TRACKS && state.items.isNotEmpty()) {
                item {
                    val tracks = state.items.mapNotNull { it.track }
                    PlayButtons(onPlay = { vm.play(tracks, 0, shuffle = false) }, onShuffle = { vm.play(tracks, 0, shuffle = true) }, label = summary(tracks.size, 0))
                }
            }
            if (browser.tab == TidalTab.TRACKS) {
                itemsIndexed(state.items, key = { _, it -> it.id }) { i, it -> TidalTrackRow(ui, it, state.items, i, playing) }
            } else {
                items(state.items, key = { it.id }) { TidalEntryRow(ui, it) }
            }
            listStatus(state, "Nothing here yet.")
            item {
                TextButton(onClick = { browser.load(list, force = true) }, Modifier.padding(8.dp)) { Text("Reload from TIDAL") }
            }
        }
    }
}

/** An album or playlist's tracks. */
@UnstableApi
@Composable
fun TidalTracksScreen(ui: Ui, list: TidalList, id: String, playing: String?) {
    val browser = ui.vm.tidalBrowser
    LaunchedEffect(list, id) {
        browser.load(list, id)
        // Whether it's yours (and so editable) comes from your playlists.
        if (list == TidalList.PLAYLIST_TRACKS) browser.load(TidalList.PLAYLISTS)
    }
    val state = browser.state(list, id)
    val tracks = state.items.mapNotNull { it.track }
    LazyColumn(Modifier.fillMaxSize()) {
        if (tracks.isNotEmpty()) {
            item {
                Row {
                    Box(Modifier.weight(1f)) {
                        PlayButtons(
                            onPlay = { ui.vm.play(tracks, 0, shuffle = false) }, onShuffle = { ui.vm.play(tracks, 0, shuffle = true) },
                            label = summary(tracks.size, tracks.sumOf { it.durationMs ?: 0 }),
                        )
                    }
                    MoreMenu(ui.tidalTrackMenu(tracks, if (list == TidalList.ALBUM_TRACKS) "the album" else "the playlist"))
                }
            }
        }
        // Your own playlist: its entries can be taken out.
        val own = list == TidalList.PLAYLIST_TRACKS && browser.ownPlaylists().any { it.id == id }
        val title = browser.state(TidalList.PLAYLISTS).items.firstOrNull { it.id == id }?.title ?: "the playlist"
        itemsIndexed(state.items, key = { i, it -> "$i:${it.id}" }) { i, it ->
            TidalTrackRow(
                ui, it, state.items, i, playing, numbered = list == TidalList.ALBUM_TRACKS,
                extra = if (own) listOf("Remove from this TIDAL playlist" to { ui.tidalEditor.removeFromPlaylist(id, title, listOf(it)) }) else emptyList(),
            )
        }
        listStatus(state, "No tracks.")
    }
}

@UnstableApi
@Composable
fun TidalArtistScreen(ui: Ui, id: String) {
    val browser = ui.vm.tidalBrowser
    LaunchedEffect(id) { browser.load(TidalList.ARTIST_ALBUMS, id) }
    val state = browser.state(TidalList.ARTIST_ALBUMS, id)
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.items, key = { it.id }) { TidalEntryRow(ui, it) }
        listStatus(state, "No albums.")
    }
}

/** Logs in in TIDAL's own page, catching where it ends; or with the browser and the address pasted back. */
@UnstableApi
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TidalLoginScreen(ui: Ui, playback: Boolean) {
    val vm = ui.vm
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request = remember(playback) { vm.pendingTidalLogin }
    var error by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    if (request == null) {
        Missing("Start the login from Settings.")
        return
    }
    fun finish(address: String) {
        if (finishing) return
        finishing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (playback) TidalLogin.completePlayback(request, address, vm.tidal.playback) else TidalLogin.completeAccount(request, address, vm.tidal.account)
                }
                vm.tidal.changed()
                vm.pendingTidalLogin = null
                vm.back()
                ui.message(if (playback) "Logged in to TIDAL for playback" else "Logged in to TIDAL")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                finishing = false
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (finishing) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            val url = req.url.toString()
                            if (TidalLogin.isRedirect(request, url)) {
                                finish(url)
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            if (TidalLogin.isRedirect(request, url)) {
                                view.stopLoading()
                                finish(url)
                            }
                        }
                    }
                    loadUrl(request.authorizeUrl)
                }
            },
        )
        // If TIDAL's page won't work in here: the browser, and the address it ends on pasted back.
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Login page not working here? Log in with the browser instead, then copy the address the browser ends on " +
                    "(an error page is fine) and paste it below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, request.authorizeUrl.toUri())) }) { Text("Open in browser") }
                Button(enabled = pasted.isNotBlank() && !finishing, onClick = { finish(pasted) }) { Text("Finish") }
            }
            OutlinedTextField(pasted, { pasted = it }, label = { Text("Address the browser ended on") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Settings > TIDAL: the two logins. */
@UnstableApi
@Composable
fun TidalSettingsSection(vm: PlayerViewModel) {
    val state by vm.tidal.state.collectAsState()
    var accountClient by rememberSaveable { mutableStateOf(vm.tidal.account.client.first) }
    var playbackClient by rememberSaveable { mutableStateOf(vm.tidal.playback.client.first) }
    var playbackSecret by rememberSaveable { mutableStateOf(vm.tidal.playback.client.second) }
    Text("TIDAL", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        "Two logins, as in the fooyin plugin. Your account (with your own developer app from developer.tidal.com, whose redirect " +
            "address is ${TidalLogin.ACCOUNT_REDIRECT}) lists and searches TIDAL. Playback uses a client of one of TIDAL's own apps, " +
            "since developer apps only get previews; TIDAL can stop that working at any time.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Account", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (state.account) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Logged in${state.country?.let { " ($it)" } ?: ""}.", Modifier.padding(top = 12.dp))
            OutlinedButton(onClick = { vm.tidal.account.logOut(); vm.tidal.changed() }) { Text("Log out") }
        }
    } else {
        OutlinedTextField(accountClient, { accountClient = it }, label = { Text("Developer app's client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = accountClient.isNotBlank(), onClick = { vm.startTidalLogin(playback = false, accountClient, "") }) { Text("Log in") }
    }
    Text("Playback", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (state.playback) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Logged in.", Modifier.padding(top = 12.dp))
            OutlinedButton(onClick = { vm.tidal.playback.logOut(); vm.tidal.changed() }) { Text("Log out") }
        }
    } else {
        OutlinedTextField(playbackClient, { playbackClient = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            playbackSecret, { playbackSecret = it }, label = { Text("Client secret (if it has one)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = playbackClient.isNotBlank(), onClick = { vm.startTidalLogin(playback = true, playbackClient, playbackSecret) }) { Text("Log in") }
    }
    Spacer(Modifier.height(4.dp))
}

/** Picks one of your TIDAL playlists for [tracks], or makes a new one. */
@UnstableApi
@Composable
fun TidalPlaylistDialog(ui: Ui, tracks: List<Track>, onDone: () -> Unit) {
    val browser = ui.vm.tidalBrowser
    LaunchedEffect(Unit) { browser.load(TidalList.PLAYLISTS) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val what = if (tracks.size == 1) "“${tracks[0].title}”" else "${tracks.size} tracks"
    if (creating) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDone,
            title = { Text("New TIDAL playlist") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    Text("Unlisted: anyone with its link can see it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { ui.tidalEditor.createPlaylist(name.trim(), tracks); onDone() }) { Text("Create") } },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        )
        return
    }
    val state = browser.state(TidalList.PLAYLISTS)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add $what to") },
        text = {
            LazyColumn(Modifier.height(400.dp)) {
                item { EntryRow("New TIDAL playlist…", "", lead = { RoundIcon(R.drawable.ic_add, 40.dp) }, onClick = { creating = true }) }
                items(browser.ownPlaylists(), key = { it.id }) { p ->
                    EntryRow(p.title, p.info, lead = { Cover(ui.covers, p.cover, 40.dp) }, onClick = { ui.tidalEditor.addToPlaylist(tracks, p); onDone() })
                }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
