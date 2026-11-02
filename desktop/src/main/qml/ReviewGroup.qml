// One group of changes waiting for approval: what it is, its buttons, and its files.
import QtQuick
import QtQuick.Layouts
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kirigamiaddons.formcard as FormCard

FormCard.FormCard {
    id: card

    required property var modelData
    readonly property var group: modelData
    property bool busy: false
    property bool expanded: false

    /** "accept", "undo" or "revert". */
    signal decide(string action)

    Layout.topMargin: Kirigami.Units.largeSpacing

    FormCard.AbstractFormDelegate {
        background: Item {}
        contentItem: ColumnLayout {
            spacing: Kirigami.Units.smallSpacing

            Kirigami.Heading {
                Layout.fillWidth: true
                level: 4
                text: card.group.title
                wrapMode: Text.Wrap
            }
            QQC2.Label {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                color: Kirigami.Theme.disabledTextColor
                text: [card.group.subtitle, card.group.count + (card.group.count === 1 ? " file" : " files")].filter(s => s).join(" · ")
            }
            QQC2.Label {
                visible: card.group.conflicts > 0
                color: Kirigami.Theme.negativeTextColor
                text: card.group.conflicts + " changed on both sides"
            }
            Flow {
                Layout.fillWidth: true
                spacing: Kirigami.Units.smallSpacing
                QQC2.Button {
                    text: "Accept"
                    highlighted: true
                    enabled: !card.busy
                    onClicked: card.decide("accept")
                }
                QQC2.Button {
                    visible: !card.group.incoming
                    text: "Undo here…"
                    enabled: !card.busy
                    onClicked: card.decide("undo")
                }
                QQC2.Button {
                    visible: card.group.incoming && card.group.rev !== null && card.group.rev !== undefined
                    text: "Revert r" + card.group.rev + " on the server…"
                    enabled: !card.busy
                    onClicked: card.decide("revert")
                }
                QQC2.Button {
                    flat: true
                    text: card.expanded ? "Hide the files" : "Show the files"
                    icon.name: card.expanded ? "arrow-up" : "arrow-down"
                    onClicked: card.expanded = !card.expanded
                }
            }
            Repeater {
                model: card.expanded ? card.group.items : []
                delegate: ColumnLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 0
                    Kirigami.Separator { Layout.fillWidth: true }
                    QQC2.Label {
                        Layout.fillWidth: true
                        Layout.topMargin: Kirigami.Units.smallSpacing
                        text: modelData.path
                        wrapMode: Text.WrapAnywhere
                    }
                    QQC2.Label {
                        Layout.fillWidth: true
                        Layout.bottomMargin: Kirigami.Units.smallSpacing
                        text: modelData.detail + (modelData.conflict ? " · changed on both sides" : "")
                        color: modelData.conflict ? Kirigami.Theme.negativeTextColor : Kirigami.Theme.disabledTextColor
                        font: Kirigami.Theme.smallFont
                        wrapMode: Text.Wrap
                    }
                }
            }
            QQC2.Label {
                visible: card.expanded && card.group.count > card.group.items.length
                text: "And " + (card.group.count - card.group.items.length) + " more."
                color: Kirigami.Theme.disabledTextColor
            }
        }
    }
}
