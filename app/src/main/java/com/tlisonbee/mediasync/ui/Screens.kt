package com.tlisonbee.mediasync.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
        last.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        if (last.warnings.any { "--allow-deletes" in it }) {
            OutlinedButton(onClick = { viewModel.syncNow(allowDeletes = true) }) { Text("Allow these deletions") }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { viewModel.syncNow() }, enabled = progress == null) { Text("Sync now") }
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, config.serverUrl.toUri())) }) { Text("Open web UI") }
    }
    Text(
        "The resolve queue, history and undo are in the web UI for now.",
        style = MaterialTheme.typography.bodySmall,
    )
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
