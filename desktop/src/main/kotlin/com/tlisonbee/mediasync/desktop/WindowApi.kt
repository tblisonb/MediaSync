package com.tlisonbee.mediasync.desktop

import com.sun.net.httpserver.HttpExchange
import com.tlisonbee.mediasync.sync.ApiException
import com.tlisonbee.mediasync.sync.Decisions
import com.tlisonbee.mediasync.sync.Review
import com.tlisonbee.mediasync.sync.ReviewAction
import com.tlisonbee.mediasync.sync.ReviewSettings
import com.tlisonbee.mediasync.sync.label
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class WindowStatus(
    val device: String,
    val server: String,
    val version: String?,
    val folders: List<FolderStatus>,
    /** What the engine is doing ("Syncing"), and how far it's got. */
    val busy: String?,
    val progress: String?,
    val fraction: Double?,
    /** The last sync's failure, until one works again. */
    val error: String?,
    val lastCheck: String?,
    val lastSync: String?,
    /** What the last job did ("pushed 1 as r240, uploaded 1 (147 KiB)"). */
    val lastOutcome: String?,
    val review: ReviewStatus,
    val settings: SettingsStatus,
    /** Goes up each time something asks for the window while it's open; [show] says what to show. */
    val raise: Int,
    val show: String,
)

@Serializable
data class FolderStatus(val folder: String, val prefix: String, val mode: String, val files: Int, val waiting: Int, val present: Boolean, val leftAlone: Int)

@Serializable
data class ReviewStatus(val version: String, val summary: String, val empty: Boolean, val warnings: List<String>, val groups: List<GroupStatus>)

@Serializable
data class GroupStatus(
    val key: String,
    val version: String,
    val incoming: Boolean,
    val title: String,
    val subtitle: String?,
    /** For "Revert r<rev> on the server"; null where there's nothing to revert. */
    val rev: Long?,
    val count: Int,
    val conflicts: Int,
    /** The first [WindowApi.MAX_ITEMS]; [count] says how many there are. */
    val items: List<ItemStatus>,
)

@Serializable
data class ItemStatus(val path: String, val detail: String, val conflict: Boolean)

@Serializable
data class SettingsStatus(val ask: Boolean, val minutes: Int, val choices: List<Choice>)

@Serializable
data class Choice(val minutes: Int, val label: String)

/** For the Library tab: a one-time login, or why there isn't one. */
@Serializable
data class LibraryLogin(val server: String, val ticket: String? = null, val needsPassword: Boolean = false, val error: String? = null)

@Serializable
data class Reply(val ok: Boolean, val message: String? = null)

/**
 * The desktop window's API, under the review page's secret (`api/…`): the state it shows, and
 * what its buttons do. Jobs are handed to the service and answered right away; the window polls
 * [WindowStatus] to follow them.
 */
class WindowApi(private val service: Service) {
    /** The review and folder counts read the whole index, so they're kept until the next job (or a minute). */
    private class Snapshot(val jobsDone: Long, val at: Long, val review: ReviewStatus, val folders: List<FolderStatus>)

    private var snapshot: Snapshot? = null

