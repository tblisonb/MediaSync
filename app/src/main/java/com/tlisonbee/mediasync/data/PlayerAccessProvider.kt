package com.tlisonbee.mediasync.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.tlisonbee.mediasync.app
import com.tlisonbee.mediasync.library.RepoPaths

/**
 * Lends the MediaSync Player this phone's pairing, so it can stream what the phone doesn't hold:
 * the server's address, this device's token, and the folder music/ syncs to. Only apps signed with
 * MediaSync's key can read it (the provider's permission is a `signature` one), and nothing can
 * write. Empty until the phone is paired.
 */
class PlayerAccessProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val settings = context?.app?.settings ?: return cursor
        val config = settings.config ?: return cursor
        val token = settings.token ?: return cursor
        val music = config.folders.firstOrNull { it.prefix == RepoPaths.MUSIC }?.folder
        cursor.addRow(arrayOf(config.serverUrl, token, music, config.deviceName))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        val COLUMNS = arrayOf("server_url", "token", "music_folder", "device_name")
    }
}
