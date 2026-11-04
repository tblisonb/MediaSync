package com.tlisonbee.mediasync.player

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.tlisonbee.mediasync.player.ui.PlayerRoot
import com.tlisonbee.mediasync.player.ui.PlayerViewModel

@UnstableApi
class PlayerActivity : ComponentActivity() {
    companion object {
        /** Set by the notification: open on the Now Playing screen. */
        const val EXTRA_NOW_PLAYING = "com.tlisonbee.mediasync.player.NOW_PLAYING"
    }

    private val viewModel: PlayerViewModel by viewModels()
    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) {
                Surface { PlayerRoot(viewModel, hasAccess) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_NOW_PLAYING, false) == true) viewModel.nowPlayingOpen = true
    }

    override fun onStart() {
        super.onStart()
        // Checked again on return from the settings screen.
        hasAccess = Environment.isExternalStorageManager()
        if (hasAccess) {
            app.library.scanSoon()
            app.library.refreshRemote()
        }
    }
}
