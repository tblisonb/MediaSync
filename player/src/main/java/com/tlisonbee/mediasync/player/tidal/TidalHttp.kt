package com.tlisonbee.mediasync.player.tidal

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A reply: its status and body. */
class HttpResult(val status: Int, val body: ByteArray) {
    val ok: Boolean get() = status in 200..299
    val text: String get() = body.decodeToString()
}

/** Blocking HTTP (TIDAL's APIs, Last.fm's); call it off the main thread. */
object TidalHttp {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult = send("GET", url, headers, null)

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap(), service: String = "TIDAL"): HttpResult =
        send("POST", url, headers + ("Content-Type" to "application/x-www-form-urlencoded"), encodeForm(form).toByteArray(), service)

    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, service: String = "TIDAL"): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpResult(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: IOException) {
            throw IOException("Couldn't reach $service: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }

    fun encodeForm(form: Map<String, String>) = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
