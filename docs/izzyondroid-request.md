# IzzyOnDroid inclusion request (draft)

Open https://codeberg.org/IzzyOnDroid/repodata/issues/new, choose "App Inclusion Request", and fill in:

**Title:** `[AppRequest] Hypr Screen`

**Guidelines:** tick all four. You are the developer, the app follows the policy, it isn't listed yet, and the Fastlane folder exists.

**Link to the source code:** https://github.com/kpanuragh/hypr-screen

**Link to app in another app store:** leave empty. F-Droid closed the request only because the app is under 3 months old: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/55602

**License used:** MIT

**Categories:** Connectivity, System

**Summary:** Use your Android phone as an extra Hyprland monitor, with touch and keyboard input.

**Description:**
Hypr Screen turns an Android phone into a real extra monitor for the Hyprland Wayland compositor on Linux, similar to Apple Sidecar. A small service on the computer (same repo) creates a headless Hyprland output at the phone's resolution and streams it as hardware-encoded H.264; the app decodes it with MediaCodec. Touch and keyboard input go back to the computer as a virtual touchscreen and keyboard.
- Works over USB (adb reverse) or Wi-Fi; Wi-Fi pairing by typing a 6-digit code on the computer
- Multitouch, plus an Esc/Tab/Ctrl/Alt/Super/arrow key row
- TLS 1.3 with a pinned certificate and per-device keys
- No accounts, ads, analytics or Google libraries; only the INTERNET permission

APKs are attached to GitHub releases (`hypr-screen-<version>.apk`), signed with the key whose certificate SHA-256 is
`bd849dc5432528a01d9dbbc43b1d06d106406e8c27f50c4c661a8d4e833a6de1`.

**Build instructions:**
```
git clone https://github.com/kpanuragh/hypr-screen
cd hypr-screen/android
./gradlew assembleRelease   # JDK 17+, Android SDK platform 36
# output: app/build/outputs/apk/release/app-release-unsigned.apk
```
A clean build of tag v0.2.0 matches the released APK, so the build is reproducible.

**Assistance Level:** choose the option for extensive or most of the code.

**"AI" Tool(s):** Claude (Claude Code)

**What did the tools help with, and how?**
Claude wrote most of the code (Android app and Linux service), the protocol and security design, and the documentation, working from my requirements. It also reviewed the security of the pairing protocol. I directed the features and tested each step on my own phone and laptop.

**AI Accountability:** tick these only if they are true for you.

**Further Notices:** The app needs the companion Linux service from the same repository (Hyprland 0.56+).
