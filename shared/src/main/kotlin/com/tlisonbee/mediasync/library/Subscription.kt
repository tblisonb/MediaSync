package com.tlisonbee.mediasync.library

import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.Limits
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SyncMode {
    @SerialName("two-way") TWO_WAY,
    @SerialName("receive-only") RECEIVE_ONLY,
    @SerialName("send-only") SEND_ONLY,
}

/**
 * One rule of a device's subscription: which part of the repository lives in which local folder.
 * [filter] terms all have to hold: `path:<glob>`, `kind:<kind>`, each optionally prefixed `not `.
 * Globs match the repository path without extension; `*` stays within a folder, `**` doesn't.
 */
@Serializable
data class SubscriptionRule(
    val prefix: String,
    val localFolder: String,
    val mode: SyncMode = SyncMode.TWO_WAY,
    val filter: List<String> = emptyList(),
    /** Formats to fetch, best first, when the device has no rendition of an asset yet. */
    val prefer: List<String> = emptyList(),
    /**
     * Only what is picked for this device, rather than everything under [prefix]: for videos too
     * big to keep everywhere. A video's subtitles and posters come with it.
     */
    val pick: Boolean = false,
) {
    fun covers(path: String, kind: AssetKind): Boolean =
        path.startsWith(prefix) && compiled.all { it.matches(path, kind) }

    private val compiled by lazy { filter.map(Term::parse) }

    fun validate() {
        require(prefix.isEmpty() || (prefix.endsWith('/') && RepoPaths.clean(prefix.trimEnd('/')) == prefix.trimEnd('/'))) {
            "prefix must be a clean repository folder ending in /: $prefix"
        }
        require(localFolder.isNotBlank()) { "localFolder is required" }
        filter.forEach(Term::parse)
    }

    private class Term(val negated: Boolean, val test: (String, AssetKind) -> Boolean) {
        fun matches(path: String, kind: AssetKind) = test(path, kind) != negated

        companion object {
            fun parse(text: String): Term {
                val negated = text.startsWith("not ")
                val body = text.removePrefix("not ").trim()
                val key = body.substringBefore(':')
                val value = body.substringAfter(':', "")
                require(value.isNotEmpty()) { "filter term needs a value: $text" }
                return when (key) {
                    "path" -> glob(value).let { regex -> Term(negated) { path, _ -> regex.matches(path) } }
                    "kind" -> {
                        val kind = AssetKind.entries.firstOrNull { it.apiName == value }
                            ?: throw IllegalArgumentException("unknown kind in filter: $value")
                        Term(negated) { _, k -> k == kind }
                    }
                    else -> throw IllegalArgumentException("unknown filter term (path: and kind: are supported): $text")
                }
            }

            fun glob(pattern: String): Regex {
                val out = StringBuilder()
                var i = 0
                while (i < pattern.length) {
                    val c = pattern[i]
                    when {
                        pattern.startsWith("**", i) -> { out.append(".*"); i++ }
                        c == '*' -> out.append("[^/]*")
                        c == '?' -> out.append("[^/]")
                        else -> out.append(Regex.escape(c.toString()))
                    }
                    i++
                }
                return Regex(out.toString())
            }
        }
    }
}

@Serializable
data class Subscription(val rules: List<SubscriptionRule> = emptyList()) {
    /** Whether the device should hold this asset. */
    fun receives(path: String, kind: AssetKind) =
        rules.any { it.mode != SyncMode.SEND_ONLY && it.covers(path, kind) }

    /** Whether the device may push changes to this asset. */
    fun sends(path: String, kind: AssetKind) =
        rules.any { it.mode != SyncMode.RECEIVE_ONLY && it.covers(path, kind) }

    fun validate() {
        require(rules.size <= Limits.MAX_RULES) { "too many rules" }
        rules.forEach(SubscriptionRule::validate)
    }
}
