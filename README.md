# Hypr Screen

Use your Android phone as an extra **Hyprland** monitor, with **touch** and **keyboard**, a bit like Apple Sidecar.

<p align="center">
  <a href="docs/demo.mp4"><img src="docs/demo.gif" alt="Hypr Screen demo: a phone used as a Hyprland monitor, typing with the phone keyboard and browsing" width="720"></a>
  <br><sub>Phone as a Hyprland monitor: touch, keyboard with Super/Ctrl/Alt row, browsing. <a href="docs/demo.mp4">Full video (MP4)</a></sub>
</p>

Plug the phone in over USB and it becomes a real Hyprland output next to your laptop screen. Drag windows onto it, move workspaces to it, tap and scroll with your fingers, and type with the phone keyboard. Unplug it and the monitor disappears. It also works over Wi-Fi, with no cable needed even for pairing.

## Features

- **Extended display, or a mirror.** Hyprland gets a real (headless) monitor at the phone's native resolution, with its own workspaces and bar. Or set **Mode → Mirror** on the phone to show a copy of your laptop screen, for example to show someone what you are doing.
- **Your workspaces, per phone.** Pick workspaces that always open on the phone (say, chat and music on 8 and 9), and a position for each phone.
- **Low-latency video.** Frames are captured with `wf-recorder`, hardware-encoded (VAAPI on Intel/AMD, NVENC on NVIDIA) and hardware-decoded on the phone. HEVC is used when both sides support it (about half the bandwidth of H.264). Only changed frames are sent, so an idle desktop uses almost no bandwidth.
- **Portrait or landscape.** Choose an orientation, or let the monitor auto-rotate with the phone. Hyprland gets a real portrait monitor, not a rotated picture.
- **Display settings on the phone.** Resolution, frame rate (a 50% / 30 fps battery saver), text size and codec.
- **Plug-and-play USB.** A background service notices the phone, opens the app and connects. Nothing to run by hand.
- **One tap on the phone.** Opening the app connects to the laptop you used last as soon as it is found on your network. A Quick Settings tile does the same from anywhere.
- **Bar widget and shortcuts.** Show the connection in Waybar or the Omarchy bar (with a panel for paired phones, settings and app sound), end it from the laptop, and send the focused window to the phone with a key.
- **Wi-Fi (opt-in).** The app discovers your laptop on the local network. Pair once by tapping **Pair** and accepting on the laptop (like Bluetooth), or just plug in USB once. On a slow network, quality and then frame rate step down automatically and come back when it recovers. If Wi-Fi drops, the phone reconnects by itself, and the laptop keeps its monitor (and your windows on it) for 15 seconds.
- **Secure by default.** Every connection is TLS 1.3 with a pinned certificate, and the phone must prove it holds its pairing key.
- **Multitouch.** A virtual touchscreen is mapped onto the phone's monitor, so you can tap, drag, scroll and pinch.
- **Stylus.** A pen (S Pen, USI) becomes a drawing tablet with pressure, tilt, eraser, side button and hover. It works in Krita, Xournal++, GIMP and other tablet-aware apps.
- **Trackpad mode.** Tap the 🖱 button to use the phone like a laptop touchpad for the whole desktop:
  - Tap to click; hold, then move to drag.
  - Two-finger tap for right-click; two-finger drag to scroll.
  - Three-finger tap for middle-click; three-finger swipe to switch workspace.
