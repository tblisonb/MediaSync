package com.tlisonbee.mediasync.sync

/**
 * Which app is talking to the server, and the oldest server it works with. The Android app and
 * the desktop client set this once at startup. Every request names the app in its User-Agent
 * (the server shows it on the Devices page), and each sync first checks that the server is new
 * enough ([ApiClient.requireCompatibleServer]).
 *
 * The rule that makes this work: a server only adds to the API, so older apps keep working with
 * a newer server. Update the server first, then the apps.
 */
object ClientInfo {
    /** "android" or "desktop"; null leaves requests unmarked (tests, tools). */
    @Volatile var kind: String? = null
    @Volatile var version: String? = null

    /** The oldest server version this app works with; null checks nothing. */
    @Volatile var minServer: String? = null

    val userAgent: String? get() = kind?.let { "MediaSync-$it/${version ?: "dev"}" }

    /** What to call this app in a message. */
    val name: String get() = when (kind) {
        "android" -> "This app"
        "desktop" -> "This desktop client"
        else -> "This client"
    }
}

/**
 * Compares dotted versions part by part as numbers, so 1.10.0 is newer than 1.9.2. A missing part
 * counts as 0, and anything after a '-' or '+' (1.4.0-dev) is ignored.
 */
fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
    val x = parts(a)
    val y = parts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}
