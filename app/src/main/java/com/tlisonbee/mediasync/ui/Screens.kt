package com.tlisonbee.mediasync.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.tlisonbee.mediasync.sync.ReviewAction
import com.tlisonbee.mediasync.sync.ReviewGroup
import com.tlisonbee.mediasync.sync.ReviewSettings
import com.tlisonbee.mediasync.sync.label
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSyncRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("MediaSync") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.screen) {
                Screen.PERMISSION -> PermissionScreen()
                Screen.PAIR -> PairScreen(state, viewModel)
                Screen.PREVIEW -> PreviewScreen(state, viewModel)
                Screen.STATUS -> StatusScreen(state, viewModel)
            }
            Spacer(Modifier.height(24.dp))
        }
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
    OutlinedTextField(folder, { folder = it }, label = { Text("Music folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
private fun StatusScreen(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
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

    val progress = state.progress
    if (progress != null) {
        Text(progress.describe())
        if (progress.total > 0) {
            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
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
        if (!state.review.enabled) Button(onClick = { viewModel.syncNow() }, enabled = progress == null) { Text("Sync now") }
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, config.serverUrl.toUri())) }) { Text("Open web UI") }
    }
    Text(
        "The resolve queue, history and undo are in the web UI for now.",
        style = MaterialTheme.typography.bodySmall,
    )
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
    val busy = state.progress != null
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
        review.incoming.forEach { ReviewGroupCard(it, busy, viewModel) }
    }
    if (review.outgoing.isNotEmpty()) {
        Text("Made on this phone", style = MaterialTheme.typography.titleSmall)
        Text(
            "Accept sends a change to the server. Undo here puts the files back as the server has them; an undone new file stays, but isn't synced.",
            style = MaterialTheme.typography.bodySmall,
        )
        review.outgoing.forEach { ReviewGroupCard(it, busy, viewModel) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewGroupCard(group: ReviewGroup, busy: Boolean, viewModel: MainViewModel) {
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
            when (confirm) {
                ReviewAction.REVERT -> {
                    Text("Revert r${group.rev} on the server? Every device gets the old values back. History in the web UI can undo this too.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirm = null; viewModel.decide(group, ReviewAction.REVERT) }) { Text("Revert") }
                        TextButton(onClick = { confirm = null }) { Text("Cancel") }
                    }
                }
                ReviewAction.UNDO -> {
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