- **Touch as mouse.** For apps that ignore touch (kitty and other terminals), set **Touch input → Mouse** on the phone: tap to click, drag to select text, long-press or two-finger tap to right-click, two fingers to scroll.
- **Shared clipboard.** Text copied on the laptop can be pasted on the phone, and text copied on the phone reaches the laptop when you switch back to Hypr Screen (or tap 📋 on the key row). Passwords marked sensitive by a password manager are never shared. Turn it off with **Shared clipboard → Off** on the phone. Needs `wl-clipboard` on the laptop.
- **Laptop audio on the phone.** Turn on **Laptop audio → Play on the phone** and, while the phone is connected, the laptop gets an extra sound output called **Hypr Screen (phone)**. Move an app to it (pavucontrol, or `pactl move-sink-input`) and its sound plays on the phone; everything else stays on the laptop. WirePlumber remembers the choice for next time. Off by default. Needs `pactl` and `parec` (pipewire-pulse/libpulse).
- **Phone mic for calls.** Turn on **Phone mic** (it asks for the microphone permission once) and the laptop gets an input called **Hypr Screen (phone) mic**. Pick it in Meet, Zoom, Discord and so on. The phone records only while an app on the laptop is listening, using Android's call mode with echo cancelling; during that time laptop sound on the phone plays as call audio, through a plugged-in headset if there is one, else the loudspeaker. Anything that opens the input counts as listening, including level meters such as pavucontrol's Input Devices tab, so don't make it your default input unless you want the phone mic always on. Off by default. Needs `pw-cat` (pipewire).
- **Keyboard.** Type with the phone keyboard; the picture shrinks to stay visible above it. An extra key row gives `Esc Tab Ctrl Alt Super ← ↑ ↓ → 📋`, and the modifiers are sticky, so Super then 1 switches workspace. A Bluetooth keyboard paired to the phone is passed straight through.

## Requirements

**Laptop**
- Hyprland **0.56+**. Both the Lua config (`hyprctl eval`) and the legacy `hyprctl keyword` are supported.
- `wf-recorder`, `android-tools` (adb), `openssl`, Python 3 (standard library only)
- A GPU for hardware encoding: VAAPI (Intel, AMD) or NVENC (NVIDIA, proprietary driver). Intel is tested. The encoder is picked automatically: the configured one (`"encoder"`) is tried first, and if it fails to start, the others follow (VAAPI → NVENC → software x264).
- Write access to `/dev/uinput` for touch and keyboard. Desktop sessions usually get this through logind. Check with `getfacl /dev/uinput`.
- Optional: `wl-clipboard`, for the shared clipboard; `wtype`, for typing characters outside the US layout.

**Phone**
- Android 11+ with **USB debugging** enabled (Developer options).

## Install

### 1. Laptop

Install the dependencies (Arch / Omarchy):

```sh
sudo pacman -S --needed wf-recorder android-tools openssl
```

Then install with **mise**:

```sh
mise use -g github:kpanuragh/hypr-screen
hypr-screen --install-service
```

mise waits 24 hours before offering a new release (its `minimum_release_age` setting). To install a release on its first day, pin it, for example `github:kpanuragh/hypr-screen@0.2.0`.

Or from a git checkout:

```sh
git clone https://github.com/kpanuragh/hypr-screen
cd hypr-screen/host
./install.sh
```

Either way, this starts the `hypr-screen` systemd user service, which comes up with your graphical session. Run `hypr-screen --uninstall-service` to remove it.

### 2. Phone

Install the app in one of these ways:

- **Obtainium:** add `https://github.com/kpanuragh/hypr-screen` to get the APK from each new release, with updates.
- **F-Droid:** planned. F-Droid only accepts apps that have been maintained for 3 months, so the request goes in from February 2027. The F-Droid metadata is already in this repo.
- **Direct download:** `hypr-screen-*.apk` from [Releases](../../releases), or `adb install hypr-screen-*.apk`.
- **Build it yourself** (see below).

Release APKs are signed with this certificate (SHA-256):
`bd:84:9d:c5:43:25:28:a0:1d:9d:bb:c4:3b:1d:06:d1:06:40:6e:8c:27:f5:0c:4c:66:1a:8d:4e:83:3a:6d:e1`

### 3. Connect

- **USB:** plug the phone in and accept the USB debugging prompt. The app opens and the phone becomes the monitor to the right of your screen.
- **Wi-Fi:** off by default. Turn it on with `hypr-screen --set wifi on` (or the Wi-Fi toggle in the Omarchy bar widget), which restarts the service. If you use `ufw`, allow only your local network, for example:
  ```sh
  sudo ufw allow from 192.168.1.0/24 to any port 7878
  ```
  Open the app on the same network and tap **Pair** next to your laptop. The phone shows a 6-digit code and a window opens on the laptop: type the code there, press **Enter**, then tap **Confirm** on the phone. After that, tap **Connect** whenever you want the extra monitor.

  Manage Wi-Fi-paired phones with `hypr-screen --devices` and `hypr-screen --forget <id>` (this also ends its session if it is connected). On the phone, **Paired laptops** in the settings lists your laptops, with **Forget**.

