package com.tlisonbee.mediasync.player.lastfm

import com.tlisonbee.mediasync.player.tidal.TidalHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.security.MessageDigest

/** An error Last.fm answered with: its code (docs: last.fm/api/errorcodes) and message. */
class LastFmException(val code: Int, message: String) : IOException(message) {
    /** Worth trying again later: Last.fm was down, busy or rate-limiting. */
    val temporary: Boolean get() = code in setOf(8, 11, 16, 29)

    /** The session key no longer works: log in again. */
    val loggedOut: Boolean get() = code == 9
}

/** Last.fm's API (ws.audioscrobbler.com/2.0, JSON). Blocking; call it off the main thread. */
class LastFmApi(val apiKey: String, private val secret: String) {
    /** Calls [method]; signed (needed for auth and anything with a session) when [signed]. */
    fun call(method: String, params: Map<String, String>, signed: Boolean = false, post: Boolean = false): JsonObject {
        val all = params + ("method" to method) + ("api_key" to apiKey)
        val form = (if (signed) all + ("api_sig" to signature(all, secret)) else all) + ("format" to "json")
        val headers = mapOf("User-Agent" to USER_AGENT)
        val reply = if (post) TidalHttp.postForm(API, form, headers, SERVICE)
        else TidalHttp.send("GET", API + "?" + TidalHttp.encodeForm(form), headers, null, SERVICE)
        val doc = runCatching { Json.parseToJsonElement(reply.text).jsonObject }.getOrNull()
            ?: throw IOException("Last.fm answered ${reply.status} without JSON")
        (doc["error"] as? JsonPrimitive)?.intOrNull?.let { code -> throw LastFmException(code, doc.string("message") ?: "Last.fm error $code") }
        if (!reply.ok) throw IOException("Last.fm answered ${reply.status}")
        return doc
    }

    companion object {
        const val API = "https://ws.audioscrobbler.com/2.0/"
        const val SERVICE = "Last.fm"
        const val USER_AGENT = "Segue (Android music player)"

        /** Where you approve a token for this app. */
        fun authorizeUrl(apiKey: String, token: String) = "https://www.last.fm/api/auth/?api_key=${TidalHttp.enc(apiKey)}&token=${TidalHttp.enc(token)}"

        /** Last.fm's api_sig: the parameters sorted by name, names and values run together, the secret after, MD5'd. */
        fun signature(params: Map<String, String>, secret: String): String {
            val text = params.filterKeys { it != "format" && it != "callback" }.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
            return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun JsonObject.string(key: String): String? = when (val v = this[key]) {
    is JsonPrimitive -> v.contentOrNull
    // Last.fm's XML-shaped JSON: {"#text": "…", "mbid": "…"}.
    is JsonObject -> (v["#text"] as? JsonPrimitive)?.contentOrNull
    else -> null
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** A list that Last.fm sends as an object when it has one entry. */
internal fun JsonElement?.list(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(this)
    else -> emptyList()
}
