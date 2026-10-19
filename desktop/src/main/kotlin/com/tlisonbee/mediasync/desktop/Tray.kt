package com.tlisonbee.mediasync.desktop

import com.tlisonbee.mediasync.sync.Decisions
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.MethodNoReply
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the tray icon shows, from the service's state after each job. */
data class TrayState(
    /** Changes waiting for approval, and the review's own summary of them. */
    val waiting: Int = 0,
    val summary: String = "Nothing waiting",
    /** What the engine is doing, if anything ("Checking for changes"). */
    val busy: String? = null,
    val lastCheck: Long? = null,
    /** The last sync's failure, until one works again. */
    val error: String? = null,
    /** Review on: checks and asks. Off: syncs by itself, so the menu offers a sync instead. */
    val reviewing: Boolean = true,
    /** What "Accept all" accepts: the list as shown, nothing newer. */
    val acceptAll: Decisions = Decisions(),
) {
    val headline: String
        get() = when {
            busy != null -> "$busy…"
            error != null -> "Sync failing: $error"
            else -> summary.replaceFirstChar { it.uppercase() }
        }

    val needsAttention get() = waiting > 0 || error != null

    fun tooltip(): String = listOfNotNull(
        headline,
        lastCheck?.let { "Last checked " + DateTimeFormatter.ofPattern("MMM d, HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) },
    ).joinToString("\n")
}

/** What the tray's clicks and menu entries do. */
interface TrayActions {
    fun openReview()
    fun acceptAll(decisions: Decisions)
    fun checkNow()
    fun openWebUi()
}

/** One menu entry; [id] stays the same across updates, as dbusmenu expects. */
data class TrayEntry(val id: Int, val label: String, val enabled: Boolean = true, val separator: Boolean = false, val icon: String? = null, val run: (() -> Unit)? = null)

fun trayMenu(state: TrayState, actions: TrayActions): List<TrayEntry> = listOf(
    TrayEntry(1, state.headline, enabled = false),
    TrayEntry(2, "", separator = true),
    TrayEntry(3, "Review changes…", icon = "view-list-details", run = actions::openReview),
    TrayEntry(4, "Accept all", enabled = state.waiting > 0 && state.busy == null, icon = "dialog-ok-apply", run = { actions.acceptAll(state.acceptAll) }),
    TrayEntry(5, if (state.reviewing) "Check now" else "Sync now", enabled = state.busy == null, icon = "view-refresh", run = actions::checkNow),
    TrayEntry(6, "Open web UI", icon = "internet-web-browser", run = actions::openWebUi),
)

interface Tray : AutoCloseable {
    fun update(state: TrayState)

    override fun close() {}
}

/** Without a desktop session (or D-Bus), there's no tray icon. */
object NoTray : Tray {
    override fun update(state: TrayState) {}
}

fun desktopTray(actions: TrayActions): Tray = try {
    PlasmaTray(actions)
} catch (e: Exception) {
    System.err.println("No tray icon (${e.message})")
    NoTray
}

// ---- The freedesktop StatusNotifierItem spec (Plasma's system tray) and its dbusmenu ----

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    @DBusMemberName("RegisterStatusNotifierItem")
    fun registerItem(service: String)
}

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    @DBusMemberName("Activate")
    fun activate(x: Int, y: Int)

    @DBusMemberName("SecondaryActivate")
    fun secondaryActivate(x: Int, y: Int)

    @DBusMemberName("ContextMenu")
    fun contextMenu(x: Int, y: Int)

    @DBusMemberName("Scroll")
    fun scroll(delta: Int, orientation: String)

    class NewStatus(path: String, val status: String) : DBusSignal(path, status)

    class NewToolTip(path: String) : DBusSignal(path)

    class NewOverlayIcon(path: String) : DBusSignal(path)
}

/** An icon as pixels; the tray uses the icon theme instead, so these lists stay empty. */
class Pixmap(@field:Position(0) @JvmField val width: Int, @field:Position(1) @JvmField val height: Int, @field:Position(2) @JvmField val argb: ByteArray) : Struct()

