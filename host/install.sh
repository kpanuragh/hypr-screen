#!/usr/bin/env bash
# Install the hypr-screen laptop service for the current user (from a git checkout).
# With mise instead:  mise use -g github:kpanuragh/hypr-screen && hypr-screen --install-service
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

install -Dm755 "$here/hypr-screen" "$HOME/.local/bin/hypr-screen"
exec "$HOME/.local/bin/hypr-screen" --install-service
