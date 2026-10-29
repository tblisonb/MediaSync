package com.tlisonbee.mediasync.server

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/*
 * The web UI inside the Android app. A device's token only syncs; the app is let into the web UI
 * once, with the web password, and from then on swaps its token for a one-time ticket that logs
 * its web view in. The grant lasts until it's revoked on the Devices page, the device is revoked,
 * or the password changes.
 */

/** How long a ticket waits to be used: long enough to load one page. */
val TICKET_LIFETIME = 1.minutes

private val random = SecureRandom()

private class Ticket(val deviceId: String, val expiresAt: Long)

/** Outstanding tickets by their hash. In memory: a restart only means the app asks for another. */
private val tickets = ConcurrentHashMap<String, Ticket>()

private fun Repository.passwordFingerprint() = sha256Hex(dir.readSecrets().passwordHash)

fun Repository.webAllowed(device: Device): Boolean =
    device.revokedAt == null && device.webGrant != null && device.webGrant == passwordFingerprint()

/** Lets [device] into the web UI, if [password] is the web password. */
fun Repository.allowWeb(device: Device, password: String): Boolean {
    val secrets = dir.readSecrets()
    if (!Passwords.verify(password, secrets.passwordHash)) return false
    db.tx { db.exec("UPDATE device SET web_grant = ? WHERE id = ?", sha256Hex(secrets.passwordHash), device.id) }
    return true
}

fun Repository.disallowWeb(id: String) {
    db.tx { if (db.exec("UPDATE device SET web_grant = NULL WHERE id = ?", id) == 0) notFound("no device $id") }
}

/** A ticket for one web login as [device], or null if it isn't allowed into the web UI. */
fun Repository.newWebTicket(device: Device): String? {
    if (!webAllowed(device)) return null
    val now = clock()
    tickets.values.removeIf { it.expiresAt < now }
    val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    tickets[sha256Hex(ticket)] = Ticket(device.id, now + TICKET_LIFETIME.inWholeMilliseconds)
    return ticket
}

/** Uses up [ticket]: the device it was made for, or null if it's unknown, used, expired, or no longer allowed. */
fun Repository.redeemWebTicket(ticket: String): Device? {
    val found = tickets.remove(sha256Hex(ticket)) ?: return null
    if (found.expiresAt < clock()) return null
    return device(found.deviceId)?.takeIf { webAllowed(it) }
}

/** [next] if it's a path on this server, else the home page: a login never sends a browser elsewhere. */
fun localPath(next: String?): String =
    next?.takeIf { it.startsWith("/") && !it.startsWith("//") && '\\' !in it } ?: "/"
