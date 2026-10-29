package com.tlisonbee.mediasync.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tlisonbee.mediasync.R
import com.tlisonbee.mediasync.api.FolderPickItem
import com.tlisonbee.mediasync.api.PickItem
import com.tlisonbee.mediasync.library.SyncMode
import com.tlisonbee.mediasync.sync.ReviewAction
import com.tlisonbee.mediasync.sync.bytes
import com.tlisonbee.mediasync.sync.ReviewGroup
import com.tlisonbee.mediasync.sync.ReviewSettings
import com.tlisonbee.mediasync.sync.label
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class Tab { SYNC, LIBRARY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSyncRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val libraryState by viewModel.library.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.SYNC) }
    val context = LocalContext.current
    val server = state.config?.serverUrl
    // Once paired, the web UI is a tab of its own. Its web view outlives the tab, so pages keep their place.
    val library = remember(server) { server?.let { Library(context, it) { next -> viewModel.openLibrary(next) } } }
    val tabs = state.screen == Screen.STATUS && library != null
    val onLibrary = tabs && tab == Tab.LIBRARY

    // Back steps through the web pages, then returns to the Sync tab.
    BackHandler(enabled = onLibrary) { if (library?.back() != true) tab = Tab.SYNC }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // The web UI has a header of its own.
            topBar = { if (!onLibrary) TopAppBar(title = { Text("MediaSync") }) },
            bottomBar = {
                if (tabs) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.SYNC,
                            onClick = { tab = Tab.SYNC },
                            icon = { Icon(painterResource(R.drawable.ic_tab_sync), contentDescription = null) },
                            label = { Text("Sync") },
                        )
                        NavigationBarItem(
                            selected = onLibrary,
                            // Tapped again, it reloads the page.
                            onClick = { if (onLibrary) library?.reload() else tab = Tab.LIBRARY },
                            icon = { Icon(painterResource(R.drawable.ic_tab_library), contentDescription = null) },
                            label = { Text("Library") },
                        )
                    }
                }
            },
        ) { padding ->
            if (onLibrary) {
                LibraryTab(library!!, libraryState, viewModel, Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize())
            } else {
                Column(
                    Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state.screen) {
                        Screen.STATUS if state.videos.open -> VideosScreen(state, viewModel)
                        Screen.STATUS if state.games.open -> GamesScreen(state, viewModel)
                        Screen.PERMISSION -> PermissionScreen()
                        Screen.PAIR -> PairScreen(state, viewModel)
                        Screen.PREVIEW -> PreviewScreen(state, viewModel)
                        Screen.STATUS -> StatusScreen(state, viewModel, openLibrary = { tab = Tab.LIBRARY })
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (onLibrary) library?.fullScreen?.let { FullScreen(it) }
    }
}

@Composable
private fun PermissionScreen() {
    val context = LocalContext.current
    Text("All files access", style = MaterialTheme.typography.titleLarge)
    Text(
        "MediaSync keeps your Music folder in step with your library: it reads the files, rewrites their tags and " +
            "renames them. Android only allows that to apps with All files access.",
    )
    Button(onClick = {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()))
    }) { Text("Allow All files access") }
}

