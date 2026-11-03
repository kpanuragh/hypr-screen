# Hypr Screen

Use your Android phone as an extra **Hyprland** monitor, with **touch** and **keyboard**, a bit like Apple Sidecar.

Plug the phone in over USB and it becomes a real Hyprland output next to your laptop screen. Drag windows onto it, move workspaces to it, tap and scroll with your fingers, and type with the phone keyboard. Unplug it and the monitor disappears. It also works over Wi-Fi after one USB connection.

## Features

- **Extended display, not a mirror.** Hyprland gets a real (headless) monitor at the phone's native resolution, with its own workspaces and bar.
- **Low-latency video.** Frames are captured with `wf-recorder`, hardware-encoded to H.264 (VAAPI) and hardware-decoded on the phone. Only changed frames are sent, so an idle desktop uses almost no bandwidth.
- **Plug-and-play USB.** A background service notices the phone, opens the app and connects. Nothing to run by hand.
- **Wi-Fi (opt-in).** The app discovers your laptop on the local network. Pairing happens automatically over USB.
- **Secure by default.** Every connection is TLS 1.3 with a pinned certificate, and the phone must prove it holds a pairing key that only ever travels over adb.
- **Multitouch.** A virtual touchscreen is mapped onto the phone's monitor, so you can tap, drag, scroll and pinch.
- **Keyboard.** Type with the phone keyboard. An extra key row gives `Esc Tab Ctrl Alt Super ← ↑ ↓ →`, and the modifiers are sticky, so Super then 1 switches workspace. A Bluetooth keyboard paired to the phone is passed straight through.

## Requirements

**Laptop**
- Hyprland **0.56+**. Both the Lua config (`hyprctl eval`) and the legacy `hyprctl keyword` are supported.
- `wf-recorder`, `android-tools` (adb), `openssl`, Python 3 (standard library only)
- A VAAPI-capable GPU for hardware encoding. Intel is tested; others should work. Set `"encoder": "x264"` for software encoding.
- Write access to `/dev/uinput` for touch and keyboard. Desktop sessions usually get this through logind. Check with `getfacl /dev/uinput`.
- Optional: `wtype`, for typing characters outside the US layout.

**Phone**
- Android 11+ with **USB debugging** enabled (Developer options).

## Install

### 1. Laptop

```sh
sudo pacman -S --needed wf-recorder android-tools openssl   # Arch / Omarchy
git clone https://github.com/kpanuragh/hypr-screen
cd hypr-screen/host
./install.sh
```

This installs `~/.local/bin/hypr-screen` and starts the `hypr-screen.service` user unit, which comes up with your graphical session.

### 2. Phone

Download `hypr-screen.apk` from [Releases](../../releases) and install it, or build it yourself (see below). If you install over adb, run `adb install hypr-screen.apk`.

### 3. Connect

- **USB:** plug the phone in and accept the USB debugging prompt. The app opens and the phone becomes the monitor to the right of your screen.
- **Wi-Fi:** off by default. Set `"wifi": true` in the config and restart the service. After one USB connection, open the app on the same network and tap your laptop. If you use `ufw`, allow only your local network, for example:
  ```sh
  sudo ufw allow from 192.168.1.0/24 to any port 7878
  ```

On the phone, the **⌨** button opens the keyboard (drag it to move it). **Back** closes the keyboard; pressing Back again disconnects.

## Configuration

`~/.config/hypr-screen/config.json` is created on first run:

| Key | Default | Meaning |
|---|---|---|
| `scale` | `1.5` | Hyprland scale for the phone output |
| `position` | `"auto-right"` | Monitor position (`auto-left`, `0x1080`, …) |
| `fps` | `60` | Refresh rate of the virtual monitor |
| `qp` | `24` | Quality: lower is sharper and uses more bandwidth |
| `encoder` | `"vaapi"` | `vaapi` or `x264` |
| `device` | `"/dev/dri/renderD128"` | VAAPI render node |
| `auto_usb` | `true` | Connect automatically when a phone is plugged in |
| `wifi` | `false` | Also listen on the network (Wi-Fi mode) |

Restart after editing: `systemctl --user restart hypr-screen`. Logs: `journalctl --user -u hypr-screen -f`.

## How it works

```
Hyprland ── headless output "HYPRSCREEN" ── wf-recorder (H.264/VAAPI, FLV)
                                                   │
                                hypr-screen service (Python)
                      FLV → framed H.264 ──────────┼──────────► phone: MediaCodec → SurfaceView
          uinput touchscreen + keyboard ◄──────────┴────────── phone: touch / key events
                                         TCP 7878 (adb reverse over USB, or Wi-Fi)
```

- **USB** uses `adb reverse`, so the phone connects to `127.0.0.1:7878` and the traffic never touches the network.
- **Wi-Fi** discovery is a UDP broadcast on port 7878.

### Security

- **Pairing.** When a phone is plugged in, the service uses adb to write a pairing record (host id, secret key, certificate fingerprint) into the app's private storage (`Android/data/com.hyprscreen`), which other apps can't read. The secret is never sent over a socket.
- **Encryption.** Every connection, USB included, is **TLS 1.3**. The phone pins the laptop's self-signed certificate, so a fake laptop on the network can't impersonate yours, and video and keystrokes can't be read or altered in transit.
- **Authentication.** After TLS, the phone answers a fresh challenge with `HMAC-SHA256(secret, server_nonce | client_nonce)`. Unpaired connections are rejected, including other local processes on the laptop and other apps on the phone.
- **Input safety.** Power, sleep, suspend and SysRq keys can never be sent from the phone.
- **Files.** Keys live in `~/.config/hypr-screen/` (`identity.json`, `key.pem`, mode 600). Delete them and restart the service to revoke every paired phone. The app disables Android backups of its pairing data.

## Build from source

```sh
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

This needs JDK 17+ and the Android SDK (platform 36), with the SDK path in `ANDROID_HOME` or `local.properties`.

## Limitations

- Only one phone at a time.
- While the phone keyboard is open, it covers part of the picture.
- Characters outside the US layout are typed through `wtype`, one at a time.
- No audio. Audio keeps playing on the laptop.
- Tested on: Omarchy, Hyprland 0.56, Intel Iris Xe, OnePlus (Android 17). Reports from other setups are welcome.

## License

MIT
