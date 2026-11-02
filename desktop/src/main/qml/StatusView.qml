// The Status tab: this PC's folders, what the service is doing, what waits for approval, and
// the review settings. Everything comes from WindowStatus; buttons POST to the window API.
import QtQuick
import QtQuick.Layouts
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kirigamiaddons.formcard as FormCard

QQC2.ScrollView {
    id: view

    property var status: null
    property string problem: ""
    readonly property bool busy: !!(status && status.busy)
    readonly property var review: status ? status.review : null
    readonly property var incoming: review ? review.groups.filter(g => g.incoming) : []
    readonly property var outgoing: review ? review.groups.filter(g => !g.incoming) : []

    signal call(string path, var body)
    signal openLibrary(string path)

    contentWidth: availableWidth
    QQC2.ScrollBar.horizontal.policy: QQC2.ScrollBar.AlwaysOff

    function decide(group, action) {
        call("act", { group: group.key, version: group.version, action: action })
    }

    ColumnLayout {
        width: view.availableWidth
        spacing: 0

        Kirigami.InlineMessage {
            Layout.fillWidth: true
            Layout.margins: Kirigami.Units.largeSpacing
            Layout.maximumWidth: Kirigami.Units.gridUnit * 30
            Layout.alignment: Qt.AlignHCenter
            visible: view.problem !== ""
            type: Kirigami.MessageType.Error
            text: view.problem
        }

        Kirigami.LoadingPlaceholder {
            Layout.alignment: Qt.AlignHCenter
            Layout.topMargin: Kirigami.Units.gridUnit * 4
            visible: !view.status && view.problem === ""
        }

        // ---- This PC ----
        FormCard.FormHeader {
            visible: !!view.status
            title: view.status ? view.status.device : ""
        }
        FormCard.FormCard {
            visible: !!view.status
            FormCard.FormTextDelegate {
                text: view.status ? view.status.server : ""
                description: view.status && view.status.version ? "MediaSync desktop " + view.status.version : ""
            }
            Repeater {
                model: view.status ? view.status.folders : []
                delegate: ColumnLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 0
                    FormCard.FormDelegateSeparator { Layout.fillWidth: true }
                    FormCard.FormTextDelegate {
                        Layout.fillWidth: true
                        text: modelData.folder + "  ↔  " + modelData.prefix
                        description: !modelData.present
                            ? "Not there, so it isn't synced until it's back"
                            : [modelData.mode,
                               modelData.files.toLocaleString(Qt.locale(), "f", 0) + (modelData.files === 1 ? " file" : " files"),
                               modelData.waiting > 0 ? modelData.waiting + " waiting to sync" : "",
                               modelData.leftAlone > 0 ? modelData.leftAlone + " left alone" : ""].filter(s => s).join(" · ")
                    }
                }
            }
        }

        // ---- What's happening ----
        FormCard.FormCard {
            visible: view.busy
            Layout.topMargin: Kirigami.Units.largeSpacing
            FormCard.AbstractFormDelegate {
                background: Item {}
                contentItem: ColumnLayout {
                    spacing: Kirigami.Units.smallSpacing
                    QQC2.Label {
                        text: view.busy ? view.status.busy + "…" : ""
                        color: Kirigami.Theme.highlightColor
                    }
                    QQC2.Label {
                        Layout.fillWidth: true
                        visible: text !== ""
                        text: view.status && view.status.progress ? view.status.progress : ""
                        wrapMode: Text.Wrap
                    }
                    QQC2.ProgressBar {
                        Layout.fillWidth: true
                        from: 0
                        to: 1
                        indeterminate: !(view.status && view.status.fraction !== null && view.status.fraction !== undefined)
                        value: view.status && view.status.fraction ? view.status.fraction : 0
                    }
                }
            }
        }

        Kirigami.InlineMessage {
            Layout.fillWidth: true
            Layout.leftMargin: Kirigami.Units.largeSpacing
            Layout.maximumWidth: Kirigami.Units.gridUnit * 30
            Layout.alignment: Qt.AlignHCenter
            Layout.rightMargin: Kirigami.Units.largeSpacing
            Layout.topMargin: Kirigami.Units.largeSpacing
            visible: !!(view.status && view.status.error)
            type: Kirigami.MessageType.Error
            text: view.status && view.status.error ? "Sync failing: " + view.status.error : ""
        }
        Repeater {
            model: view.review ? view.review.warnings.slice(0, 5) : []
            delegate: Kirigami.InlineMessage {
                required property string modelData
                Layout.fillWidth: true
                Layout.leftMargin: Kirigami.Units.largeSpacing
                Layout.maximumWidth: Kirigami.Units.gridUnit * 30
                Layout.alignment: Qt.AlignHCenter
                Layout.rightMargin: Kirigami.Units.largeSpacing
                Layout.topMargin: Kirigami.Units.smallSpacing
                visible: true
                type: Kirigami.MessageType.Warning
                text: modelData
            }
        }

        // ---- Changes ----
        FormCard.FormHeader {
            visible: !!view.status
            title: "Changes"
        }
        FormCard.FormCard {
            visible: !!view.status
            FormCard.FormTextDelegate {
                text: !view.review ? "" : view.review.empty ? "Nothing waiting" : view.review.summary
                description: !view.status ? ""
                    : (view.status.lastCheck ? "Last checked " + view.status.lastCheck : "Not checked yet")
                      + (view.status.settings.ask ? " · checks " + view.status.settings.choices.find(c => c.minutes === view.status.settings.minutes).label
                                                  : " · syncs by itself when anything changes")
            }
            FormCard.AbstractFormDelegate {
                background: Item {}
                contentItem: Flow {
                    spacing: Kirigami.Units.smallSpacing
                    QQC2.Button {
                        text: view.status && !view.status.settings.ask ? "Sync now" : "Check now"
                        icon.name: "view-refresh"
                        enabled: !view.busy
                        onClicked: view.call(view.status.settings.ask ? "check" : "sync", {})
                    }
                    QQC2.Button {
                        visible: !!(view.review && !view.review.empty)
                        text: "Accept all"
                        icon.name: "dialog-ok-apply"
                        highlighted: true
                        enabled: !view.busy
                        onClicked: view.call("act", { group: "all", version: view.review.version })
                    }
                    QQC2.Button {
                        text: "Open the library"
                        icon.name: "folder-pictures"
                        onClicked: view.openLibrary("")
                    }
                }
            }
        }

        FormCard.FormHeader {
            visible: view.incoming.length > 0
            title: "From the server"
        }
        FormCard.FormSectionText {
            visible: view.incoming.length > 0
            text: "Accept writes a change into your files. To leave it for later, do nothing; it stays here. Revert undoes it on the server, for every device."
        }
        Repeater {
            model: view.incoming
            delegate: ReviewGroup {
                busy: view.busy
                onDecide: action => action === "revert" ? confirm.ask(group, action) : view.decide(group, action)
            }
        }

        FormCard.FormHeader {
            visible: view.outgoing.length > 0
            title: "Made on this PC"
        }
        FormCard.FormSectionText {
            visible: view.outgoing.length > 0
            text: "Accept sends a change to the server. Undo here puts the files back as the server has them, and what changed here is lost; an undone new file stays, but isn't synced."
        }
        Repeater {
            model: view.outgoing
            delegate: ReviewGroup {
                busy: view.busy
                onDecide: action => action === "undo" ? confirm.ask(group, action) : view.decide(group, action)
            }
        }

        // ---- Last sync ----
        FormCard.FormHeader {
            visible: !!view.status
            title: "Last sync"
        }
        FormCard.FormCard {
            visible: !!view.status
            FormCard.FormTextDelegate {
                text: view.status && view.status.lastSync ? view.status.lastSync : "Never"
                description: view.status && view.status.lastOutcome ? view.status.lastOutcome : ""
            }
        }

        // ---- Settings ----
        FormCard.FormHeader {
            visible: !!view.status
            title: "Settings"
        }
        FormCard.FormCard {
            visible: !!view.status
            FormCard.FormSwitchDelegate {
                text: "Ask before syncing"
                description: "Without asking, MediaSync syncs as soon as anything changes, here or on the server."
                checked: !!(view.status && view.status.settings.ask)
                onToggled: view.call("settings", { ask: checked })
            }
            FormCard.FormDelegateSeparator {}
            FormCard.FormComboBoxDelegate {
                text: "Check for changes"
                enabled: !!(view.status && view.status.settings.ask)
                model: view.status ? view.status.settings.choices : []
                textRole: "label"
                valueRole: "minutes"
                currentIndex: view.status ? view.status.settings.choices.findIndex(c => c.minutes === view.status.settings.minutes) : -1
                onActivated: index => view.call("settings", { minutes: view.status.settings.choices[index].minutes })
            }
        }
        FormCard.FormSectionText {
            visible: !!view.status
            Layout.bottomMargin: Kirigami.Units.gridUnit
            text: "To sync another folder, or stop syncing one: mediasync-desktop add-folder, remove-folder (mediasync-desktop --help)."
        }
    }

    Kirigami.PromptDialog {
        id: confirm

        property var group: null
        property string action: ""

        function ask(group, action) {
            confirm.group = group
            confirm.action = action
            open()
        }

        title: action === "revert" ? "Revert r" + (group ? group.rev : "") + " on the server?" : "Undo these changes here?"
        subtitle: action === "revert"
            ? "Every device gets the old values back. History in the web UI can undo this too."
            : "The files go back as the server has them. What changed here is lost."
        standardButtons: Kirigami.Dialog.Ok | Kirigami.Dialog.Cancel
        onAccepted: view.decide(group, action)
    }
}
