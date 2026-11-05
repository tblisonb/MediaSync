package com.tlisonbee.mediasync.player.lastfm

/**
 * Follows the current track's playing time, to scrobble it as Last.fm asks: once it has played
 * (seeking doesn't count) for half its length or four minutes, whichever is first, if it's at least
 * 30 seconds long; stamped with when it started. Also says when to tell Last.fm it's playing now.
 * The clocks are passed in, for tests: [elapsedMs] is monotonic, [wallSec] is the time of day.
 */
class PlayTracker(private val elapsedMs: () -> Long, private val wallSec: () -> Long) {
    private var id: String? = null
    private var info: Scrobble? = null
    private var durationMs = 0L
    private var playedMs = 0L
    private var playingSince: Long? = null
    private var startedAt: Long? = null
    private var scrobbled = false
    private var announced = false

    val currentId: String? get() = id

    /** A new track (or the same one again, repeated): [info] has no timestamp yet; null for none. */
    fun start(id: String?, info: Scrobble?, durationMs: Long) {
        this.id = id
        this.info = info
        this.durationMs = durationMs
        playedMs = 0
        playingSince = null
        startedAt = null
        scrobbled = false
        announced = false
    }

    /** The player may learn the length only once the track is prepared. */
    fun duration(ms: Long) {
        if (ms > 0) durationMs = ms
    }

    fun playing(isPlaying: Boolean) {
        val now = elapsedMs()
        val since = playingSince
        if (isPlaying && since == null) {
            playingSince = now
            if (startedAt == null) startedAt = wallSec()
        } else if (!isPlaying && since != null) {
            playedMs += now - since
            playingSince = null
        }
    }

    fun playedMs(): Long = playedMs + (playingSince?.let { elapsedMs() - it } ?: 0)

    private fun eligible() = info != null && durationMs >= LastFm.MIN_DURATION_MS

    /** Once per track, when it first plays: what to send as "now playing". */
    fun announce(): Scrobble? {
        if (announced || playingSince == null || info == null) return null
        announced = true
        return info
    }

    /** The scrobble, once it's due (and only once). */
    fun due(): Scrobble? {
        if (scrobbled || !eligible() || playedMs() < LastFm.thresholdMs(durationMs)) return null
        scrobbled = true
        return info!!.copy(timestamp = startedAt ?: wallSec(), durationSec = (durationMs / 1000).toInt())
    }

    /** How long until it's due, while it's playing; null if it won't come due by itself. */
    fun msUntilDue(): Long? {
        if (scrobbled || !eligible() || playingSince == null) return null
        return (LastFm.thresholdMs(durationMs) - playedMs()).coerceAtLeast(0)
    }
}