On the phone, the **⌨** button opens the keyboard (drag it to move it). **Back** closes the keyboard; pressing Back again disconnects.

To connect from anywhere on the phone, add the **Hypr Screen** tile: pull down Quick Settings, tap the edit (pencil) button and drag the tile in. Tapping it opens the app and connects to the last laptop. Turn off **Auto-connect** in the app if you'd rather tap **Connect** yourself.

### 4. Laptop shortcuts and bar widget

```sh
hypr-screen --status        # JSON for Waybar
hypr-screen --disconnect    # end the session (the phone doesn't reconnect)
hypr-screen --move-window   # send the focused window to the phone
```

Bind a key to move windows. On Omarchy, add this to `~/.config/hypr/bindings.lua`:

```lua
o.bind("SUPER + SHIFT + P", "Move window to phone", "hypr-screen --move-window")
```

**Omarchy bar:** `host/install.sh` adds a Hypr Screen widget to the bar when it finds Omarchy (with mise or the release tarball, run `hypr-screen --install-omarchy`). The phone icon is dimmed while no phone is connected. Click it for a panel to:

- see the connected phone, disconnect it, or move the focused window to it (middle-click the icon also disconnects);
- forget phones paired over Wi-Fi;
- turn Wi-Fi, the shared clipboard, laptop sound and the phone mic on or off;
- while the phone plays laptop sound, choose which apps play on it;
- start or stop the service.

To hide the icon while idle, set `"hideWhenIdle": true` on its entry in `bar.layout` of `~/.config/omarchy/shell.json`. Remove it with `omarchy plugin remove kpanuragh.hypr-screen`. On other desktops nothing is installed; use Waybar below. The widget uses these commands, which are handy in scripts too:

```sh
hypr-screen --state                 # service, connected phone, paired phones, settings (JSON)
hypr-screen --set mic off           # wifi, clipboard, audio, mic: on/off (wifi restarts the service)
hypr-screen --audio-apps            # apps playing sound (JSON)
hypr-screen --move-audio ID phone   # or laptop
```

**Waybar:**

```json
"custom/hypr-screen": {
  "exec": "hypr-screen --status", "return-type": "json", "interval": 5,
  "on-click": "hypr-screen --disconnect"
}
```

