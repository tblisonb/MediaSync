package com.tlisonbee.mediasync.spike

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/** Milestone 0: check that tags can be rewritten on Android without touching the media. */
class SpikeActivity : ComponentActivity() {
    private val viewModel: SpikeViewModel by viewModels()
    private var hasFileAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SpikeScreen(
                    viewModel = viewModel,
                    hasFileAccess = hasFileAccess,
                    requestFileAccess = {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasFileAccess = Environment.isExternalStorageManager()
    }
}

@Composable
private fun SpikeScreen(viewModel: SpikeViewModel, hasFileAccess: Boolean, requestFileAccess: () -> Unit) {
    var folder by rememberSaveable { mutableStateOf("/storage/emulated/0/Music") }
    var maxFiles by rememberSaveable { mutableStateOf("50") }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tag round-trip check", style = MaterialTheme.typography.titleLarge)
            Text(
                "Copies each file into the app's cache, rewrites its tags and checks the audio is untouched. " +
                    "Your files are only read.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!hasFileAccess) {
                Button(onClick = requestFileAccess) { Text("Allow access to all files") }
                return@Column
            }
            OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth(), label = { Text("Folder") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(maxFiles, { maxFiles = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Max files") }, singleLine = true)
                Button(
                    onClick = { viewModel.run(folder, maxFiles.toIntOrNull() ?: 50) },
                    enabled = !viewModel.running,
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) { Text(if (viewModel.running) "Running…" else "Run check") }
            }
            if (viewModel.progress.isNotEmpty()) Text(viewModel.progress, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth()) {
                items(viewModel.lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
