package com.tlisonbee.mediasync.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.tlisonbee.mediasync.app
import com.tlisonbee.mediasync.sync.SyncScheduler

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MediaSyncTheme {
                MediaSyncRoot(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        // Pick up changes from elsewhere whenever the app comes to the front.
        if (app.settings.ready) SyncScheduler.onOpen(this)
    }

    companion object {
        fun intent(context: Context) = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** The system's Material You colors (minSdk 33 always supports dynamic color). */
@Composable
fun MediaSyncTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