The widget shows a phone icon while a phone is connected (class `connected` or `mirror`) and nothing otherwise (`idle`, or `stopped` when the service isn't running).

## Configuration

`~/.config/hypr-screen/config.json` is created on first run:

| Key | Default | Meaning |
|---|---|---|
| `scale` | `1.5` | Hyprland scale for the phone output |
| `position` | `"auto-right"` | Monitor position (`auto-left`, `0x1080`, …) |
| `fps` | `60` | Refresh rate of the virtual monitor |
| `qp` | `24` | Quality: lower is sharper and uses more bandwidth |
| `encoder` | `"vaapi"` | Encoder to try first: `vaapi`, `nvenc` (NVIDIA) or `x264` (software). Others are tried if it fails |
| `device` | `"/dev/dri/renderD128"` | VAAPI render node |
| `auto_usb` | `true` | Connect automatically when a phone is plugged in |
| `wifi` | `false` | Also listen on the network (Wi-Fi mode) |
| `wifi_pairing` | `true` | Allow pairing requests over Wi-Fi (they still need your approval) |
| `codec` | `"auto"` | `auto` (HEVC when the phone supports it), `h264` or `hevc` |
| `adaptive` | `true` | On Wi-Fi, lower quality/frame rate while the link can't keep up |
| `workspaces` | `[]` | Workspaces to put on the phone while it is connected, e.g. `[8, 9]` or `["name:chat"]` |
| `mirror_output` | `""` | Monitor shown in mirror mode (`""` = the focused one), e.g. `"eDP-1"` |
| `clipboard` | `true` | Share clipboard text with phones that turn it on (needs `wl-clipboard`) |
| `audio` | `true` | Offer the Hypr Screen (phone) sound output to phones that turn audio on |
| `mic` | `true` | Offer the Hypr Screen (phone) mic input to phones that turn the mic on |

`clipboard`, `audio` and `mic` are kept in step with the phone app (0.8.0 on both sides): when a phone connects, the laptop takes the app's three settings, and switching one on the laptop while it is connected (`hypr-screen --set`, or the Omarchy bar widget) also switches it in the app. They apply at once, without reconnecting.
| `phones` | `{}` | Per-phone `position` and `workspaces`, keyed by the id from `--devices` (or `"usb"`) |

Example for a phone that sits below the laptop screen and gets workspace 9:

```json
"phones": { "3f9a1c2b7d10": { "position": "auto-down", "workspaces": [9] } }
```

Workspace rules stay in Hyprland until it reloads its config, so after removing a workspace from this list, reload Hyprland (or log out and in).

The phone's own Display settings (resolution, frame rate, text size, codec) override `fps` and `scale` for that phone. Restart after editing: `systemctl --user restart hypr-screen`. Logs: `journalctl --user -u hypr-screen -f`.

## How it works

```
Hyprland ── headless output "HYPRSCREEN" ── wf-recorder (H.264/HEVC via VAAPI or NVENC, FLV)
                                                   │
                                hypr-screen service (Python)
                      FLV → framed H.264 ──────────┼──────────► phone: MediaCodec → SurfaceView
          uinput touchscreen + keyboard ◄──────────┴────────── phone: touch / key events
                                         TCP 7878 (adb reverse over USB, or Wi-Fi)
```

- **USB** uses `adb reverse`, so the phone connects to `127.0.0.1:7878` and the traffic never touches the network.
- **Wi-Fi** discovery is a UDP broadcast on port 7878.

### Security

- **USB pairing.** When a phone is plugged in, the service uses adb to write a pairing record (host id, secret key, certificate fingerprint) into the app's private storage (`Android/data/com.hyprscreen`), which other apps can't read.
- **Wi-Fi pairing.** Works like Bluetooth pairing. The phone shows a 6-digit code computed from the laptop's certificate fingerprint and a random value from each side. The phone commits to its value before it sees the laptop's, so a man in the middle can't make the code match. You type the code on the laptop (a stray keypress can't accept a request). The laptop sends a new per-phone secret only after you accept on **both** the laptop and the phone. Requests are refused while Wi-Fi pairing is off, only one can be open at a time, and a rejected request blocks new ones for 10 seconds.
- **Encryption.** Every connection, USB included, is **TLS 1.3**. The phone pins the laptop's self-signed certificate, so a fake laptop on the network can't impersonate yours, and video and keystrokes can't be read or altered in transit.
- **Authentication.** After TLS, the phone answers a fresh challenge with `HMAC-SHA256(secret, server_nonce | client_nonce)`. Unpaired connections are rejected, including other local processes on the laptop and other apps on the phone.
- **Input safety.** Power, sleep, suspend and SysRq keys can never be sent from the phone.
- **Files.** Keys live in `~/.config/hypr-screen/` (`identity.json`, `key.pem`, mode 600). Wi-Fi-paired phones are in `devices.json`; remove one with `hypr-screen --forget <id>`. Delete the files and restart the service to revoke every paired phone. The app disables Android backups of its pairing data.

## Build from source

```sh
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

This needs JDK 17+ and the Android SDK (platform 36), with the SDK path in `ANDROID_HOME` or `local.properties`.

## Limitations

- Only one phone at a time.
- A floating phone keyboard still floats over the picture (drag it aside, or use a docked keyboard, which the picture shrinks to make room for).
- Characters outside the US layout are typed through `wtype`, one at a time.
- Laptop sound plays on the phone only for the apps you send there (the Omarchy bar widget, pavucontrol or `pactl`); everything else stays on the laptop. The phone mic is used only by apps that pick the Hypr Screen (phone) mic input.
- Mirror mode sends the mirrored monitor at its full resolution (up to 4096 pixels wide); the phone's Resolution setting doesn't apply to it.
- Tested on: Omarchy, Hyprland 0.56, Intel Iris Xe, OnePlus (Android 17). Reports from other setups are welcome.

## License

MIT
