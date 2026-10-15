package com.tlisonbee.mediasync.data

import android.content.Context
import androidx.core.content.edit
import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.sync.ClientConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** How the last sync went, for the status screen. */
data class LastSync(val at: Long?, val summary: String?, val error: String?, val warnings: List<String>)

/**
 * The pairing (server, device, token, folders) and whether the first sync was approved. The prefs
 * file is excluded from backups (res/xml/data_extraction_rules.xml), so the token stays on the phone.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)

    /** Ticks whenever something here changes, so the UI can re-read. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    val config: ClientConfig?
        get() = prefs.getString(KEY_CONFIG, null)?.let { ApiJson.decodeFromString(ClientConfig.serializer(), it) }

    val token: String? get() = prefs.getString(KEY_TOKEN, null)

    /** The person has seen the preview and said to go ahead. */
    val approved: Boolean get() = prefs.getBoolean(KEY_APPROVED, false)

    val ready: Boolean get() = config != null && token != null && approved

    val lastSync: LastSync
        get() = LastSync(
            at = prefs.getLong(KEY_LAST_AT, 0).takeIf { it > 0 },
            summary = prefs.getString(KEY_LAST_SUMMARY, null),
            error = prefs.getString(KEY_LAST_ERROR, null),
            warnings = prefs.getString(KEY_WARNINGS, null)?.let { ApiJson.decodeFromString(strings, it) }.orEmpty(),
        )

    /** Issue ids already announced in a notification. */
    var notifiedIssues: Set<Long>
        get() = prefs.getStringSet(KEY_NOTIFIED, emptySet())!!.map { it.toLong() }.toSet()
        set(value) = prefs.edit { putStringSet(KEY_NOTIFIED, value.map { it.toString() }.toSet()) }

    fun pair(config: ClientConfig, token: String) = update {
        clear()
        putString(KEY_CONFIG, ApiJson.encodeToString(ClientConfig.serializer(), config))
        putString(KEY_TOKEN, token)
    }

    fun approve() = update { putBoolean(KEY_APPROVED, true) }

    fun unpair() = update { clear() }

    fun recordSuccess(summary: String, warnings: List<String>) = update {
        putLong(KEY_LAST_AT, System.currentTimeMillis())
        putString(KEY_LAST_SUMMARY, summary)
        putString(KEY_WARNINGS, ApiJson.encodeToString(strings, warnings))
        remove(KEY_LAST_ERROR)
    }

    fun recordError(message: String) = update { putString(KEY_LAST_ERROR, message) }

    private fun update(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = true, action = block)
        _changes.value++
    }

    private companion object {
        const val KEY_CONFIG = "config"
        const val KEY_TOKEN = "token"
        const val KEY_APPROVED = "approved"
        const val KEY_LAST_AT = "lastAt"
        const val KEY_LAST_SUMMARY = "lastSummary"
        const val KEY_LAST_ERROR = "lastError"
        const val KEY_WARNINGS = "warnings"
        const val KEY_NOTIFIED = "notifiedIssues"
        val strings = ListSerializer(String.serializer())
    }
}
