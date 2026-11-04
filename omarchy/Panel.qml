import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Quickshell
import Quickshell.Io
import qs.Commons
import qs.Commons as Commons
import qs.Ui

// Hypr Screen in the Omarchy bar: the connected phone, paired phones, settings
// and quick actions. Everything goes through `hypr-screen` (bin/hypr-screen is a
// wrapper written by `hypr-screen --install-omarchy`), which prints JSON.
Panel {
  id: root
  moduleName: "kpanuragh.hypr-screen"
  ipcTarget: "kpanuragh.hypr-screen"

  readonly property string bin:
    Qt.resolvedUrl("bin/hypr-screen").toString().replace(/^file:\/\//, "")
  readonly property bool hideWhenIdle: setting("hideWhenIdle", false)
  readonly property int refreshSec: Math.max(2, setting("refreshIntervalSec", 5))

  readonly property color foreground: bar ? bar.foreground : Commons.Color.foreground
  readonly property color accent: Commons.Color.accent
  readonly property color urgent: bar ? bar.urgent : Commons.Color.urgent
  readonly property color dim: Qt.darker(foreground, 1.55)
  readonly property string fontFamily: bar ? bar.fontFamily : Style.font.family

  readonly property string phoneIcon: "󰄜"
  readonly property string forgetIcon: "󰅙"

  property var st: null            // last `hypr-screen --state`
  property var apps: []            // last `hypr-screen --audio-apps`
  property bool busy: false        // an action is running
  property string busyKey: ""      // which one, for the spinner/disabled state
  property string pendingForget: ""
  property string message: ""
  property bool messageIsError: false

  readonly property var connected: st ? st.connected : null
  readonly property bool running: st ? st.running : false
  readonly property string service: st ? String(st.service) : "unknown"
  readonly property bool serviceActive: service === "active"
  readonly property var settingsState: st && st.settings ? st.settings : ({})
  // Assigned only when they really change: a new array rebuilds the Repeater rows (and eats clicks).
  property var devices: []
  // Changes only when another phone connects or its sound starts or stops, not on every poll.
  readonly property string connKey: connected
    ? connected.device + "|" + connected.since + "|" + connected.audio : ""

  visible: !(hideWhenIdle && !connected)
  onVisibleChanged: if (!visible && opened) close()
  implicitWidth: visible ? button.implicitWidth : 0
  implicitHeight: button.implicitHeight

  function parseJson(text) {
    try { return JSON.parse(String(text || "").trim() || "null") } catch (e) { return null }
  }

  function showMessage(text, isError) {
    message = text
    messageIsError = isError
    messageTimer.restart()
  }

  function refresh() {
    if (!stateProc.running) stateProc.running = true
  }

  function refreshApps() {
    if (!connected || !connected.audio) setIfChanged("apps", [])
    else if (opened && !appsProc.running) appsProc.running = true
  }

  function setIfChanged(name, value) {
    if (JSON.stringify(root[name]) !== JSON.stringify(value)) root[name] = value
  }

  // Runs one hypr-screen (or systemctl) command, then refreshes.
  function act(key, command, okText) {
    if (busy) return
    busy = true
    busyKey = key
    busyTimeout.restart()
    actionProc.okText = okText || ""
    actionProc.command = command
    actionProc.running = true
  }

  function statusLine() {
    if (!st) return "Checking…"
    if (connected) {
      var c = connected
      var what = c.mirror ? "mirroring " + c.target : c.width + "×" + c.height + " @ " + c.fps + " fps"
      return c.name + " via " + c.via + " · " + what
    }
    if (running) return "No phone connected"
    if (service === "absent") return "hypr-screen is not installed"
    if (service === "old") return "Update hypr-screen to 0.8.0 or newer"
    if (service === "missing") return "Service not installed"
    return "Service stopped"
  }

  function tooltip() {
    if (!connected) return "Hypr Screen: " + statusLine().toLowerCase()
    var mins = Math.floor((Date.now() / 1000 - connected.since) / 60)
    var t = "Hypr Screen: " + statusLine() + "\nconnected " + mins + " min"
    if (connected.level) t += "\nlink busy: quality level " + connected.level
    return t
  }

  function addedText(ts) {
    if (!ts) return ""
    return "paired " + Qt.formatDate(new Date(ts * 1000), "yyyy-MM-dd")
  }

  onOpenedChanged: if (opened) {
    pendingForget = ""
    refresh()
    refreshApps()
  }
  onConnKeyChanged: refreshApps()

  Timer {
    interval: (root.opened ? 2 : root.refreshSec) * 1000
    running: true
    repeat: true
    triggeredOnStart: true
    onTriggered: {
      root.refresh()
      if (root.opened) root.refreshApps()
    }
  }

  Timer {  // in case a command never reports back: don't leave every control disabled
    id: busyTimeout
    interval: 45000
    onTriggered: root.busy = false
  }

  Timer {
    id: messageTimer
    interval: 5000
    onTriggered: root.message = ""
  }

  Timer {
    id: forgetTimer
    interval: 4000
    onTriggered: root.pendingForget = ""
  }

  Process {
    id: stateProc
    running: false
    command: [root.bin, "--state"]
    stdout: StdioCollector { id: stateOut; waitForEnd: true }
    onExited: function(exitCode) {
      var data = root.parseJson(stateOut.text)
      if (exitCode === 0 && data) {
        root.st = data
        root.setIfChanged("devices", Array.isArray(data.devices) ? data.devices : [])
      } else {
        // 127: bin/hypr-screen found no hypr-screen; 2: a version without --state (before 0.8.0)
        var why = exitCode === 127 ? "absent" : exitCode === 2 ? "old" : "unknown"
        if (!root.st || why !== "unknown")
          root.st = { running: false, service: why, connected: null, devices: [], settings: {} }
      }
    }
  }

  Process {
    id: appsProc
    running: false
    command: [root.bin, "--audio-apps"]
    stdout: StdioCollector { id: appsOut; waitForEnd: true }
    onExited: function(exitCode) {
      var data = root.parseJson(appsOut.text)
      root.setIfChanged("apps", exitCode === 0 && data && Array.isArray(data.apps) ? data.apps : [])
    }
  }

  Process {
    id: actionProc
    property string okText: ""
    running: false
    command: []
    stdout: StdioCollector { id: actionOut; waitForEnd: true }
    stderr: StdioCollector { id: actionErr; waitForEnd: true }
    onExited: function(exitCode) {
      root.busy = false
      root.busyKey = ""
      if (exitCode === 0) {
        var data = root.parseJson(actionOut.text)
        var text = actionProc.okText
        if (data && data.applied === "now") text = "Saved and applied."
        else if (data && data.applied === "next") text = "Saved. Applies from the next connection."
        else if (data && data.applied === "restart") text = "Saved. Restart the service to apply it."
        else if (data && data.restarted) text = "Saved. The service restarted."
        if (text !== "") root.showMessage(text, false)
      } else {
        var err = String(actionErr.text || actionOut.text || "").trim().split("\n").pop()
        root.showMessage(err !== "" ? err : "Command failed", true)
      }
      root.refresh()
      root.refreshApps()
    }
  }

  BarIconButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: root.phoneIcon
    dimmed: !root.connected
    tooltipText: root.opened ? "" : root.tooltip()
    onPressed: function(buttonCode) {
      if (buttonCode === Qt.MiddleButton && root.connected)
        root.act("disconnect", [root.bin, "--disconnect"], "Disconnected")
      else root.toggle()
    }
  }

  KeyboardPanel {
    id: panel
    anchorItem: button
    owner: root
    bar: root.bar
    open: root.opened
    contentWidth: panel.fittedContentWidth(Style.space(380))
    contentHeight: panel.fittedContentHeight(column.implicitHeight, Style.space(640))

    Flickable {
      id: flick
      anchors.fill: parent
      contentWidth: width
      contentHeight: column.implicitHeight
      clip: true
      boundsBehavior: Flickable.StopAtBounds
      flickableDirection: Flickable.VerticalFlick
      interactive: contentHeight > height
      ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

      Column {
        id: column
        width: flick.width
        spacing: Style.space(10)

        // ---- status
        RowLayout {
          width: parent.width
          spacing: Style.space(12)

          Text {
            textFormat: Text.PlainText
            text: root.phoneIcon
            color: root.connected ? root.foreground : root.dim
            font.family: root.fontFamily
            font.pixelSize: Style.font.display
            Layout.alignment: Qt.AlignVCenter
          }

          ColumnLayout {
            Layout.fillWidth: true
            spacing: Style.space(2)

            Text {
              textFormat: Text.PlainText
              Layout.fillWidth: true
              text: "Hypr Screen"
              color: root.foreground
              font.family: root.fontFamily
              font.pixelSize: Style.font.subtitle
              font.bold: true
            }

            Text {
              textFormat: Text.PlainText
              Layout.fillWidth: true
              text: root.statusLine()
              color: root.dim
              font.family: root.fontFamily
              font.pixelSize: Style.font.bodySmall
              wrapMode: Text.WordWrap
            }
          }
        }

        Row {
          visible: root.connected !== null && root.connected !== undefined
          width: parent.width
          spacing: Style.space(8)

          Button {
            width: (parent.width - parent.spacing) / 2
            text: "Disconnect"
            iconText: "󰌸"
            enabled: !root.busy
            foreground: root.foreground
            fontFamily: root.fontFamily
            onClicked: root.act("disconnect", [root.bin, "--disconnect"], "Disconnected")
          }

          Button {
            width: (parent.width - parent.spacing) / 2
            text: "Move window here"
            iconText: "󰏌"
            enabled: !root.busy && !!(root.connected && !root.connected.mirror)
            foreground: root.foreground
            fontFamily: root.fontFamily
            onClicked: root.act("move", [root.bin, "--move-window"], "Moved the focused window to the phone")
          }
        }

        Text {
          textFormat: Text.PlainText
          visible: root.message !== ""
          width: parent.width
          text: root.message
          color: root.messageIsError ? root.urgent : root.dim
          font.family: root.fontFamily
          font.pixelSize: Style.font.bodySmall
          wrapMode: Text.WordWrap
        }

        // ---- app sound on the phone (while the phone plays laptop audio)
        PanelSeparator {
          visible: appSection.visible
          foreground: root.foreground
        }

        Column {
          id: appSection
          visible: !!(root.connected && root.connected.audio)
          width: parent.width
          spacing: Style.space(6)

          PanelSectionHeader {
            text: "PLAY ON PHONE"
            foreground: root.foreground
            fontFamily: root.fontFamily
          }

          Text {
            textFormat: Text.PlainText
            visible: root.apps.length === 0
            width: parent.width
            text: "No apps are playing sound."
            color: root.dim
            font.family: root.fontFamily
            font.pixelSize: Style.font.bodySmall
          }

          Repeater {
            model: root.apps
            Toggle {
              required property var modelData
              width: appSection.width
              label: String(modelData.name)
              description: String(modelData.title || "")
              checked: !!modelData.on_phone
              enabled: !root.busy
              foreground: root.foreground
              accent: root.accent
              fontFamily: root.fontFamily
              onClicked: root.act("audio", [root.bin, "--move-audio", String(modelData.id),
                                            modelData.on_phone ? "laptop" : "phone"], "")
            }
          }
        }

        // ---- paired phones
        PanelSeparator { foreground: root.foreground }

        PanelSectionHeader {
          text: "PAIRED OVER WI-FI"
          foreground: root.foreground
          fontFamily: root.fontFamily
        }

        Text {
          textFormat: Text.PlainText
          visible: root.devices.length === 0
          width: parent.width
          text: "No phones paired over Wi-Fi."
          color: root.dim
          font.family: root.fontFamily
          font.pixelSize: Style.font.bodySmall
        }

        Column {
          id: deviceColumn
          visible: root.devices.length > 0
          width: parent.width
          spacing: Style.space(4)

          Repeater {
            model: root.devices
            Item {
              id: deviceRow
              required property var modelData
              readonly property bool isConnected: !!(root.connected && root.connected.device === modelData.id)
              readonly property bool confirming: root.pendingForget === modelData.id
              width: deviceColumn.width
              implicitHeight: deviceText.implicitHeight + Style.space(8)

              ColumnLayout {
                id: deviceText
                anchors.left: parent.left
                anchors.right: forgetButton.left
                anchors.rightMargin: Style.space(8)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Style.space(1)

                Text {
                  textFormat: Text.PlainText
                  Layout.fillWidth: true
                  text: deviceRow.modelData.name + (deviceRow.isConnected ? "  · connected" : "")
                  color: root.foreground
                  font.family: root.fontFamily
                  font.pixelSize: Style.font.body
                  elide: Text.ElideRight
                }

                Text {
                  textFormat: Text.PlainText
                  Layout.fillWidth: true
                  text: deviceRow.confirming
                    ? "Click again to forget. It must pair again to connect."
                    : deviceRow.modelData.id + " · " + root.addedText(deviceRow.modelData.added)
                  color: deviceRow.confirming ? root.urgent : root.dim
                  font.family: root.fontFamily
                  font.pixelSize: Style.font.caption
                  elide: Text.ElideRight
                }
              }

              PanelActionButton {
                id: forgetButton
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                iconText: root.forgetIcon
                tooltipText: deviceRow.confirming ? "Click again to forget" : "Forget"
                foreground: deviceRow.confirming ? root.urgent : root.foreground
                hoverColor: root.urgent
                fontFamily: root.fontFamily
                enabled: !root.busy
                onClicked: {
                  if (deviceRow.confirming) {
                    root.pendingForget = ""
                    root.act("forget", [root.bin, "--forget", deviceRow.modelData.id],
                             "Forgot " + deviceRow.modelData.name)
                  } else {
                    root.pendingForget = deviceRow.modelData.id
                    forgetTimer.restart()
                  }
                }
              }
            }
          }
        }

        // ---- settings
        PanelSeparator { foreground: root.foreground }

        PanelSectionHeader {
          text: "SETTINGS"
          foreground: root.foreground
          fontFamily: root.fontFamily
        }

        Repeater {
          model: [
            { key: "wifi", label: "Wi-Fi", description: "Let paired phones connect over the network. Restarts the service, which drops a connected phone." },
            { key: "clipboard", label: "Shared clipboard", description: "Copy on one device, paste on the other." },
            { key: "audio", label: "Laptop sound on the phone", description: "Offer the Hypr Screen (phone) sound output." },
            { key: "mic", label: "Phone microphone", description: "Offer the phone's mic as an input for calls." }
          ]
          Toggle {
            required property var modelData
            readonly property bool wifiKey: modelData.key === "wifi"
            readonly property bool laptopOn: !!root.settingsState[modelData.key]
            // While a phone is connected: on only if its app has it on too (a sync-capable app follows our toggle).
            readonly property bool phoneOff: !wifiKey && !!root.connected && !!root.connected.phone
                                             && !root.connected.phone[modelData.key]
            width: column.width
            label: modelData.label
            description: laptopOn && phoneOff
              ? (root.connected.sync ? "Off in the phone app. Switch on to turn it on there too."
                                     : "Off in the phone app (update the app to switch it from here).")
              : modelData.description
            // Older apps can't be switched from here: then the toggle is the laptop's own setting.
            checked: laptopOn && !(phoneOff && root.connected.sync)
            enabled: !root.busy && root.st !== null && root.service !== "absent" && root.service !== "old"
            foreground: root.foreground
            accent: root.accent
            fontFamily: root.fontFamily
            onClicked: root.act("set", [root.bin, "--set", modelData.key, checked ? "off" : "on"], "")
          }
        }

        // ---- service
        PanelSeparator { foreground: root.foreground }

        RowLayout {
          width: parent.width
          spacing: Style.space(8)

          Text {
            textFormat: Text.PlainText
            Layout.fillWidth: true
            text: root.service === "absent" || root.service === "old"
              ? "See github.com/kpanuragh/hypr-screen"
              : root.service === "missing"
              ? "Run hypr-screen --install-service to set it up."
              : "Service " + root.service + (root.st && root.st.version ? " · " + root.st.version : "")
            color: root.dim
            font.family: root.fontFamily
            font.pixelSize: Style.font.bodySmall
            wrapMode: Text.WordWrap
          }

          Button {
            visible: root.service !== "missing" && root.service !== "absent" && root.service !== "old"
            text: root.serviceActive ? "Stop" : "Start"
            iconText: root.serviceActive ? "󰓛" : "󰐊"
            enabled: !root.busy
            foreground: root.foreground
            fontFamily: root.fontFamily
            onClicked: root.act("service", ["systemctl", "--user", root.serviceActive ? "stop" : "start",
                                            "hypr-screen.service"],
                                root.serviceActive ? "Service stopped" : "Service started")
          }
        }
      }
    }
  }
}
