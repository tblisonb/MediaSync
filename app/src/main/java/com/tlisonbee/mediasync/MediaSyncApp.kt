package com.tlisonbee.mediasync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.tlisonbee.mediasync.data.AppIndex
import com.tlisonbee.mediasync.data.Settings
import com.tlisonbee.mediasync.sync.ClientInfo
import com.tlisonbee.mediasync.sync.SyncScheduler
import com.tlisonbee.mediasync.tags.AudioTags

class MediaSyncApp : Application() {
    lateinit var settings: Settings
        private set

    /** The local index, one per process; WorkManager runs the sync in this process too. */
    val index: AppIndex by lazy { AppIndex(this) }

    override fun onCreate() {
        super.onCreate()
        // Android has no java.awt or javax.imageio; jaudiotagger must avoid them.
        AudioTags.useAndroidMode()
        ClientInfo.kind = "android"
        ClientInfo.version = packageManager.getPackageInfo(packageName, 0).versionName
        ClientInfo.minServer = MIN_SERVER
        settings = Settings(this)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, "Sync progress", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_ATTENTION, "Needs your attention", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
        if (settings.ready) SyncScheduler.schedulePeriodic(this)
    }

    companion object {
        /**
         * The oldest server this app works with: 1.3.0 for the Library tab. Raise it whenever the
         * app starts relying on something newer. A sync with an older server stops and says so,
         * and scripts/release.sh puts it in the release notes.
         */
        const val MIN_SERVER = "1.3.0"

        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_ATTENTION = "attention"
    }
}

val Context.app get() = applicationContext as MediaSyncApp
