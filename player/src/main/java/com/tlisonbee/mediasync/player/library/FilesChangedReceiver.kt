package com.tlisonbee.mediasync.player.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import com.tlisonbee.mediasync.player.app
import kotlinx.coroutines.launch

/**
 * MediaSync's word that a sync changed files (retagged, renamed, added, deleted). A player that's
 * running (playing in the background, say) rescans at once, so its lists and next tracks match
 * the files; one that isn't finds the changes when it next opens, as it always does.
 */
class FilesChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val app = context.app
        if (!app.libraryStarted || !Environment.isExternalStorageManager()) return
        val library = app.library
        if (paths.none { it.startsWith(library.root.path + "/") }) return
        android.util.Log.i("PlayerLibrary", "MediaSync changed ${paths.size} files; scanning")
        // The process is already up (a screen or playback started the library), so the scan runs on
        // in its scope; if it's stopped part way, the next scan reads what this one didn't.
        app.scope.launch { library.scan() }
    }

    companion object {
        const val EXTRA_PATHS = "paths"
    }
}
