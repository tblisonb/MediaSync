// The window: Status and Library tabs, like the Android app's Sync and Library.
import QtQuick
import QtQuick.Layouts
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import "api.js" as Api

Kirigami.ApplicationWindow {
    id: window

    /** The file the service writes its address to, and what to show first. */
    property string addressFile
    property string firstShow: "status"

    /** The service's API (http://127.0.0.1:<port>/<secret>/api/), once read. */
    property string base: ""
    /** The last WindowStatus, or null before the first. */
    property var status: null
    /** Why there's no status: the service isn't running, say. */
    property string problem: ""
    property int seenRaise: -1
    property bool library: firstShow !== "status"

    title: "MediaSync"
    width: Kirigami.Units.gridUnit * 52
    height: Kirigami.Units.gridUnit * 42
    minimumWidth: Kirigami.Units.gridUnit * 22
    minimumHeight: Kirigami.Units.gridUnit * 20
    visible: true

    pageStack.globalToolBar.style: Kirigami.ApplicationHeaderStyle.None
    pageStack.initialPage: Kirigami.Page {
        padding: 0
        StackLayout {
            anchors.fill: parent
            currentIndex: window.library ? 1 : 0
            StatusView {
                status: window.status
                problem: window.problem
                onCall: (path, body) => window.call(path, body)
                onOpenLibrary: path => window.showLibrary(path)
            }
            LibraryView {
                id: libraryView
                active: window.library
                base: window.base
                firstPage: window.firstShow === "status" ? "/" : window.firstShow
                onFullScreenChanged: fullScreen ? window.showFullScreen() : window.showNormal()
                onNotice: text => window.showPassiveNotification(text, "long")
            }
        }
    }

    footer: Kirigami.NavigationTabBar {
        visible: !libraryView.fullScreen
        actions: [
            Kirigami.Action {
                text: "Status"
                icon.name: "view-refresh"
                checked: !window.library
                onTriggered: window.library = false
            },
            Kirigami.Action {
                text: "Library"
                icon.name: "folder-pictures"
                checked: window.library
                onTriggered: window.library = true
            }
        ]
    }

    function showLibrary(path) {
        library = true
        if (path) libraryView.open(path)
    }

    /** Finds the service: its address is in [addressFile], and changes when it restarts. */
    function connect() {
        Api.readFile(addressFile, text => {
            base = text + "api/"
            refresh()
        }, () => {
            base = ""
            problem = "The MediaSync service isn't running. Start it with: systemctl --user start mediasync-desktop"
        })
    }

    function refresh() {
        if (!base) return connect()
        Api.call("GET", base + "status", undefined, value => {
            problem = ""
            status = value
            if (seenRaise >= 0 && value.raise !== seenRaise) {
                if (value.show === "status") library = false; else showLibrary(value.show)
                window.raise()
                window.requestActivate()
            }
            seenRaise = value.raise
        }, (message, gone) => {
            problem = message
            if (gone) base = ""
        })
    }

    /** POSTs [body] to the API's [path]; says what came back if it wasn't done. */
    function call(path, body) {
        if (!base) return
        Api.call("POST", base + path, body || {}, reply => {
            if (reply.ok === false && reply.message) showPassiveNotification(reply.message, "long")
            else if (path === "settings" && reply.message) showPassiveNotification(reply.message)
            refresh()
        }, message => showPassiveNotification(message, "long"))
    }

    Timer {
        // Often while it's in front, now and then behind other windows; a raise arrives within one.
        interval: window.active ? 1500 : 4000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: window.refresh()
    }
}
