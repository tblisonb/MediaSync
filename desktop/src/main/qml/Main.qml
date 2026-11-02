// The MediaSync window, run by Qt 6's qml tool:
//   qml --apptype widget Main.qml -- <file with the service's address> <status | web UI path>
// The application's domain is set before the window exists, so Wayland gets the app id
// com.tlisonbee.mediasync.qml, which mediasync.desktop names (StartupWMClass) for its icon.
import QtQuick

QtObject {
    property Component appWindow: Component { AppWindow {} }

    Component.onCompleted: {
        Qt.application.name = "MediaSync"
        Qt.application.displayName = "MediaSync"
        Qt.application.organization = "MediaSync"
        Qt.application.domain = "mediasync.tlisonbee.com"
        const args = Qt.application.arguments
        const at = args.indexOf("--")
        appWindow.createObject(null, {
            addressFile: at >= 0 ? args[at + 1] || "" : "",
            firstShow: at >= 0 ? args[at + 2] || "status" : "status",
        })
    }
}