    @Synchronized
    private fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        snapshot?.takeIf { it.jobsDone == service.jobsDone && now - it.at < SNAPSHOT_MS }?.let { return it }
        val jobsDone = service.jobsDone
        val rows = service.rows()
        val folders = service.config.folders.map { folder ->
            val inFolder = rows.filter { it.prefix == folder.prefix }
            FolderStatus(
                folder = folder.folder,
                prefix = folder.prefix,
                mode = folder.mode.label + if (folder.keepsDeleted) ", kept in the library when deleted here" else "",
                files = inFolder.count { !it.missing },
                waiting = inFolder.count { it.changed && it.ignored == null },
                present = Files.isDirectory(folder.root),
                leftAlone = inFolder.count { it.ignored != null },
            )
        }
        return Snapshot(jobsDone, now, reviewStatus(service.review()), folders).also { snapshot = it }
    }

    fun handle(ex: HttpExchange, path: String, post: Boolean) {
        if (!post) return when (path) {
            "status" -> json(ex, WindowStatus.serializer(), status())
            else -> respond(ex, 404, "{}")
        }
        val body = runCatching { Json.parseToJsonElement(ex.requestBody.readNBytes(MAX_BODY).toString(Charsets.UTF_8)).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        fun field(name: String) = body[name]?.jsonPrimitive?.contentOrNull
        when (path) {
            "check" -> reply(ex, service.submit(Job.Check()).isSuccess, "Checking for changes")
            "sync" -> reply(ex, service.submit(Job.Sync(allowDeletes = body["allowDeletes"]?.jsonPrimitive?.booleanOrNull == true)).isSuccess, "Syncing")
            "act" -> decisionsFor(service.review(), field("group").orEmpty(), field("version"), field("action")).fold(
                onSuccess = { reply(ex, it.isEmpty || service.submit(Job.Apply(it)).isSuccess, "Applying your choices") },
                onFailure = { reply(ex, false, it.message) },
            )
            "settings" -> {
                body["ask"]?.jsonPrimitive?.booleanOrNull?.let { service.settings.enabled = it }
                body["minutes"]?.jsonPrimitive?.intOrNull?.let { service.settings.checkMinutes = it }
                reply(ex, true, if (service.settings.enabled) "Saved." else "Saved. MediaSync syncs automatically now, starting with anything that was waiting.")
            }
            "library" -> json(ex, LibraryLogin.serializer(), library())
            "allow" -> json(ex, Reply.serializer(), allow(field("password").orEmpty()))
            else -> respond(ex, 404, "{}")
        }
    }

    fun status(): WindowStatus {
        val snapshot = snapshot()
        val progress = service.progress
        val settings = service.settings
        return WindowStatus(
            device = service.config.deviceName,
            server = service.config.serverUrl,
            version = service.version,
            folders = snapshot.folders,
            busy = service.busy,
            progress = progress?.describe(System.currentTimeMillis()),
            fraction = progress?.fraction,
            error = service.error,
            lastCheck = service.lastCheck()?.let(::time),
            lastSync = service.lastSync()?.let(::time),
            lastOutcome = service.lastOutcome,
            review = snapshot.review,
            settings = SettingsStatus(settings.enabled, settings.checkMinutes, ReviewSettings.CHOICES.map { Choice(it, ReviewSettings.describe(it)) }),
            raise = service.raise,
            show = service.show,
        )
    }

    private fun reviewStatus(review: Review) = ReviewStatus(
        version = review.version,
        summary = review.summary("this PC").replaceFirstChar { it.uppercase() },
        empty = review.isEmpty,
        warnings = review.warnings,
        groups = review.groups.map { group ->
            GroupStatus(
                key = group.key,
                version = group.version,
                incoming = group.incoming,
                title = group.title,
                subtitle = group.subtitle,
                rev = group.rev.takeIf { group.incoming },
                count = group.items.size,
                conflicts = group.items.count { it.conflict },
                items = group.items.take(MAX_ITEMS).map { ItemStatus(it.path, it.detail, it.conflict) },
            )
        },
    )

    private fun library(): LibraryLogin {
        val server = service.config.serverUrl
        return try {
            service.api.requireCompatibleServer()
            val ticket = service.api.webTicket()
            if (ticket == null) LibraryLogin(server, needsPassword = true) else LibraryLogin(server, ticket = ticket)
        } catch (e: ApiException) {
            LibraryLogin(server, error = e.message ?: e.toString())
        } catch (e: IOException) {
            LibraryLogin(server, error = "Couldn't reach the server: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun allow(password: String): Reply = try {
        if (service.api.allowWeb(password)) Reply(true) else Reply(false, "That isn't the web UI's password.")
    } catch (e: ApiException) {
        Reply(false, e.message)
    } catch (e: IOException) {
        Reply(false, "Couldn't reach the server: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun reply(ex: HttpExchange, ok: Boolean, message: String?) = json(ex, Reply.serializer(), Reply(ok, message))

    private fun <T> json(ex: HttpExchange, serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        respond(ex, 200, JSON.encodeToString(serializer, value))

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val MAX_ITEMS = 200
        private const val SNAPSHOT_MS = 60_000L
        private const val MAX_BODY = 16 * 1024
        private val JSON = Json { encodeDefaults = true }
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.getDefault())

        fun time(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}

/**
 * What one of the review's buttons decides: [group] is a group's key, or "all" for Accept all;
 * [version] is the list as it was shown, so a list that changed since does nothing.
 */
fun decisionsFor(review: Review, group: String, version: String?, action: String?): Result<Decisions> {
    val stale = "That list changed since it was shown, so nothing was done. Here it is again."
    if (group == "all") return if (version == review.version) Result.success(review.acceptAll()) else Result.failure(IllegalStateException(stale))
    val found = review.group(group)
    if (found == null || found.version != version) return Result.failure(IllegalStateException(stale))
    val chosen = ReviewAction.entries.firstOrNull { it.name.lowercase() == action } ?: return Result.failure(IllegalArgumentException("Pick an action."))
    return Result.success(review.decide(found, chosen))
}