class ToolTip(
    @field:Position(0) @JvmField val iconName: String,
    @field:Position(1) @JvmField val pixmaps: List<Pixmap>,
    @field:Position(2) @JvmField val title: String,
    @field:Position(3) @JvmField val text: String,
) : Struct()

@DBusInterfaceName("com.canonical.dbusmenu")
interface DBusMenu : DBusInterface {
    @DBusMemberName("GetLayout")
    fun getLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): LayoutReply<UInt32, MenuLayout>

    @DBusMemberName("GetGroupProperties")
    fun getGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties>

    @DBusMemberName("GetProperty")
    fun getProperty(id: Int, name: String): Variant<*>

    @MethodNoReply
    @DBusMemberName("Event")
    fun event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)

    @DBusMemberName("EventGroup")
    fun eventGroup(events: List<MenuEvent>): List<Int>

    @DBusMemberName("AboutToShow")
    fun aboutToShow(id: Int): Boolean

    @DBusMemberName("AboutToShowGroup")
    fun aboutToShowGroup(ids: List<Int>): AboutToShowReply<List<Int>, List<Int>>

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

/** One menu item and its children, each a variant holding another of these: (ia{sv}av). */
class MenuLayout(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, @JvmSuppressWildcards Variant<*>>,
    @field:Position(2) @JvmField val children: List<@JvmSuppressWildcards Variant<*>>,
) : Struct()

// The replies are generic because dbus-java reads a returned tuple's element types from the
// method's return type (a plain List field would be refused as a raw type).
class LayoutReply<A, B>(@field:Position(0) @JvmField val revision: A, @field:Position(1) @JvmField val layout: B) : Tuple()

class ItemProperties(@field:Position(0) @JvmField val id: Int, @field:Position(1) @JvmField val properties: Map<String, @JvmSuppressWildcards Variant<*>>) : Struct()

class MenuEvent(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val eventId: String,
    @field:Position(2) @JvmField val data: Variant<*>,
    @field:Position(3) @JvmField val timestamp: UInt32,
) : Struct()

class AboutToShowReply<A, B>(@field:Position(0) @JvmField val updatesNeeded: A, @field:Position(1) @JvmField val idErrors: B) : Tuple()

/**
 * A tray icon in Plasma's system tray: a click opens the review page, a middle click checks now,
 * and the menu has the rest. Registers with the tray's watcher again whenever that restarts.
 */
class PlasmaTray(private val actions: TrayActions) : Tray {
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().build()

    @Volatile
    private var state = TrayState()

    @Volatile
    private var entries = trayMenu(state, actions)

    @Volatile
    private var revision = 1L

    private val item = Item()
    private val menu = Menu()

