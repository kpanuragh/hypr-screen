#!/usr/bin/env bash
# Install the hypr-screen laptop service for the current user.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

for tool in hyprctl wf-recorder adb openssl; do
  command -v "$tool" >/dev/null || { echo "missing: $tool (sudo pacman -S ${tool/adb/android-tools})"; exit 1; }
done

install -Dm755 "$here/hypr-screen" "$HOME/.local/bin/hypr-screen"
install -Dm644 "$here/hypr-screen.service" "$HOME/.config/systemd/user/hypr-screen.service"
systemctl --user daemon-reload
systemctl --user enable --now hypr-screen.service
systemctl --user restart hypr-screen.service
echo "hypr-screen service installed and running."

echo
echo "USB works now: plug in your phone (USB debugging on) with the Hypr Screen app installed."
echo "Wi-Fi is off by default: set \"wifi\": true in ~/.config/hypr-screen/config.json,"
echo "restart (systemctl --user restart hypr-screen) and, with ufw, allow your LAN only, e.g.:"
echo "  sudo ufw allow from 192.168.1.0/24 to any port 7878"
