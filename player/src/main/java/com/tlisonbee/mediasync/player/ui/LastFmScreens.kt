package com.tlisonbee.mediasync.player.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.text.format.DateUtils
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.tlisonbee.mediasync.player.R
import com.tlisonbee.mediasync.player.lastfm.LastFmEntry
import com.tlisonbee.mediasync.player.lastfm.LastFmImages
import com.tlisonbee.mediasync.player.lastfm.LastFmPeriod
import com.tlisonbee.mediasync.player.lastfm.LastFmProfile
import com.tlisonbee.mediasync.player.lastfm.LastFmStats
import com.tlisonbee.mediasync.player.library.LibraryData
import com.tlisonbee.mediasync.player.library.Track
import com.tlisonbee.mediasync.player.library.sortKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings > Last.fm: your API account and the login, and how scrobbling is going. */
@UnstableApi
@Composable
fun LastFmSettingsSection(vm: PlayerViewModel) {
    val state by vm.lastFm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var apiKey by rememberSaveable { mutableStateOf(state.apiKey) }
    var secret by rememberSaveable { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Last.fm", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        "Scrobbles what you play (your files, the server's and TIDAL's) once it has played for half its length or four minutes, " +
            "and shows your stats (menu > Last.fm). Scrobbles made offline are sent later.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val user = state.user
    if (user != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Logged in as $user.", Modifier.padding(top = 12.dp))
            OutlinedButton(onClick = { vm.lastFm.logOut() }) { Text("Log out") }
        }
        if (state.pending > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.pending == 1) "1 scrobble waiting to be sent." else "${state.pending} scrobbles waiting to be sent.", Modifier.weight(1f))
                OutlinedButton(onClick = { vm.lastFm.flush() }) { Text("Send now") }
            }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        return
    }
    Text(
        "Needs your own Last.fm API account: create one at last.fm/api/account/create (any name; leave the callback URL empty), " +
            "then enter its API key and shared secret here.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        secret, { secret = it }, label = { Text("Shared secret") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Button(
        enabled = apiKey.isNotBlank() && secret.isNotBlank() && !starting,
        onClick = {
            starting = true
            error = null
            scope.launch {
                try {
                    val login = withContext(Dispatchers.IO) { vm.lastFm.startLogin(apiKey, secret) }
                    vm.pendingLastFmLogin = login to secret
                    vm.navigate(Route.LastFmLoginPage)
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                } finally {
                    starting = false
                }
            }
        },
    ) { Text("Log in") }
    if (starting) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

/** Last.fm's page where you allow Segue; meanwhile Segue asks Last.fm every few seconds whether you have. */
@UnstableApi
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LastFmLoginScreen(ui: Ui) {
    val vm = ui.vm
    val context = LocalContext.current
    val pending = remember { vm.pendingLastFmLogin }
    var error by remember { mutableStateOf<String?>(null) }
    if (pending == null) {
        Missing("Start the login from Settings.")
        return
    }
    val (login, secret) = pending
    LaunchedEffect(login) {
        // A token is good for an hour.
        repeat(1200) {
            delay(3000)
            try {
                val name = withContext(Dispatchers.IO) { vm.lastFm.finishLogin(login, secret) }
                if (name != null) {
                    vm.pendingLastFmLogin = null
                    vm.back()
                    ui.message("Logged in to Last.fm as $name")
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = WebViewClient()
                    loadUrl(login.authorizeUrl)
                }
            },
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Log in and tap “Yes, allow access”; Segue notices by itself. Page not working here? Allow it in the browser instead and come back.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, login.authorizeUrl.toUri())) }) { Text("Open in browser") }
        }
    }
}