@Composable
private fun PairScreen(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("https://") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(viewModel.defaultName) }
    var folder by rememberSaveable { mutableStateOf(viewModel.defaultFolder) }
    var scanError by remember { mutableStateOf<String?>(null) }

    Text("Pair with your server", style = MaterialTheme.typography.titleLarge)
    Text("In the web UI, open Devices and choose Pair a device. Scan the code it shows, or type the server address and code.")
    OutlinedButton(onClick = {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val uri = barcode.rawValue.orEmpty().toUri()
                if (uri.scheme == "mediasync" && uri.host == "pair") {
                    uri.getQueryParameter("server")?.let { url = it }
                    uri.getQueryParameter("code")?.let { code = it }
                    scanError = null
                } else {
                    scanError = "That isn't a MediaSync pairing code."
                }
            }
            .addOnFailureListener { scanError = "Couldn't scan: ${it.message}" }
    }) { Text("Scan QR code") }
    scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedTextField(url, { url = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { code = it }, label = { Text("Pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(name, { name = it }, label = { Text("Name for this phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FolderField(folder, { folder = it }, "Music folder")
    state.pairing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { viewModel.pair(url, code, name, folder) }, enabled = !state.pairing.busy && code.isNotBlank()) {
        Text(if (state.pairing.busy) "Pairing…" else "Pair")
    }
}

@Composable
private fun PreviewScreen(state: UiState, viewModel: MainViewModel) {
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.approve() }
    val preview = state.preview
    Text("Before the first sync", style = MaterialTheme.typography.titleLarge)
    Text("Here's what the first sync does to ${state.config?.folders?.firstOrNull()?.folder}. Nothing has been changed yet.")
    when {
        preview.running -> {
            Text("Reading tags: ${preview.read} of ${preview.total}")
            if (preview.total > 0) {
                LinearProgressIndicator(progress = { preview.read.toFloat() / preview.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        preview.error != null -> {
            Text("Couldn't make the preview: ${preview.error}", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = viewModel::runPreview) { Text("Try again") }
        }
        preview.result != null -> {
            for (folder in preview.result.folders) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        folder.describe().forEach { Text(it) }
                        if (folder.examples.isNotEmpty()) {
                            Text("For example:", style = MaterialTheme.typography.labelLarge)
                            folder.examples.forEach { (from, to) ->
                                Text("$from\n→ $to", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            Text("Files the server already has are matched up, not uploaded again. The server keeps every file as it was before.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Start syncing") }
                TextButton(onClick = viewModel::unpair) { Text("Start over") }
            }
        }
        else -> CircularProgressIndicator()
    }
}

@Composable
private fun StatusScreen(state: UiState, viewModel: MainViewModel, openLibrary: () -> Unit) {
    val config = state.config ?: return
    val folder = config.folders.first()
    var confirmUnpair by remember { mutableStateOf(false) }
    LaunchedEffect(state.progress == null) { viewModel.refresh() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(config.deviceName, style = MaterialTheme.typography.titleMedium)
            Text("${folder.folder} ↔ ${folder.prefix} (${folder.mode.label})", style = MaterialTheme.typography.bodySmall)
            Text(config.serverUrl, style = MaterialTheme.typography.bodySmall)
            Text("${"%,d".format(state.files)} files tracked", style = MaterialTheme.typography.bodySmall)
        }
    }

    // What waits for a decision comes first; the last sync's details follow.
    if (state.review.enabled) ReviewSection(state, viewModel)

    // With review on, what's happening shows above its buttons instead.
    if (!state.review.enabled && state.busy) {
        ActivityLine(state)
    } else {
        val last = state.lastSync
        Text(last.at?.let { "Last synced " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Not synced yet")
        last.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        last.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Warnings(last.warnings)
        if (last.warnings.any { "--allow-deletes" in it }) {
            OutlinedButton(onClick = { viewModel.syncNow(allowDeletes = true) }) { Text("Allow these deletions") }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // With review on, syncing everything at once would skip the asking.
        if (!state.review.enabled) Button(onClick = { viewModel.syncNow() }, enabled = !state.busy) { Text("Sync now") }
        OutlinedButton(onClick = openLibrary) { Text("Open the library") }
    }
    Text(
        "The resolve queue, history, undo and tag editing are in the Library tab.",
        style = MaterialTheme.typography.bodySmall,
    )
    ImagesCard(state, viewModel)
    VideosCard(state, viewModel)
    GamesCard(state, viewModel)
    ReviewSettingsCard(state, viewModel)
    if (confirmUnpair) {
        Text("Unpair this phone? Its files stay; it stops syncing until paired again.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { confirmUnpair = false; viewModel.unpair() }) { Text("Unpair") }
            TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") }
        }
    } else {
        TextButton(onClick = { confirmUnpair = true }) { Text("Unpair…") }
    }
}

/** What waits for approval, with a card per group of changes. */
@Composable
private fun ReviewSection(state: UiState, viewModel: MainViewModel) {
    val review = state.review.review
    val busy = state.busy
    Text(
        if (review.isEmpty) "Nothing waiting" else review.summary("this phone").replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        "Last checked " + (state.review.lastCheck?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "never") +
            " · checks " + ReviewSettings.describe(state.review.checkMinutes),
        style = MaterialTheme.typography.bodySmall,
    )
    review.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    ActivityLine(state)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = viewModel::checkNow, enabled = !busy) { Text("Check now") }
        if (!review.isEmpty) Button(onClick = viewModel::acceptAll, enabled = !busy) { Text("Accept all") }
    }
    if (review.incoming.isNotEmpty()) {
        Text("From the server", style = MaterialTheme.typography.titleSmall)
        Text(
            "Accept writes a change into your files. To leave it for later, do nothing. Revert undoes it on the server, for every device.",
            style = MaterialTheme.typography.bodySmall,
        )
        review.incoming.forEach { ReviewGroupCard(it, busy, state.applying[it.key], viewModel) }
    }
    if (review.outgoing.isNotEmpty()) {
        Text("Made on this phone", style = MaterialTheme.typography.titleSmall)
        Text(
            "Accept sends a change to the server. Undo here puts the files back as the server has them; an undone new file stays, but isn't synced.",
            style = MaterialTheme.typography.bodySmall,
        )
        review.outgoing.forEach { ReviewGroupCard(it, busy, state.applying[it.key], viewModel) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewGroupCard(group: ReviewGroup, busy: Boolean, applying: String?, viewModel: MainViewModel) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    var confirm by remember(group.key, group.version) { mutableStateOf<ReviewAction?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.title, style = MaterialTheme.typography.titleSmall)
            val conflicts = group.items.count { it.conflict }
            Text(
                listOfNotNull(group.subtitle, "${group.items.size} ${if (group.items.size == 1) "file" else "files"}", conflicts.takeIf { it > 0 }?.let { "$it changed on both sides" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                applying != null -> Working(applying)
                confirm == ReviewAction.REVERT -> {
                    Text("Revert r${group.rev} on the server? Every device gets the old values back. History in the web UI can undo this too.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirm = null; viewModel.decide(group, ReviewAction.REVERT) }) { Text("Revert") }
                        TextButton(onClick = { confirm = null }) { Text("Cancel") }
                    }
                }
                confirm == ReviewAction.UNDO -> {
                    Text("Put these files back as the server has them? What changed here is lost.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirm = null; viewModel.decide(group, ReviewAction.UNDO) }) { Text("Undo here") }
                        TextButton(onClick = { confirm = null }) { Text("Cancel") }
                    }
                }
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.decide(group, ReviewAction.ACCEPT) }, enabled = !busy) { Text("Accept") }
                    if (!group.incoming) OutlinedButton(onClick = { confirm = ReviewAction.UNDO }, enabled = !busy) { Text("Undo here") }
                    if (group.incoming && group.rev != null) TextButton(onClick = { confirm = ReviewAction.REVERT }, enabled = !busy) { Text("Revert…") }
                }
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide the files" else "Show the files") }
            if (expanded) {
                group.items.take(MAX_ITEMS).forEach { item ->
                    Column {
                        Text(item.path, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(
                            item.detail + if (item.conflict) " · changed on both sides" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (group.items.size > MAX_ITEMS) Text("And ${group.items.size - MAX_ITEMS} more.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * What the sync is doing, from the moment a button asks for it: a spinner and a line at once,
 * then the sync's own progress once it reports some.
 */
@Composable
private fun ActivityLine(state: UiState) {
    val activity = state.activity ?: return
    val progress = state.progress
    // Work that hasn't started after a few seconds is waiting for a network (or a retry).
    var stuck by remember(activity) { mutableStateOf(false) }
    LaunchedEffect(activity) {
        if (!activity.started) {
            delay(5_000)
            stuck = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Working(activity.kind.label)
        when {
            progress != null -> {
                Text(progress.describe(), style = MaterialTheme.typography.bodySmall)
                if (progress.total > 0) {
                    LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            stuck -> Text("Waiting to start: it needs a connection to the server.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A small spinner beside "[label]…". */
@Composable
private fun Working(label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("$label…", color = MaterialTheme.colorScheme.primary)
    }
}

private const val MAX_ITEMS = 200

@Composable
private fun ReviewSettingsCard(state: UiState, viewModel: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask before syncing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.review.enabled) {
                            "MediaSync checks for changes and asks before it changes your files or the server."
                        } else {
                            "MediaSync syncs as soon as anything changes, here or on the server."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.review.enabled, onCheckedChange = { viewModel.setReview(enabled = it) })
            }
            if (state.review.enabled) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("Check " + ReviewSettings.describe(state.review.checkMinutes)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ReviewSettings.CHOICES.forEach { minutes ->
                            DropdownMenuItem(text = { Text(ReviewSettings.describe(minutes)) }, onClick = { menu = false; viewModel.setReview(minutes = minutes) })
                        }
                    }
                }
            }
        }
    }
}

/** The first few warnings, and the rest behind a button: a sync can leave dozens. */
@Composable
private fun Warnings(warnings: List<String>) {
    var all by remember(warnings) { mutableStateOf(false) }
    val shown = if (all) warnings else warnings.take(2)
    shown.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
    if (warnings.size > shown.size) TextButton(onClick = { all = true }) { Text("Show all ${warnings.size} warnings") }
}

/** The folders of the library's images/ this phone syncs, each with a folder of its own. */
@Composable
private fun ImagesCard(state: UiState, viewModel: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pictures", style = MaterialTheme.typography.titleSmall)
            Text(
                "Sync folders of the library's images/ with folders on this phone, each its own way: the camera's photos " +
                    "from DCIM, say, and screenshots from Pictures/Screenshots.",
                style = MaterialTheme.typography.bodySmall,
            )
            for (folder in state.imageFolders) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${folder.prefix} ↔ ${viewModel.local(folder.folder)}")
                        Text(describe(folder.mode), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.removeImages(folder.prefix) }, enabled = !state.images.saving) { Text("Remove") }
                }
                if (folder.mode == SyncMode.SEND_ONLY) KeepDeleted(folder.keepDeleted) { viewModel.setKeepDeleted(folder.prefix, it) }
            }
            if (state.images.adding) {
                AddImages(state.images, viewModel)
            } else {
                TextButton(onClick = viewModel::openAddImages) { Text("Add a folder…") }
            }
            state.images.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** What an image folder's mode does, for this phone. */
private fun describe(mode: SyncMode) = when (mode) {
    SyncMode.TWO_WAY -> "Both ways: what's added on either side comes to the other."
    SyncMode.SEND_ONLY -> "To the library only: nothing comes back, and the names here stay as they are."
    SyncMode.RECEIVE_ONLY -> "From the library only: changes made here aren't sent."
}

/** For a send-only folder: clearing it to make room leaves the library alone. */
@Composable
private fun KeepDeleted(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text("Keep in the library when deleted here", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * A folder's path, typed in or chosen with the system's folder picker (Browse…). [onPicked] gets
 * the picked folder's full path; by default it becomes the value.
 */
@Composable
private fun FolderField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String? = null,
    onPicked: (String) -> Unit = onValueChange,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = folderPath(uri)
        error = if (path == null) "Choose a folder in the phone's storage or on its SD card." else null
        path?.let(onPicked)
    }
    OutlinedTextField(
        value = value, onValueChange = { error = null; onValueChange(it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        trailingIcon = { TextButton(onClick = { picker.launch(null) }) { Text("Browse…") } },
    )
}

/**
 * The file path of a folder the system's picker chose ("primary:DCIM/Camera" is
 * /storage/emulated/0/DCIM/Camera), or null for one that isn't in the phone's storage or on an SD
 * card. With All files access, the path is all the sync needs.
 */
private fun folderPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    val inside = id.substringAfter(':', "")
    val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
    return (if (inside.isEmpty()) root else root.resolve(inside)).path
}

/** Picks a folder of the library's images/, a folder on the phone, and which way they sync. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddImages(images: ImagesState, viewModel: MainViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(SyncMode.TWO_WAY) }
    var keepDeleted by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Library folder") }, prefix = { Text("images/") },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("photos", "screenshots", "art", "other").forEach { FilterChip(selected = name == it, onClick = { name = it }, label = { Text(it) }) }
    }
    fun choose(chosen: String) {
        folder = chosen
        // The camera's folder sends: the library files photos by date, the camera keeps its names,
        // and clearing the phone to make room leaves the library alone.
        val camera = chosen.startsWith(Environment.DIRECTORY_DCIM)
        mode = if (camera) SyncMode.SEND_ONLY else SyncMode.TWO_WAY
        keepDeleted = camera
    }
    FolderField(
        folder, { folder = it }, "Folder on this phone", "In the phone's storage, such as DCIM or Pictures/Screenshots",
        onPicked = { choose(viewModel.local(it)) },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.suggestions.forEach { FilterChip(selected = folder == it, onClick = { choose(it) }, label = { Text(it) }) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(SyncMode.TWO_WAY to "Both ways", SyncMode.SEND_ONLY to "To the library", SyncMode.RECEIVE_ONLY to "From the library").forEach { (m, label) ->
            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(label) })
        }
    }
    Text(describe(mode), style = MaterialTheme.typography.bodySmall)
    if (mode == SyncMode.SEND_ONLY) KeepDeleted(keepDeleted) { keepDeleted = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { viewModel.addImages(name, folder, mode, keepDeleted) }, enabled =!images.saving && name.isNotBlank() && folder.isNotBlank()) { Text("Add") }
        TextButton(onClick = viewModel::closeAddImages) { Text("Cancel") }
        if (images.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun VideosCard(state: UiState, viewModel: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Videos", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keep the movies and episodes you pick in Movies/MediaSync. The rest stay on the server.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.videosEnabled, onCheckedChange = viewModel::setVideos)
            }
            if (state.videosEnabled) TextButton(onClick = viewModel::openVideos) { Text("Choose videos…") }
            state.videos.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun GamesCard(state: UiState, viewModel: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Games", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keep the games and platforms you pick in ROMs/MediaSync, for an emulator such as RetroArch. The rest stay on the server.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.gamesEnabled, onCheckedChange = viewModel::setGames)
            }
            if (state.gamesEnabled) TextButton(onClick = viewModel::openGames) { Text("Choose games…") }
            state.games.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Every platform with its games, to pick whole platforms (now and later) or single games from. */
@Composable
private fun GamesScreen(state: UiState, viewModel: MainViewModel) {
    val games = state.games
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Choose games", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = viewModel::closeGames) { Text("Cancel") }
    }
    Text("A picked game is kept on this phone with all its discs and files. Keeping a whole platform also brings the games added to it later.")
    when {
        games.loading -> CircularProgressIndicator()
        games.error != null -> Text(games.error, color = MaterialTheme.colorScheme.error)
        games.items.isEmpty() -> Text("The library has no games yet.")
        else -> {
            Text("${bytes(games.chosenBytes)} picked", style = MaterialTheme.typography.bodySmall)
            Button(onClick = viewModel::saveGames, enabled = games.changes > 0 && !games.saving) {
                Text(if (games.saving) "Saving…" else "Save ${games.changes} ${if (games.changes == 1) "change" else "changes"}")
            }
            val platforms = games.items.filter { it.whole }
            platforms.forEach { platform ->
                PlatformGroup(platform, games.items.filter { !it.whole && games.platformOf(it) == platform.folder }, games.chosen, viewModel)
            }
        }
    }
}

@Composable
private fun PlatformGroup(platform: FolderPickItem, items: List<FolderPickItem>, chosen: Set<String>, viewModel: MainViewModel) {
    var expanded by remember(platform.folder) { mutableStateOf(items.size <= 12) }
    val whole = platform.folder in chosen
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(platform.label, style = MaterialTheme.typography.titleSmall)
                    val picked = if (whole) items.size else items.count { it.folder in chosen }
                    Text("$picked of ${items.size} kept · ${bytes(platform.size)}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                Text("Keep every ${platform.label} game", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = whole, onCheckedChange = { viewModel.chooseGames(listOf(platform.folder), it) })
            }
            if (expanded) {
                items.sortedBy { it.label.lowercase() }.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = whole || item.folder in chosen,
                            enabled = !whole,
                            onCheckedChange = { viewModel.chooseGames(listOf(item.folder), it) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                bytes(item.size) + if (item.files > 1) " · ${item.files} files" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Every video in the library, grouped into movies, each show's seasons, and the rest, to pick from. */
@Composable
private fun VideosScreen(state: UiState, viewModel: MainViewModel) {
    val videos = state.videos
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Choose videos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = viewModel::closeVideos) { Text("Cancel") }
    }
    Text("Picked videos are kept on this phone, with their subtitles and posters. Unpicking one removes it from the phone only.")
    when {
        videos.loading -> CircularProgressIndicator()
        videos.error != null -> Text(videos.error, color = MaterialTheme.colorScheme.error)
        videos.items.isEmpty() -> Text("The library has no videos yet.")
        else -> {
            Text("${videos.chosen.size} picked, ${bytes(videos.chosenBytes)}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = viewModel::saveVideos, enabled = videos.changes > 0 && !videos.saving) {
                Text(if (videos.saving) "Saving…" else "Save ${videos.changes} ${if (videos.changes == 1) "change" else "changes"}")
            }
            groups(videos.items).forEach { (title, items) -> VideoGroup(title, items, videos.chosen, viewModel) }
        }
    }
}

/** "Movies"; "Fringe · Season 01" for each show's season; "Other". */
private fun groups(items: List<PickItem>): List<Pair<String, List<PickItem>>> =
    items.groupBy { item ->
        val parts = item.path.split('/')
        when (item.category) {
            "movies" -> "Movies"
            "shows" -> listOfNotNull(parts.getOrNull(2), parts.getOrNull(3)?.takeIf { parts.size > 4 }).joinToString(" · ")
            else -> "Other"
        }
    }.toList().sortedBy { (title, _) -> if (title == "Movies") "" else if (title == "Other") "\uFFFF" else title.lowercase() }

@Composable
private fun VideoGroup(title: String, items: List<PickItem>, chosen: Set<String>, viewModel: MainViewModel) {
    var expanded by remember(title) { mutableStateOf(items.size <= 12) }
    val ids = items.map { it.assetId }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ids.all { it in chosen }, onCheckedChange = { viewModel.choose(ids, it) })
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text("${ids.count { it in chosen }} of ${items.size} picked · ${bytes(items.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) {
                items.sortedBy { it.label.lowercase() }.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.assetId in chosen, onCheckedChange = { viewModel.choose(listOf(item.assetId), it) })
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                            Text(bytes(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
