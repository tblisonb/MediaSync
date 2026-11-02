package com.tlisonbee.mediasync.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What [DesktopWindow.launch] did. */
enum class WindowLaunch { STARTED, RUNNING, UNAVAILABLE }

/**
 * The MediaSync window: share/qml/Main.qml, run by Qt 6's `qml` tool (Debian: qml-qt6) with the
 * system's Qt, Kirigami and QtWebEngine. It's a view of the running service: it reads the
 * service's address from [Dirs.reviewUrl] and talks to [WindowApi]. It runs as its own systemd
 * user unit, outside the service's sandbox, which also keeps it to one window.
 */
object DesktopWindow {
    const val UNIT = "mediasync-window"

    /** What to install when there's no `qml` tool. */
    const val NEEDS = "The MediaSync window needs Qt 6's qml tool and some QML modules: " +
        "sudo apt install qml-qt6 qml6-module-qtwebengine qml6-module-org-kde-kirigamiaddons-formcard"

    /** Qt 6's qml tool; MEDIASYNC_QML overrides where it's looked for. */
    fun qml(): Path? {
        System.getenv("MEDIASYNC_QML")?.let { Path.of(it) }?.takeIf(Files::isExecutable)?.let { return it }
        val known = listOf("/usr/lib/qt6/bin/qml", "/usr/lib64/qt6/bin/qml", "/usr/lib/x86_64-linux-gnu/qt6/bin/qml").map { Path.of(it) }
        // Not plain `qml` on PATH: on Debian that's Qt 5's, through qtchooser.
        val onPath = System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }.map { Path.of(it, "qml6") }
        return (known + onPath).firstOrNull(Files::isExecutable)
    }

    /** share/qml/Main.qml in the installed program; null when not run from an install (tests). */
    fun main(): Path? {
        val jar = runCatching { Path.of(DesktopWindow::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
        return jar.parent?.parent?.resolve("share/qml/Main.qml")?.takeIf(Files::isRegularFile)
    }

    fun running(): Boolean = runCatching {
        ProcessBuilder("systemctl", "--user", "is-active", "--quiet", "$UNIT.service").start().waitFor() == 0
    }.getOrDefault(false)

    /**
     * Opens the window on [show] ("status", or a web UI path such as "/resolve" for the Library
     * tab), unless it's already open: then the caller raises it.
     */
    fun launch(dirs: Dirs, show: String): WindowLaunch {
        val qml = qml() ?: return WindowLaunch.UNAVAILABLE
        val main = main() ?: return WindowLaunch.UNAVAILABLE
        if (running()) return WindowLaunch.RUNNING
        // widget: a QApplication, which Plasma's style for Qt Quick Controls needs.
        val command = listOf(qml.toString(), "--apptype", "widget", main.toString(), "--", dirs.reviewUrl.toString(), show)
        val viaSystemd = runCatching {
            ProcessBuilder(listOf("systemd-run", "--user", "--quiet", "--collect", "--unit=$UNIT", "--setenv=QML_XHR_ALLOW_FILE_READ=1") + command)
                .inheritIO().start().waitFor() == 0
        }.getOrDefault(false)
        if (!viaSystemd) {
            ProcessBuilder(command).apply { environment()["QML_XHR_ALLOW_FILE_READ"] = "1" }.inheritIO().start()
        }
        // Started by a background service, the window has no activation token, so Plasma opens it
        // behind the window in front; once it's there, KWin brings it forward.
        Thread({
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until && !activate()) Thread.sleep(300)
        }, "window-activate").apply { isDaemon = true }.start()
        return WindowLaunch.STARTED
    }

    /** The window's Wayland app id (Main.qml sets the domain it's made from). */
    private const val APP_ID = "com.tlisonbee.mediasync.qml"

    /**
     * Brings the open window to the front through KWin's window search (Plasma); false if there's
     * no such window, or no KWin. Only a window with this app's id, not a browser tab named MediaSync.
     */
    fun activate(): Boolean {
        val matches = gdbus("/WindowsRunner", "org.kde.krunner1.Match", "MediaSync") ?: return false
        for (id in Regex("'(0_\\{[0-9a-fA-F-]+\\})'").findAll(matches).map { it.groupValues[1] }) {
            val info = gdbus("/KWin", "org.kde.KWin.getWindowInfo", id.removePrefix("0_")) ?: continue
            if ("'resourceClass': <'$APP_ID'>" !in info) continue
            return gdbus("/WindowsRunner", "org.kde.krunner1.Run", id, "") != null
        }
        return false
    }

    /** Calls KWin over the session bus with gdbus; its output, or null if that failed. */
    private fun gdbus(path: String, method: String, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("gdbus", "call", "--session", "--dest", "org.kde.KWin", "--object-path", path, "--method", method) + args)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) null else output
    }.getOrNull()
}
