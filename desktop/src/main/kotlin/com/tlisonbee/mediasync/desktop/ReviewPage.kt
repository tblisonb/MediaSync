package com.tlisonbee.mediasync.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.tlisonbee.mediasync.sync.Decisions
import com.tlisonbee.mediasync.sync.ReviewAction
import com.tlisonbee.mediasync.sync.ReviewGroup
import com.tlisonbee.mediasync.sync.ReviewSettings
import com.tlisonbee.mediasync.sync.SyncStage
import com.tlisonbee.mediasync.sync.bytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * The review page: what waits for approval, and the buttons to decide. Served on loopback at a
 * random port, with a random secret as the first part of every URL (the notification and
 * `mediasync-desktop review` open it), and only to requests addressed to 127.0.0.1. Plain HTML
 * forms, no scripts.
 */
class ReviewPage(private val service: Service) {
    private val secret = ByteArray(24).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16)
    private val port get() = server.address.port
    val url get() = "http://127.0.0.1:$port/$secret/"

    fun start() {
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (e: Exception) {
                respond(exchange, 500, "Something went wrong: ${e.message}")
            } finally {
                exchange.close()
            }
        }
        server.executor = Executors.newFixedThreadPool(2) { Thread(it, "review-page").apply { isDaemon = true } }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        // A web page elsewhere can't learn the secret, and a rebound DNS name doesn't match this.
        val host = ex.requestHeaders.getFirst("Host")
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return respond(ex, 421, "Open this page at 127.0.0.1.")
        val path = ex.requestURI.rawPath
        if (!path.startsWith("/$secret/")) return respond(ex, 404, "Not found. Open the review page with: mediasync-desktop review")
        val post = ex.requestMethod == "POST"
        when (path.removePrefix("/$secret/")) {
            "" -> if (post) respond(ex, 405, "") else page(ex)
            "act" -> if (post) act(ex, form(ex)) else back(ex)
            "check" -> if (post) run(ex, Job.Check(CompletableDeferred())) else back(ex)
            "settings" -> if (post) settings(ex, form(ex)) else back(ex)
            else -> respond(ex, 404, "Not found")
        }
    }

    private fun act(ex: HttpExchange, form: Map<String, String>) {
        val review = service.review()
        val stale = "That list changed since the page was shown, so nothing was done. Here it is again."
        val decisions: Decisions = if (form["group"] == "all") {
            if (form["version"] != review.version) return back(ex, stale)
            review.acceptAll()
        } else {
            val group = review.group(form["group"].orEmpty())
            if (group == null || group.version != form["version"]) return back(ex, stale)
            val action = ReviewAction.entries.firstOrNull { it.name.lowercase() == form["action"] } ?: return back(ex, "Pick an action.")
            review.decide(group, action)
        }
        if (decisions.isEmpty) return back(ex)
        run(ex, Job.Apply(decisions, CompletableDeferred()))
    }

    /** Hands [job] to the service; waits a little so quick ones show their outcome right away. */
    private fun run(ex: HttpExchange, job: Job) {
        service.submit(job)
        val outcome = runBlocking { withTimeoutOrNull(20.seconds) { job.reply!!.await() } }
        back(ex, outcome ?: "Still working on it. This page refreshes until it's done.")
    }

    private fun settings(ex: HttpExchange, form: Map<String, String>) {
        service.settings.enabled = form["enabled"] == "on"
        form["minutes"]?.toIntOrNull()?.let { service.settings.checkMinutes = it }
        back(ex, if (service.settings.enabled) "Saved." else "Saved. MediaSync syncs automatically now, starting with anything that was waiting.")
    }

    private fun back(ex: HttpExchange, message: String? = null) {
        ex.responseHeaders.add("Location", "/$secret/" + (message?.let { "?msg=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""))
        ex.sendResponseHeaders(303, -1)
    }

    private fun page(ex: HttpExchange) {
        val review = service.review()
        val busy = service.busy
        val settings = service.settings
        val message = ex.requestURI.rawQuery?.split('&')?.firstOrNull { it.startsWith("msg=") }?.let { URLDecoder.decode(it.removePrefix("msg="), Charsets.UTF_8) }
        val html = buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>Review changes · MediaSync</title>")
            if (busy != null) append("<meta http-equiv=\"refresh\" content=\"5\">")
            append("<style>").append(STYLE).append("</style></head><body><main>")
            append("<h1>Review changes</h1><p class=\"muted\">").append(esc(service.config.deviceName))
            append(" · last checked ").append(service.lastCheck()?.let(::time) ?: "never")
            append(" · <a href=\"").append(esc(service.config.serverUrl)).append("\">web UI</a></p>")
            if (message != null) append("<p class=\"notice\">").append(esc(message)).append("</p>")
            if (busy != null) progress(this, busy, service.progress)
            review.warnings.forEach { append("<p class=\"notice warn\">").append(esc(it)).append("</p>") }

            append("<div class=\"actions\"><form method=\"post\" action=\"check\"><button>Check now</button></form>")
            if (!review.isEmpty) {
                append("<form method=\"post\" action=\"act\"><input type=\"hidden\" name=\"group\" value=\"all\">")
                append("<input type=\"hidden\" name=\"version\" value=\"").append(review.version).append("\">")
                append("<button class=\"primary\">Accept all</button></form>")
            }
            append("</div>")

            if (review.isEmpty) {
                append("<p class=\"empty\">Nothing waits for approval.")
                if (settings.enabled) append(" MediaSync checks ").append(ReviewSettings.describe(settings.checkMinutes)).append(".")
                append("</p>")
            }
            if (review.incoming.isNotEmpty()) {
                append("<h2>From the server</h2><p class=\"muted\">Accept writes a change into your files. To leave it for later, do nothing; ")
                append("it stays here. Revert undoes it on the server, for every device.</p>")
                review.incoming.forEach { group(this, it) }
            }
            if (review.outgoing.isNotEmpty()) {
                append("<h2>Made on this PC</h2><p class=\"muted\">Accept sends a change to the server. Undo here puts the files back as the ")
                append("server has them, and what changed here is lost; an undone new file stays, but isn't synced.</p>")
                review.outgoing.forEach { group(this, it) }
            }

            append("<h2>Settings</h2><form method=\"post\" action=\"settings\" class=\"card\">")
            append("<label class=\"check\"><input type=\"checkbox\" name=\"enabled\"").append(if (settings.enabled) " checked" else "")
            append("> Ask before syncing</label>")
            append("<label>Check for changes <select name=\"minutes\">")
            ReviewSettings.CHOICES.forEach { m ->
                append("<option value=\"").append(m).append("\"").append(if (m == settings.checkMinutes) " selected" else "").append(">")
                append(ReviewSettings.describe(m)).append("</option>")
            }
            append("</select></label><p class=\"muted\">Without asking, MediaSync syncs as soon as anything changes, here or on the server.</p>")
            append("<button>Save</button></form>")
            append("</main></body></html>")
        }
        ex.responseHeaders.add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        respond(ex, 200, html, "text/html")
    }

    /** The running job: what it's doing, a bar, files and bytes, and a rough time left. */
    private fun progress(out: StringBuilder, busy: String, p: JobProgress?) = with(out) {
        append("<div class=\"notice\"><p>").append(esc(busy)).append("… This page refreshes by itself.</p>")
        if (p != null) {
            val what = when (p.stage) {
                SyncStage.SCANNING -> "Reading files"
                SyncStage.PULLING -> "Asking the server"
                SyncStage.UPLOADING -> "Uploading"
                SyncStage.DOWNLOADING -> "Downloading"
            }
            val parts = listOfNotNull(
                "$what:",
                if (p.total > 0) "${"%,d".format(p.done)} of ${"%,d".format(p.total)} files" else null,
                if (p.totalBytes > 0) "· ${bytes(p.bytes)} of ${bytes(p.totalBytes)}" else if (p.bytes > 0) "· ${bytes(p.bytes)}" else null,
                p.fraction?.let { "(${(it * 100).toInt()}%)" },
                p.remainingMs(System.currentTimeMillis())?.let { "· about ${duration(it)} left" },
            )
            append("<p>").append(esc(parts.joinToString(" "))).append("</p>")
            p.fraction?.let { append("<progress max=\"1000\" value=\"").append((it * 1000).toInt()).append("\"></progress>") }
        }
        append("</div>")
    }

    private fun group(out: StringBuilder, group: ReviewGroup) = with(out) {
        append("<section class=\"card\"><h3>").append(esc(group.title)).append("</h3><p class=\"muted\">")
        group.subtitle?.let { append(esc(it)).append(" · ") }
        append(group.items.size).append(if (group.items.size == 1) " file" else " files")
        val conflicts = group.items.count { it.conflict }
        if (conflicts > 0) append(" · <span class=\"badge\">").append(conflicts).append(" changed on both sides</span>")
        append("</p><form method=\"post\" action=\"act\" class=\"actions\">")
        append("<input type=\"hidden\" name=\"group\" value=\"").append(esc(group.key)).append("\">")
        append("<input type=\"hidden\" name=\"version\" value=\"").append(group.version).append("\">")
        append("<button name=\"action\" value=\"accept\" class=\"primary\">Accept</button>")
        if (!group.incoming) append("<button name=\"action\" value=\"undo\">Undo here</button>")
        if (group.incoming && group.rev != null) append("<button name=\"action\" value=\"revert\" class=\"danger\">Revert r${group.rev} on the server</button>")
        append("</form><details><summary>Show the files</summary><table>")
        group.items.take(MAX_ROWS).forEach { item ->
            append("<tr><td>").append(esc(item.path)).append("</td><td class=\"muted\">").append(esc(item.detail))
            if (item.conflict) append(" <span class=\"badge\">changed on both sides</span>")
            append("</td></tr>")
        }
        append("</table>")
        if (group.items.size > MAX_ROWS) append("<p class=\"muted\">And ").append(group.items.size - MAX_ROWS).append(" more.</p>")
        append("</details></section>")
    }

    private fun form(ex: HttpExchange): Map<String, String> =
        ex.requestBody.readNBytes(MAX_FORM).toString(Charsets.UTF_8).split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    private fun respond(ex: HttpExchange, status: Int, body: String, type: String = "text/plain") {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val MAX_ROWS = 500
        const val MAX_FORM = 64 * 1024
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun time(millis: Long) = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        fun duration(ms: Long): String {
            val minutes = (ms + 59_999) / 60_000
            return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** The web UI's look, trimmed to what this page uses. */
        val STYLE = """
            :root { color-scheme: light dark; --bg: #f5f7f6; --surface: #fff; --text: #1a1c1c; --muted: #5d6463; --border: #d7dddb;
              --accent: #00796b; --accent-text: #fff; --danger: #b3261e; }
            @media (prefers-color-scheme: dark) { :root { --bg: #111414; --surface: #1c2020; --text: #e2e6e5; --muted: #a0a9a7;
              --border: #333a39; --accent: #80cbc4; --accent-text: #00201c; --danger: #ffb4ab; } }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--text); font: 16px/1.45 system-ui, sans-serif; }
            main { max-width: 960px; margin: 0 auto; padding: 16px 16px 48px; }
            h1 { font-size: 1.5rem; margin-bottom: 4px; } h2 { font-size: 1.15rem; margin-top: 28px; } h3 { font-size: 1rem; margin: 0 0 4px; overflow-wrap: anywhere; }
            a { color: var(--accent); } .muted { color: var(--muted); } .empty { color: var(--muted); }
            .notice { background: var(--surface); border-left: 4px solid var(--accent); padding: 10px 14px; border-radius: 4px; }
            .notice.warn { border-left-color: var(--danger); }
            .card { background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 16px 20px; margin: 12px 0; }
            .card p { margin: 0; }
            .actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; } form { margin: 0; }
            button, select { font: inherit; color: inherit; padding: 8px 16px; border: 1px solid var(--border); border-radius: 999px; background: var(--surface); cursor: pointer; }
            select { border-radius: 8px; margin-left: 8px; }
            button.primary { background: var(--accent); border-color: var(--accent); color: var(--accent-text); }
            button.danger { color: var(--danger); border-color: var(--danger); }
            button:focus-visible, select:focus, a:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
            label { display: block; margin: 0 0 12px; } label.check input { margin-right: 8px; }
            .badge { padding: 1px 8px; border-radius: 999px; background: var(--danger); color: var(--bg); font-size: 0.75rem; white-space: nowrap; }
            summary { cursor: pointer; color: var(--accent); margin-top: 4px; }
            .notice p { margin: 0 0 6px; } progress { width: 100%; height: 12px; accent-color: var(--accent); }
            table { border-collapse: collapse; width: 100%; margin-top: 8px; font-size: 0.9rem; }
            td { padding: 4px 10px 4px 0; border-bottom: 1px solid var(--border); vertical-align: top; overflow-wrap: anywhere; }
        """.trimIndent()
    }
}
