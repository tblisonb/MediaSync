package com.tlisonbee.mediasync.player.tidal

import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * TIDAL's two logins, as the fooyin plugin makes them (see its docs/design.md):
 *
 * - **Account**, for search, favorites and playlists (the official v2 API), with your own
 *   developer app; its redirect is `http://localhost:47130/callback`, which the app catches in its
 *   login page before anything tries to open it.
 * - **Playback**, for full tracks (the private v1 API), with a client from one of TIDAL's own apps;
 *   it ends on TIDAL's "Oops" page at `https://tidal.com/android/login/auth?code=…`.
 *
 * Both are PKCE: no secret leaves the phone but the playback client's, which TIDAL's own app sends too.
 */
object TidalLogin {
    const val AUTHORIZE_URL = "https://login.tidal.com/authorize"
    const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
    const val ACCOUNT_REDIRECT = "http://localhost:47130/callback"
    const val PLAYBACK_REDIRECT = "https://tidal.com/android/login/auth"
    const val ACCOUNT_SCOPES = "user.read collection.read collection.write playlists.read playlists.write search.read playback"
    const val PLAYBACK_SCOPES = "r_usr w_usr w_sub"

    /** One login in progress. */
    class Request(
        val clientId: String,
        val clientSecret: String,
        val redirectUri: String,
        val verifier: String,
        val state: String?,
        val uniqueKey: String?,
        val authorizeUrl: String,
    )

    private val random = SecureRandom()

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun challenge(verifier: String) = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun url(query: Map<String, String>) = AUTHORIZE_URL + "?" + TidalHttp.encodeForm(query)

    fun accountRequest(clientId: String): Request {
        val verifier = base64Url(randomBytes(64))
        val state = base64Url(randomBytes(16))
        val id = clientId.trim()
        return Request(
            id, "", ACCOUNT_REDIRECT, verifier, state, null,
            url(
                linkedMapOf(
                    "response_type" to "code", "client_id" to id, "redirect_uri" to ACCOUNT_REDIRECT, "scope" to ACCOUNT_SCOPES,
                    "code_challenge_method" to "S256", "code_challenge" to challenge(verifier), "state" to state,
                ),
            ),
        )
    }

    fun playbackRequest(clientId: String, clientSecret: String): Request {
        val verifier = base64Url(randomBytes(64))
        val uniqueKey = randomBytes(8).joinToString("") { "%02x".format(it) }
        val id = clientId.trim()
        return Request(
            id, clientSecret.trim(), PLAYBACK_REDIRECT, verifier, null, uniqueKey,
            url(
                linkedMapOf(
                    "response_type" to "code", "redirect_uri" to PLAYBACK_REDIRECT, "client_id" to id, "lang" to "EN", "appMode" to "android",
                    "client_unique_key" to uniqueKey, "code_challenge" to challenge(verifier), "code_challenge_method" to "S256",
                    "restrict_signup" to "true",
                ),
            ),
        )
    }

    /** Whether [url] is where [request]'s login ends. */
    fun isRedirect(request: Request, url: String) = url.startsWith(request.redirectUri)

    /** The code in the address a login ended on. */
    fun codeFrom(request: Request, redirect: String): String {
        val uri = Uri.parse(redirect.trim())
        uri.getQueryParameter("error")?.let { throw IOException("TIDAL refused the login: $it ${uri.getQueryParameter("error_description").orEmpty()}".trim()) }
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotEmpty() } ?: throw IOException("There's no login code in that address")
        if (request.state != null && uri.getQueryParameter("state") != request.state) throw IOException("That address belongs to another login")
        return code
    }

    private fun exchange(request: Request, code: String, scope: String?): JsonObject {
        val form = buildMap {
            put("grant_type", "authorization_code")
            put("client_id", request.clientId)
            put("code", code)
            put("redirect_uri", request.redirectUri)
            put("code_verifier", request.verifier)
            scope?.let { put("scope", it) }
            request.uniqueKey?.let { put("client_unique_key", it) }
        }
        val result = TidalHttp.postForm(TOKEN_URL, form)
        if (!result.ok) throw IOException("TIDAL didn't accept the login (HTTP ${result.status})")
        val token = Json.parseToJsonElement(result.text).jsonObject
        if (token.string("access_token").isNullOrEmpty()) throw IOException("TIDAL's token reply had no access token")
        return token
    }

    /** Finishes the account login: tokens, then the account's country and user ID. Blocking. */
    fun completeAccount(request: Request, redirect: String, session: TidalSession) {
        val token = exchange(request, codeFrom(request, redirect), null)
        val me = TidalHttp.get(
            "https://openapi.tidal.com/v2/users/me",
            mapOf("Authorization" to "Bearer ${token.string("access_token")}", "Accept" to "application/vnd.api+json"),
        )
        if (!me.ok) throw IOException("Couldn't read the account's details (HTTP ${me.status})")
        val data = Json.parseToJsonElement(me.text).jsonObject["data"]?.jsonObject
        val country = (data?.get("attributes") as? JsonObject)?.string("country") ?: ""
        session.setLogin(request.clientId, "", token, country, data?.string("id"))
    }

    /** Finishes the playback login: tokens, then the login's country. Blocking. */
    fun completePlayback(request: Request, redirect: String, session: TidalSession) {
        val token = exchange(request, codeFrom(request, redirect), PLAYBACK_SCOPES)
        val info = TidalHttp.get("https://api.tidal.com/v1/sessions", mapOf("Authorization" to "Bearer ${token.string("access_token")}"))
        if (!info.ok) throw IOException("Couldn't read the login's details (HTTP ${info.status})")
        val country = (Json.parseToJsonElement(info.text) as JsonObject).string("countryCode") ?: ""
        session.setLogin(request.clientId, request.clientSecret, token, country, (token["user"] as? JsonObject)?.get("userId")?.let { (it as? JsonPrimitive)?.content })
    }
}