    init {
        connection.exportObject(ITEM_PATH, item)
        connection.exportObject(MENU_PATH, menu)
        connection.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
            if (signal.name == WATCHER && signal.newOwner.isNotEmpty()) register()
        }
        register()
    }

    private fun register() {
        try {
            connection.getRemoteObject(WATCHER, "/StatusNotifierWatcher", StatusNotifierWatcher::class.java).registerItem(connection.uniqueName)
        } catch (e: Exception) {
            // No tray yet (Plasma still starting); NameOwnerChanged registers it once there is one.
        }
    }

    override fun update(state: TrayState) {
        val before = this.state
        this.state = state
        entries = trayMenu(state, actions)
        revision++
        try {
            if (before.needsAttention != state.needsAttention) {
                connection.sendMessage(StatusNotifierItem.NewStatus(ITEM_PATH, item.status()))
                connection.sendMessage(StatusNotifierItem.NewOverlayIcon(ITEM_PATH))
            }
            connection.sendMessage(StatusNotifierItem.NewToolTip(ITEM_PATH))
            connection.sendMessage(DBusMenu.LayoutUpdated(MENU_PATH, UInt32(revision), 0))
        } catch (e: Exception) {
            // The bus went away; the next update tries again.
        }
    }

    override fun close() = connection.close()

    private inner class Item : StatusNotifierItem, Properties {
        fun status() = if (state.needsAttention) "NeedsAttention" else "Active"

        private fun properties(): Map<String, Variant<*>> = mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("mediasync"),
            "Title" to Variant("MediaSync"),
            "Status" to Variant(status()),
            "IconName" to Variant("mediasync"),
            "IconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
            "OverlayIconName" to Variant(if (state.needsAttention) "emblem-important" else ""),
            "OverlayIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
            "AttentionIconName" to Variant("mediasync"),
            "AttentionIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
            "AttentionMovieName" to Variant(""),
            "IconThemePath" to Variant(""),
            "ToolTip" to Variant(ToolTip("mediasync", emptyList(), "MediaSync", state.tooltip()), "(sa(iiay)ss)"),
            "ItemIsMenu" to Variant(false),
            "Menu" to Variant(DBusPath(MENU_PATH)),
            "WindowId" to Variant(0),
        )

        override fun activate(x: Int, y: Int) = actions.openReview()

        override fun secondaryActivate(x: Int, y: Int) = actions.checkNow()

        override fun contextMenu(x: Int, y: Int) {} // Plasma shows [Menu] itself

        override fun scroll(delta: Int, orientation: String) {}

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = properties().getValue(propertyName) as A

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties()

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}

        override fun getObjectPath() = ITEM_PATH
    }

    private inner class Menu : DBusMenu, Properties {
        private fun properties(): Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )

        private fun propertiesOf(entry: TrayEntry): Map<String, Variant<*>> = buildMap {
            if (entry.separator) {
                put("type", Variant("separator"))
            } else {
                // dbusmenu labels take "_" as the mnemonic marker.
                put("label", Variant(entry.label.replace("_", "__")))
                if (!entry.enabled) put("enabled", Variant(false))
                entry.icon?.let { put("icon-name", Variant(it)) }
            }
        }

        private fun layoutOf(entry: TrayEntry) = MenuLayout(entry.id, propertiesOf(entry), emptyList())

        override fun getLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): LayoutReply<UInt32, MenuLayout> {
            val current = entries
            val layout = if (parentId == 0) {
                val children = if (recursionDepth == 0) emptyList() else current.map { Variant(layoutOf(it), LAYOUT_SIGNATURE) }
                MenuLayout(0, mapOf("children-display" to Variant("submenu")), children)
            } else {
                current.firstOrNull { it.id == parentId }?.let(::layoutOf) ?: MenuLayout(parentId, emptyMap(), emptyList())
            }
            return LayoutReply(UInt32(revision), layout)
        }

        override fun getGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties> {
            val current = entries
            return ids.mapNotNull { id ->
                if (id == 0) ItemProperties(0, mapOf("children-display" to Variant("submenu"))) else current.firstOrNull { it.id == id }?.let { ItemProperties(id, propertiesOf(it)) }
            }
        }

        override fun getProperty(id: Int, name: String): Variant<*> =
            entries.firstOrNull { it.id == id }?.let { propertiesOf(it)[name] } ?: Variant("")

        override fun event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            val entry = entries.firstOrNull { it.id == id } ?: return
            if (entry.enabled) entry.run?.invoke()
        }

        override fun eventGroup(events: List<MenuEvent>): List<Int> {
            val known = entries.map { it.id }.toSet()
            events.forEach { event(it.id, it.eventId, it.data, it.timestamp) }
            return events.map { it.id }.filter { it !in known }
        }

        override fun aboutToShow(id: Int) = false

        override fun aboutToShowGroup(ids: List<Int>) = AboutToShowReply<List<Int>, List<Int>>(emptyList(), emptyList())

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = properties().getValue(propertyName) as A

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties()

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}

        override fun getObjectPath() = MENU_PATH
    }

    companion object {
        private const val WATCHER = "org.kde.StatusNotifierWatcher"
        private const val ITEM_PATH = "/StatusNotifierItem"
        private const val MENU_PATH = "/MenuBar"
        private const val LAYOUT_SIGNATURE = "(ia{sv}av)"
    }
}
