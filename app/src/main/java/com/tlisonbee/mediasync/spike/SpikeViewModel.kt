package com.tlisonbee.mediasync.spike

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tlisonbee.mediasync.check.RoundTripCheck
import com.tlisonbee.mediasync.tags.AudioTags
import com.tlisonbee.mediasync.tags.Picture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Runs [RoundTripCheck] over music on the phone. Files are copied into the cache first. */
class SpikeViewModel(application: Application) : AndroidViewModel(application) {
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set
    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        AudioTags.useAndroidMode()
    }

    fun run(folder: String, maxFiles: Int) {
        if (running) return
        running = true
        lines = emptyList()
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { check(File(folder).toPath(), maxFiles) }
            lines = report
            running = false
        }
    }

    private fun check(root: Path, maxFiles: Int): List<String> {
        if (!Files.isDirectory(root)) return listOf("Not a folder: $root")
        val files = RoundTripCheck.audioFilesUnder(root).take(maxFiles)
        val scratch = File(getApplication<Application>().cacheDir, "m0").apply { deleteRecursively(); mkdirs() }.toPath()
        val cover = testCover()
        val results = files.mapIndexed { i, file ->
            progress = "${i + 1} / ${files.size}: ${file.fileName}"
            RoundTripCheck.run(file, scratch, cover)
        }
        progress = ""

        val summary = results.groupBy { it.format?.name?.lowercase() ?: "?" }.toSortedMap().map { (format, group) ->
            "$format: ${group.count { it.ok }} ok, ${group.count { !it.ok }} failed"
        }
        val details = results.sortedBy { it.ok }.flatMap { result ->
            val status = if (result.ok) "OK  " else "FAIL"
            listOf("$status ${result.format?.name?.lowercase() ?: "?"} ${result.millis} ms  ${root.relativize(result.file)}") +
                result.problems.map { "   - $it" } +
                result.notes.filter { "read back" in it }.map { "   note: $it" }
        }
        val report = listOf("${files.size} files under $root") + summary + listOf("") + details

        val reportFile = File(getApplication<Application>().getExternalFilesDir(null), "m0-report.txt")
        reportFile.writeText(report.joinToString("\n", postfix = "\n"))
        report.forEach { Log.i(TAG, it) }
        return report + listOf("", "Report saved to $reportFile")
    }

    private fun testCover(): Picture {
        val bitmap = createBitmap(64, 64).apply { eraseColor(0xFF3F51B5.toInt()) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Picture("image/jpeg", bytes.toByteArray())
    }

    private companion object {
        const val TAG = "MediaSyncM0"
    }
}
