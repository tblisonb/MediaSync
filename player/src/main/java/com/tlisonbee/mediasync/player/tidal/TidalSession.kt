package com.tlisonbee.mediasync.player.tidal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException

/**
 * One TIDAL login, kept in a file only this app can read (as the fooyin plugin keeps
 * `account.json` and `session.json`): the client it was made with, its tokens, the account's
 * country. Hands out a fresh access token, refreshing it a minute before it runs out.
 */
class TidalSession(private val file: File, private val refreshScope: String?) {
    private var clientId = ""
    private var clientSecret = ""
    private var accessToken = ""
    private var refreshToken = ""
    private var expiresAt = 0L
    private var country = ""
    private var userId = ""

    init {
        load()
    }

    @get:Synchronized
    val isLoggedIn: Boolean get() = clientId.isNotEmpty() && refreshToken.isNotEmpty()

    @get:Synchronized
    val countryCode: String get() = country.ifEmpty { "US" }

    @get:Synchronized
    val user: String get() = userId

    /** The client a login was made with, to offer it again. */
    @get:Synchronized
    val client: Pair<String, String> get() = clientId to clientSecret

    @Synchronized
    fun setLogin(clientId: String, clientSecret: String, token: JsonObject, country: String, userId: String?) {
        this.clientId = clientId
        this.clientSecret = clientSecret
        this.country = country
        this.refreshToken = ""
        this.userId = userId ?: ""
        store(token)
        save()
    }

    @Synchronized
    fun logOut() {
        accessToken = ""
        refreshToken = ""
        userId = ""
        expiresAt = 0
        save()
    }

    private fun store(token: JsonObject) {
        accessToken = token.string("access_token") ?: ""
        expiresAt = System.currentTimeMillis() / 1000 + (token["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600)
        token.string("refresh_token")?.takeIf { it.isNotEmpty() }?.let { refreshToken = it }
        token["user_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && userId.isEmpty() }?.let { userId = it }
    }

    /** A valid access token; refreshes it when needed. Blocking. */
    @Synchronized
    fun accessToken(): String {
        if (!isLoggedIn) throw IOException("Not logged in to TIDAL")
        val now = System.currentTimeMillis() / 1000
        if (accessToken.isNotEmpty() && now < expiresAt - 60) return accessToken
        val form = buildMap {
            put("grant_type", "refresh_token")
            put("client_id", clientId)
            put("refresh_token", refreshToken)
            refreshScope?.let { put("scope", it) }
            if (clientSecret.isNotEmpty()) put("client_secret", clientSecret)
        }
        val result = TidalHttp.postForm(TidalLogin.TOKEN_URL, form)
        if (!result.ok) throw IOException("Couldn't refresh the TIDAL login (HTTP ${result.status})")
        store(Json.parseToJsonElement(result.text).jsonObject)
        if (accessToken.isEmpty()) throw IOException("TIDAL's token reply had no access token")
        save()
        return accessToken
    }

    private fun load() {
        val json = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        clientId = json.string("client_id") ?: ""
        clientSecret = json.string("client_secret") ?: ""
        accessToken = json.string("access_token") ?: ""
        refreshToken = json.string("refresh_token") ?: ""
        country = json.string("country") ?: ""
        userId = json.string("user_id") ?: ""
        expiresAt = json["expires_at"]?.jsonPrimitive?.longOrNull ?: 0
    }

    private fun save() {
        val json = buildJsonObject {
            put("client_id", JsonPrimitive(clientId))
            put("client_secret", JsonPrimitive(clientSecret))
            put("access_token", JsonPrimitive(accessToken))
            put("refresh_token", JsonPrimitive(refreshToken))
            put("country", JsonPrimitive(country))
            put("user_id", JsonPrimitive(userId))
            put("expires_at", JsonPrimitive(expiresAt))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
