package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.ApiJson
import com.tlisonbee.mediasync.api.AssetKind
import com.tlisonbee.mediasync.api.AssetDto
import com.tlisonbee.mediasync.api.ChangesResponse
import com.tlisonbee.mediasync.api.DeviceDto
import com.tlisonbee.mediasync.api.DeviceKind
import com.tlisonbee.mediasync.api.Limits
import com.tlisonbee.mediasync.api.PairRequest
import com.tlisonbee.mediasync.api.PairResponse
import com.tlisonbee.mediasync.library.Subscription
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.ResultSet
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val random = SecureRandom()

/** Pairing codes avoid letters that read alike (0/O, 1/I/L). */
private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
val PAIRING_LIFETIME = 10.minutes

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** A one-time code for pairing a device, valid for [PAIRING_LIFETIME]. Shown as "ABCD-EFGH". */
fun Repository.newPairingCode(): String {
    val code = (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    db.tx {
        db.exec("DELETE FROM pairing_code WHERE expires_at < ?", clock())
        db.exec("INSERT INTO pairing_code (code_hash, expires_at) VALUES (?, ?)", sha256Hex(code), clock() + PAIRING_LIFETIME.inWholeMilliseconds)
    }
    return code.chunked(4).joinToString("-")
}

/** Swaps a pairing code for a new device and its bearer token; the token itself is never stored. */
fun Repository.pair(request: PairRequest): PairResponse {
    val name = request.name.trim()
    if (name.isEmpty() || name.length > Limits.MAX_DEVICE_NAME) badRequest("name must be 1 to ${Limits.MAX_DEVICE_NAME} characters")
    if (request.kind == DeviceKind.WEB) badRequest("kind must be desktop or android")
    val code = request.code.uppercase().filter { it.isLetterOrDigit() }
    val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val id = UUID.randomUUID().toString()
    db.tx {
        val used = db.exec("DELETE FROM pairing_code WHERE code_hash = ? AND expires_at >= ?", sha256Hex(code), clock())
        if (used == 0) throw RequestException("that pairing code is wrong or has expired", 401)
        insertDevice(id, request.kind, name, sha256Hex(token))
    }
    return PairResponse(id, token, db.read { head() })
}

fun Repository.insertDevice(id: String, kind: DeviceKind, name: String, tokenHash: String?) {
    db.exec(
        "INSERT INTO device (id, kind, name, token_hash, subscription, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        id, kind.dbName, name, tokenHash, ApiJson.encodeToString(Subscription.serializer(), Subscription()), clock(),
    )
}

private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

/** The device a bearer token belongs to, unless it was revoked. */
fun Repository.authenticate(token: String): Device? {
    val device = db.read { db.one("SELECT * FROM device WHERE token_hash = ? AND revoked_at IS NULL", sha256Hex(token)) { it.toDevice() } }
        ?: return null
    // Note activity at most once a minute, so downloads don't each cost a write.
    val now = clock()
    if (now - (lastSeen[device.id] ?: 0) > 60_000) {
        lastSeen[device.id] = now
        db.tx { db.exec("UPDATE device SET last_seen_at = ? WHERE id = ?", now, device.id) }
    }
    return device
}

fun Repository.device(id: String): Device? = db.read { db.one("SELECT * FROM device WHERE id = ?", id) { it.toDevice() } }

fun Repository.devices(): List<Device> = db.read { db.query("SELECT * FROM device ORDER BY revoked_at IS NOT NULL, created_at") { it.toDevice() } }

fun Repository.revoke(id: String) {
    db.tx {
        if (db.exec("UPDATE device SET revoked_at = ?, token_hash = NULL WHERE id = ? AND kind != 'web'", clock(), id) == 0) {
            notFound("no device $id")
        }
    }
}

/** Replaces a device's rules; its next GET /changes is a full listing, so it picks up the new scope. */
fun Repository.setSubscription(id: String, subscription: Subscription) {
    try {
        subscription.validate()
    } catch (e: IllegalArgumentException) {
        badRequest(e.message ?: "bad subscription")
    }
    db.tx {
        val changed = db.exec(
            "UPDATE device SET subscription = ?, rescan = 1 WHERE id = ?",
            ApiJson.encodeToString(Subscription.serializer(), subscription), id,
        )
        if (changed == 0) notFound("no device $id")
    }
}

fun Device.toDto() = DeviceDto(id, kind, name, syncedRev, lastSeenAt, subscription)

private fun ResultSet.toDevice() = Device(
    id = getString("id"),
    kind = deviceKind(getString("kind")),
    name = getString("name"),
    subscription = ApiJson.decodeFromString(Subscription.serializer(), getString("subscription")),
    rescan = getBoolean("rescan"),
    syncedRev = getLong("synced_rev"),
    createdAt = getLong("created_at"),
    lastSeenAt = longOrNull("last_seen_at"),
    revokedAt = longOrNull("revoked_at"),
)

/**
 * What changed for [device] after revision [since]: the latest version of every asset in its
 * scope, plus the assets that left it. Paged in (revision, asset id) order; [after] is the
 * previous page's [ChangesResponse.next].
 */
fun Repository.changes(device: Device, since: Long, after: String?, limit: Int): ChangesResponse = db.read {
    if (since < 0) badRequest("since must be >= 0")
    val pageSize = limit.coerceIn(1, Limits.MAX_CHANGES_PAGE)
    val (afterRev, afterAsset) = parseCursor(after)
    val head = head()
    // A subscription change can bring old assets into scope: list everything in scope instead.
    val reset = device.rescan
    val from = maxOf(if (reset) 0 else since, afterRev ?: 0)
    val rows = db.query(
        """
        SELECT v.* FROM asset_head h JOIN asset_version v ON v.asset_id = h.asset_id AND v.rev = h.rev
        WHERE h.rev > ? OR (h.rev = ? AND h.asset_id > ?)
        ORDER BY h.rev, h.asset_id LIMIT ?
        """.trimIndent(),
        from, afterRev ?: -1, afterAsset ?: "", pageSize + 1,
    ) { it.toVersion() }
    val more = rows.size > pageSize
    val page = rows.take(pageSize)

    val assets = mutableListOf<AssetDto>()
    val left = mutableListOf<String>()
    val scope = Scope(this, device)
    for (v in page) {
        val inScope = scope.holds(v)
        when {
            reset -> if (inScope && !v.deleted) assets += toDto(v)
            inScope -> assets += toDto(v)
            else -> {
                val before = versionAt(v.assetId, since)
                if (before != null && !before.deleted && scope.holds(before)) left += v.assetId
            }
        }
    }
    if (!more) {
        // Lets the web UI show how far behind each device is.
        if (head > device.syncedRev) db.exec("UPDATE device SET synced_rev = ? WHERE id = ?", head, device.id)
        if (reset) db.exec("UPDATE device SET rescan = 0 WHERE id = ?", device.id)
    }
    ChangesResponse(
        head = head,
        assets = assets,
        left = left,
        reset = reset,
        more = more,
        next = if (more) page.last().let { "${it.rev}:${it.assetId}" } else null,
    )
}

private fun parseCursor(after: String?): Pair<Long?, String?> {
    if (after == null) return null to null
    val rev = after.substringBefore(':').toLongOrNull()
    val asset = after.substringAfter(':', "")
    if (rev == null || asset.isEmpty()) badRequest("bad cursor: $after")
    return rev to asset
}

/** Kinds a subscription filter can name, for the web UI. */
val ASSET_KINDS = AssetKind.entries.map { it.apiName }
