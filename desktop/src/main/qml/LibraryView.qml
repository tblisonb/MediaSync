// The Library tab: the web UI in QtWebEngine. This PC is let in once with the web password;
// after that the service gets a one-time ticket from the server for each login, and the page
// posts it to /login/device, as the Android app does.
import QtQuick
import QtQuick.Layouts
import QtQuick.Controls as QQC2
import QtWebEngine
import org.kde.kirigami as Kirigami
import "api.js" as Api

Item {
    id: library

    /** The tab is showing; the first login waits for that. */
    property bool active: false
    /** The service's window API. */
    property string base: ""
    property string firstPage: "/"
    property bool fullScreen: false

    /** "idle", "loading", "password", "error" or "ready". */
    property string phase: "idle"
    property string message: ""
    property bool allowing: false
    property string server: ""
    /** The last web UI page asked for, which a login returns to. */
    property string lastPage: "/"
    /** Logins in a row that landed back on the login page. */
    property int tries: 0

    signal notice(string text)

    onActiveChanged: start()
    onBaseChanged: start()

    function start() {
        if (active && base && phase === "idle") login(firstPage)
    }

    /** Shows [path] (a web UI path such as "/resolve"), logging in first if need be. */
    function open(path) {
        if (phase === "ready" && server) web.url = server + path
        else if (phase === "idle") firstPage = path
        else login(path)
    }

    // The callbacks below run later, from api.js, where names don't resolve to this item's
    // properties: each function keeps the item in `self` for them.
    function login(next) {
        const self = library
        const view = web
        lastPage = next
        phase = "loading"
        Api.call("POST", base + "library", {}, reply => {
            self.server = reply.server.replace(/\/+$/, "")
            if (reply.ticket) {
                self.phase = "ready"
                // The form's page is the server's own, so the login and its cookie are same-site.
                view.loadHtml(self.loginForm(reply.ticket, next), self.server + "/login/device")
            } else if (reply.needsPassword) {
                self.phase = "password"
            } else {
                self.message = reply.error || "The server didn't give this PC a login."
                self.phase = "error"
            }
        }, text => {
            self.message = text
            self.phase = "error"
        })
    }

    function loginForm(ticket, next) {
        const esc = s => String(s).replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;")
        return "<!doctype html><html><body style=\"background:" + Kirigami.Theme.backgroundColor + "\">"
            + "<form method=\"post\" action=\"/login/device\">"
            + "<input type=\"hidden\" name=\"ticket\" value=\"" + esc(ticket) + "\">"
            + "<input type=\"hidden\" name=\"next\" value=\"" + esc(next) + "\"></form>"
            + "<script>document.forms[0].submit()</script></body></html>"
    }

    function allow() {
        if (!password.text || allowing) return
        const self = library
        const field = password
        allowing = true
        message = ""
        Api.call("POST", base + "allow", { password: password.text }, reply => {
            self.allowing = false
            if (reply.ok) {
                field.text = ""
                self.login(self.lastPage)
            } else {
                self.message = reply.message
            }
        }, text => {
            self.allowing = false
            self.message = text
        })
    }

    function isServer(url) {
        return server !== "" && (url === server || url.startsWith(server + "/") || url.startsWith(server + "?"))
    }

    WebEngineProfile {
        id: profile
        storageName: "mediasync"
        offTheRecord: false
        // The web UI's session cookie outlives the window, so it opens logged in.
        persistentCookiesPolicy: WebEngineProfile.ForcePersistentCookies
        onDownloadRequested: download => download.accept()
        onDownloadFinished: download => {
            if (download.state === WebEngineDownloadRequest.DownloadCompleted) library.notice("Saved " + download.downloadFileName + " to " + download.downloadDirectory)
            else if (download.state === WebEngineDownloadRequest.DownloadInterrupted) library.notice("Couldn't download " + download.downloadFileName + ": " + download.interruptReasonString)
        }
    }

    WebEngineView {
        id: web
        anchors.fill: parent
        visible: library.phase === "ready"
        profile: profile
        backgroundColor: Kirigami.Theme.backgroundColor
        settings.playbackRequiresUserGesture: false
        settings.fullScreenSupportEnabled: true

        onFullScreenRequested: request => {
            request.accept()
            library.fullScreen = request.toggleOn
        }

        onLoadingChanged: request => {
            if (request.status !== WebEngineView.LoadSucceededStatus) return
            const url = request.url.toString()
            if (!library.isServer(url)) return
            const path = url.substring(library.server.length) || "/"
            if (path.startsWith("/login/device")) return
            if (path.startsWith("/login")) {
                // The session expired, or this PC's access was revoked: a fresh login, back to the same page.
                if (++library.tries > 2) {
                    library.message = "The web UI keeps asking this PC to log in again."
                    library.phase = "error"
                } else {
                    library.login(library.lastPage)
                }
                return
            }
            library.tries = 0
            library.lastPage = path
        }

        // Other sites open in the browser.
        onNavigationRequested: request => {
            const url = request.url.toString()
            if (!request.isMainFrame || library.isServer(url) || url.startsWith("data:") || url.startsWith("about:")) return
            request.reject()
            Qt.openUrlExternally(request.url)
        }
        onNewWindowRequested: request => {
            const url = request.requestedUrl.toString()
            if (library.isServer(url)) web.url = url
            else Qt.openUrlExternally(request.requestedUrl)
        }

        TapHandler {
            acceptedButtons: Qt.BackButton | Qt.ForwardButton
            onTapped: (point, button) => button === Qt.BackButton ? web.goBack() : web.goForward()
        }
    }

    QQC2.ProgressBar {
        anchors { left: parent.left; right: parent.right; top: parent.top }
        visible: library.phase === "ready" && web.loading
        from: 0
        to: 100
        value: web.loadProgress
    }

    Shortcut {
        enabled: library.active && library.phase === "ready"
        sequences: [StandardKey.Back]
        onActivated: web.goBack()
    }
    Shortcut {
        enabled: library.active && library.phase === "ready"
        sequences: [StandardKey.Forward]
        onActivated: web.goForward()
    }
    Shortcut {
        enabled: library.active && library.phase === "ready"
        sequences: [StandardKey.Refresh]
        onActivated: web.reload()
    }
    Shortcut {
        enabled: library.active && library.phase === "ready"
        sequences: [StandardKey.ZoomIn]
        onActivated: web.zoomFactor = Math.min(web.zoomFactor + 0.1, 3)
    }
    Shortcut {
        enabled: library.active && library.phase === "ready"
        sequences: [StandardKey.ZoomOut]
        onActivated: web.zoomFactor = Math.max(web.zoomFactor - 0.1, 0.3)
    }
    Shortcut {
        enabled: library.active && library.phase === "ready"
        sequence: "Ctrl+0"
        onActivated: web.zoomFactor = 1
    }

    Kirigami.LoadingPlaceholder {
        anchors.centerIn: parent
        visible: library.phase === "loading" || (library.phase === "idle" && library.active)
    }

    Kirigami.PlaceholderMessage {
        anchors.centerIn: parent
        width: parent.width - Kirigami.Units.gridUnit * 4
        visible: library.phase === "error"
        icon.name: "dialog-error"
        text: library.message
        helpfulAction: Kirigami.Action {
            text: "Try again"
            icon.name: "view-refresh"
            onTriggered: {
                library.tries = 0
                library.login(library.lastPage)
            }
        }
    }

    // The first visit: the web password lets this PC in, once.
    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(parent.width - Kirigami.Units.gridUnit * 4, Kirigami.Units.gridUnit * 26)
        visible: library.phase === "password"
        spacing: Kirigami.Units.largeSpacing

        Kirigami.Heading {
            Layout.fillWidth: true
            text: "Open the library here"
            wrapMode: Text.Wrap
        }
        QQC2.Label {
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            text: "The library is the web UI, inside this window. Enter its password once to let this PC in. After that it opens without the password, until you revoke this PC's web access on the Devices page or change the password."
        }
        Kirigami.PasswordField {
            id: password
            Layout.fillWidth: true
            placeholderText: "Web UI password"
            onAccepted: library.allow()
        }
        QQC2.Label {
            Layout.fillWidth: true
            visible: library.message !== "" && library.phase === "password"
            text: library.message
            color: Kirigami.Theme.negativeTextColor
            wrapMode: Text.Wrap
        }
        QQC2.Button {
            text: library.allowing ? "Letting this PC in…" : "Let this PC in"
            highlighted: true
            enabled: password.text !== "" && !library.allowing
            onClicked: library.allow()
        }
    }
}
