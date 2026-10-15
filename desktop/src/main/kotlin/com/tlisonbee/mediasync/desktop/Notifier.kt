package com.tlisonbee.mediasync.desktop

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap

/** The freedesktop notification spec, which Plasma implements. */
@DBusInterfaceName("org.freedesktop.Notifications")
interface Notifications : DBusInterface {
    // Not named `notify`: dbus-java treats that name as Object.notify() and never sends the call.
    @DBusMemberName("Notify")
    fun sendNotification(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: Array<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32

    class ActionInvoked(path: String, val id: UInt32, val actionKey: String) : DBusSignal(path, id, actionKey)

    class NotificationClosed(path: String, val id: UInt32, val reason: UInt32) : DBusSignal(path, id, reason)
}

interface Notifier : AutoCloseable {
    /** Shows a notification; clicking it runs [onClick]. [key] lets a newer one replace an older one. */
    fun show(key: String, summary: String, body: String, onClick: (() -> Unit)? = null)

    override fun close() {}
}

/** Native Plasma notifications over the session bus, as in Remind. */
class PlasmaNotifier : Notifier {
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().build()
    private val service = connection.getRemoteObject("org.freedesktop.Notifications", "/org/freedesktop/Notifications", Notifications::class.java)
    private val byKey = ConcurrentHashMap<String, UInt32>()
    private val clicks = ConcurrentHashMap<UInt32, () -> Unit>()

    init {
        // These signals are broadcast for every app's notifications; only ours are in the map.
        connection.addSigHandler(Notifications.ActionInvoked::class.java) { signal -> clicks[signal.id]?.invoke() }
        connection.addSigHandler(Notifications.NotificationClosed::class.java) { signal -> clicks.remove(signal.id) }
    }

    override fun show(key: String, summary: String, body: String, onClick: (() -> Unit)?) {
        val id = service.sendNotification(
            "MediaSync",
            byKey[key] ?: UInt32(0),
            "mediasync",
            summary,
            body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"),
            if (onClick != null) arrayOf("default", "Open") else emptyArray(),
            mapOf("desktop-entry" to Variant("mediasync")),
            -1,
        )
        byKey[key] = id
        if (onClick != null) clicks[id] = onClick
    }

    override fun close() = connection.close()
}

/** Without a desktop session (or D-Bus), notifications only go to the log. */
class LogNotifier : Notifier {
    override fun show(key: String, summary: String, body: String, onClick: (() -> Unit)?) = println("$summary: $body")
}

fun desktopNotifier(): Notifier = try {
    PlasmaNotifier()
} catch (e: Exception) {
    System.err.println("No desktop notifications (${e.message}); they go to the log instead")
    LogNotifier()
}