/** Your play count of the track on Now Playing, from Last.fm (cached), when you're logged in. */
@UnstableApi
@Composable
fun LastFmPlays(vm: PlayerViewModel, track: Track?, artist: String?, title: String?) {
    val state by vm.lastFm.state.collectAsState()
    if (state.user == null) return
    val a = track?.artists?.firstOrNull() ?: artist ?: return
    val t = track?.title ?: title ?: return
    val stats = vm.lastFm.stats
    // Asked again when a scrobble is queued or sent, so the count follows.
    val plays by produceState(stats.cachedPlays(a, t), a, t, state.pending) {
        value = stats.cachedPlays(a, t) ?: withContext(Dispatchers.IO) { runCatching { stats.plays(a, t) }.getOrNull() }
    }
    val n = plays ?: return
    Text(
        if (n == 1) "1 play on Last.fm" else "${NumberFormat.getIntegerInstance().format(n)} plays on Last.fm",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private enum class StatsTab(val label: String, val kind: LastFmStats.Kind?) {
    RECENT("Recent", null),
    ARTISTS("Artists", LastFmStats.Kind.ARTISTS),
    ALBUMS("Albums", LastFmStats.Kind.ALBUMS),
    TRACKS("Tracks", LastFmStats.Kind.TRACKS),
}

/** One list on the stats page, page by page. */
private class StatsList(val entries: List<LastFmEntry> = emptyList(), val page: Int = 0, val totalPages: Int = 1, val loading: Boolean = false, val error: String? = null)

/** Your Last.fm stats: totals, recent scrobbles, and top artists, albums and tracks over a period. */
@UnstableApi
@Composable
fun LastFmStatsScreen(ui: Ui, data: LibraryData, playing: String?) {
    val vm = ui.vm
    val state by vm.lastFm.state.collectAsState()
    if (state.user == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("See your scrobbles and top artists, albums and tracks: log in to Last.fm in Settings first.")
            Button(onClick = { vm.navigate(Route.Settings) }) { Text("Settings") }
        }
        return
    }
    val stats = vm.lastFm.stats
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(StatsTab.RECENT) }
    var period by rememberSaveable { mutableStateOf(LastFmPeriod.MONTH) }
    var reload by remember { mutableIntStateOf(0) }
    val profile by produceState<LastFmProfile?>(null, reload) { value = withContext(Dispatchers.IO) { runCatching { stats.profile() }.getOrNull() } }
    // Kept per tab and period while the page is open.
    val lists = remember { androidx.compose.runtime.mutableStateMapOf<String, StatsList>() }
    val listKey = if (tab == StatsTab.RECENT) "recent" else "${tab.name}/${period.name}"

    fun load(more: Boolean) {
        val key = listKey
        val kind = tab.kind
        val p = period
        val now = lists[key] ?: StatsList()
        if (now.loading) return
        val page = if (more) now.page + 1 else 1
        lists[key] = StatsList(if (more) now.entries else emptyList(), now.page, now.totalPages, loading = true)
        scope.launch {
            try {
                val got = withContext(Dispatchers.IO) { if (kind == null) stats.recent(page) else stats.top(kind, p, page) }
                val before = if (more) now.entries else emptyList()
                lists[key] = StatsList(before + got.entries, page, got.totalPages)
            } catch (e: Exception) {
                lists[key] = StatsList(now.entries, now.page, now.totalPages, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }
    LaunchedEffect(listKey, reload) { if (lists[listKey] == null || reload > 0) load(more = false) }
    val list = lists[listKey] ?: StatsList(loading = true)
    val index = remember(data) { LibraryIndex(data) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(state.user.orEmpty(), style = MaterialTheme.typography.titleLarge)
                profile?.let { p ->
                    val since = p.since?.let { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(it * 1000)) }
                    Text(
                        listOfNotNull(
                            "${NumberFormat.getIntegerInstance().format(p.scrobbles)} scrobbles",
                            p.artists?.let { "${NumberFormat.getIntegerInstance().format(it)} artists" },
                            since?.let { "since $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.pending > 0) {
                    Text(
                        if (state.pending == 1) "1 scrobble waiting to be sent" else "${state.pending} scrobbles waiting to be sent",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }) }
            }
            if (tab != StatsTab.RECENT) PeriodPicker(period) { period = it }
        }
        itemsIndexed(list.entries, key = { i, e -> "$listKey/$i/${e.name}" }) { i, e ->
            StatsRow(ui, index, tab, if (tab == StatsTab.RECENT) null else i + 1, e, playing)
        }
        if (list.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        list.error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        if (!list.loading && list.error == null && list.entries.isEmpty()) item { Missing("Nothing here yet.") }
        item {
            Row(Modifier.padding(8.dp)) {
                if (!list.loading && list.page < list.totalPages && list.entries.isNotEmpty()) TextButton(onClick = { load(more = true) }) { Text("Load more") }
                TextButton(onClick = { lists.clear(); reload++ }) { Text("Reload") }
            }
        }
    }
}

@Composable
private fun PeriodPicker(period: LastFmPeriod, onPick: (LastFmPeriod) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { open = true }) { Text(period.label + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LastFmPeriod.entries.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { open = false; onPick(p) }) }
        }
    }
}

