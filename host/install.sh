#!/usr/bin/env bash
# Install the hypr-screen laptop service for the current user (from a git checkout).
# With mise instead:  mise use -g github:kpanuragh/hypr-screen && hypr-screen --install-service
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

install -Dm755 "$here/hypr-screen" "$HOME/.local/bin/hypr-screen"
"$HOME/.local/bin/hypr-screen" --install-service

# Omarchy: the bar widget (other desktops: nothing to do)
if command -v omarchy >/dev/null && command -v omarchy-shell >/dev/null; then
  plugin_src="$HOME/.local/share/hypr-screen/omarchy"
  install -d "$plugin_src"
  find "$here/../omarchy" -maxdepth 1 -type f -exec install -m644 {} "$plugin_src/" \;  # bin/ is written by --install-omarchy
  "$HOME/.local/bin/hypr-screen" --install-omarchy || echo "The bar widget wasn't installed; the service is. Retry: hypr-screen --install-omarchy"
fi
