package com.tlisonbee.mediasync.player

import android.content.Context
import com.tlisonbee.mediasync.player.audio.ReplayGain
import com.tlisonbee.mediasync.player.library.Gains
import com.tlisonbee.mediasync.player.tidal.TidalSort
import com.tlisonbee.mediasync.player.ui.Accent
import com.tlisonbee.mediasync.player.ui.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Volume levelling from the files' ReplayGain tags. */
enum class GainMode(val label: String, val description: String) {
    OFF("Off", "Play tracks as they are."),
    TRACK("Track", "Every track at the same loudness. Best for shuffling."),
    ALBUM("Album", "Albums keep their quiet and loud tracks; tracks without album values use their own."),
    ;

    /** The factor a track plays at, never more than its peak allows without clipping. */
    fun factor(gains: Gains): Float {
        val track = gains.trackGain?.let { ReplayGain.factor(it, peak = gains.trackPeak) }
        return when (this) {
            OFF -> 1f
            TRACK -> track ?: 1f
            ALBUM -> gains.albumGain?.let { ReplayGain.factor(it, peak = gains.albumPeak) } ?: track ?: 1f
        }
    }
}

/** The player's settings, in its shared preferences. */
class PlayerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _gainMode = MutableStateFlow(prefs.getString("gain_mode", null)?.let { runCatching { GainMode.valueOf(it) }.getOrNull() } ?: GainMode.TRACK)
    val gainMode: StateFlow<GainMode> = _gainMode.asStateFlow()

    private val _showRemote = MutableStateFlow(prefs.getBoolean("show_remote", true))

    /** Whether the server's tracks the phone doesn't hold are in the library. */
    val showRemote: StateFlow<Boolean> = _showRemote.asStateFlow()

    fun setShowRemote(show: Boolean) {
        prefs.edit().putBoolean("show_remote", show).apply()
        _showRemote.value = show
    }

    private val _tidalSort = MutableStateFlow(
        TidalSort(
            prefs.getString("tidal_sort", null)?.let { runCatching { TidalSort.By.valueOf(it) }.getOrNull() } ?: TidalSort.By.LIST,
            prefs.getBoolean("tidal_sort_reversed", false),
        ),
    )

    /** How TIDAL's playlists and favorite tracks are sorted: one choice for all of them. */
    val tidalSort: StateFlow<TidalSort> = _tidalSort.asStateFlow()

    fun setTidalSort(sort: TidalSort) {
        prefs.edit().putString("tidal_sort", sort.by.name).putBoolean("tidal_sort_reversed", sort.reversed).apply()
        _tidalSort.value = sort
    }

    private val _theme = MutableStateFlow(prefs.getString("theme", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        _theme.value = mode
    }

    private val _accent = MutableStateFlow(prefs.getString("accent", null)?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: Accent.WALLPAPER)
    val accent: StateFlow<Accent> = _accent.asStateFlow()

    fun setAccent(accent: Accent) {
        prefs.edit().putString("accent", accent.name).apply()
        _accent.value = accent
    }

    fun setGainMode(mode: GainMode) {
        prefs.edit().putString("gain_mode", mode.name).apply()
        _gainMode.value = mode
    }
}
