package com.tlisonbee.mediasync.server

import com.tlisonbee.mediasync.api.DeviceKind
import java.lang.management.ManagementFactory
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.footer
import kotlinx.html.span

/** What the footer of every page and Settings > About say of the server. */
class ServerInfo(
    /** From the jar's manifest; null when not run from the built jar. */
    val version: String?,
    val head: Long,
    /** Paired apps and desktops, not web sessions or revoked devices. */
    val devices: Int,
    /** Of those, the ones that synced in the last day. */
    val recent: Int,
    val upMillis: Long,
)

private const val DAY_MS = 24 * 3600_000L

fun Repository.serverInfo(version: String?): ServerInfo {
    val now = clock()
    val devices = devices().filter { it.revokedAt == null && it.kind != DeviceKind.WEB }
    return ServerInfo(
        version = version,
        head = head(),
        devices = devices.size,
        recent = devices.count { (it.lastSeenAt ?: 0) > now - DAY_MS },
        upMillis = ManagementFactory.getRuntimeMXBean().uptime,
    )
}

/**
 * Where [HTML.page] gets the footer from. Set once by the module, since pages are built without
 * the repository; null (no footer) until then.
 */
internal object WebFooter {
    @Volatile var info: (() -> ServerInfo)? = null
}

/** "1 h", "3 days": how long the server has been up. */
internal fun uptime(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 48 * 60 -> "${minutes / 60} h"
        else -> "${minutes / (24 * 60)} days"
    }
}

internal fun versionName(version: String?) = version ?: "development build"

/** One line at the foot of every page: the version, the revision, the devices, uptime. */
internal fun FlowContent.serverFooter(info: ServerInfo) = footer("server-footer") {
    span { +"MediaSync server ${versionName(info.version)}" }
    span { a(href = "/revisions") { +"r${info.head}" } }
    span { a(href = "/devices") { +plural(info.devices, "device", "devices") }; +", ${info.recent} synced in the last day" }
    span { +"up ${uptime(info.upMillis)}" }
}