/** Finds Last.fm's entries in the library: by artist, album and title, as the library sorts names. */
private class LibraryIndex(data: LibraryData) {
    private val albums = data.albums.associateBy { sortKey(it.artist) + "\u0000" + sortKey(it.title) }
    private val tracks = HashMap<String, Track>().apply {
        for (t in data.tracks) {
            for (a in (t.artists + t.albumArtist).filter { it.isNotBlank() }) putIfAbsent(sortKey(a) + "\u0000" + sortKey(t.title), t)
        }
    }
    private val artists = data

    fun artist(name: String) = artists.artist(name)
    fun album(artist: String, title: String) = albums[sortKey(artist) + "\u0000" + sortKey(title)]
    fun track(artist: String, title: String) = tracks[sortKey(artist) + "\u0000" + sortKey(title)]
}

@UnstableApi
@Composable
private fun StatsRow(ui: Ui, index: LibraryIndex, tab: StatsTab, rank: Int?, e: LastFmEntry, playing: String?) {
    val vm = ui.vm
    val artist = if (tab == StatsTab.ARTISTS) index.artist(e.name) else null
    val album = if (tab == StatsTab.ALBUMS) index.album(e.artist, e.name) else null
    val track = if (tab == StatsTab.RECENT || tab == StatsTab.TRACKS) index.track(e.artist, e.name) else null
    // Your own cover where the library has it, else Last.fm's image.
    val cover = album?.cover ?: track?.cover ?: artist?.albums?.firstNotNullOfOrNull { it.cover } ?: e.image?.let { LastFmImages.key(it) }
    val subtitle = when (tab) {
        StatsTab.RECENT -> listOf(e.artist, e.album).filter { it.isNotEmpty() }.joinToString(" · ")
        StatsTab.ARTISTS -> ""
        else -> e.artist
    }
    val trailing = when {
        e.nowPlaying -> "Now"
        e.time != null -> DateUtils.getRelativeTimeSpanString(e.time * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        e.plays != null -> NumberFormat.getIntegerInstance().format(e.plays)
        else -> ""
    }
    val open: () -> Unit = {
        when {
            artist != null -> vm.navigate(Route.ArtistPage(artist.name))
            album != null -> vm.navigate(Route.AlbumPage(album.key))
            track != null -> vm.play(listOf(track))
            // Not in the library: look for it on TIDAL.
            vm.tidal.state.value.account -> {
                vm.tidalBrowser.query = listOf(e.artist, e.name).filter { it.isNotEmpty() }.joinToString(" ")
                vm.tidalBrowser.runSearch()
                vm.tab = HomeTab.TIDAL
                while (vm.stack.size > 1) vm.back()
            }
            else -> ui.message("“${e.name}” isn't in your library")
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = open).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rank != null) Text(rank.toString(), Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tab == StatsTab.ARTISTS && cover == null) RoundIcon(R.drawable.ic_note, 44.dp)
        else Cover(ui.covers, cover, 44.dp, corner = if (tab == StatsTab.ARTISTS) 22.dp else 4.dp)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                e.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (track != null && track.path == playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = if (e.nowPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
