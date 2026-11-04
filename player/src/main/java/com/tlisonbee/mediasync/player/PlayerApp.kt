package com.tlisonbee.mediasync.player

import android.app.Application
import android.os.Environment
import com.tlisonbee.mediasync.player.library.Covers
import com.tlisonbee.mediasync.player.library.Library
import com.tlisonbee.mediasync.player.library.LibraryDb
import com.tlisonbee.mediasync.tags.AudioTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Holds what the screens and the playback service share: the library and the settings. */
class PlayerApp : Application() {
    val scope = CoroutineScope(SupervisorJob())

    val settings by lazy { PlayerSettings(this) }

    val library by lazy {
        Library(LibraryDb(this), Covers(File(filesDir, "covers")), File(Environment.getExternalStorageDirectory(), "Music"), scope)
    }

    override fun onCreate() {
        super.onCreate()
        AudioTags.useAndroidMode()
    }
}

val android.content.Context.app: PlayerApp get() = applicationContext as PlayerApp
