#!/usr/bin/env bash
#
# Render all web README screenshots from the real app (issue #300).
# Builds + serves web/ (production preview by default) and drives it with
# Playwright, writing 1440×920 @2× PNGs into docs/screenshots/.
#
# Usage (from anywhere in the repo):
#   scripts/screenshots/render-web.sh                 # all web shots
#   scripts/screenshots/render-web.sh web-rezepte     # one shot (test title)
#   SCREENSHOT_SERVER=dev scripts/screenshots/render-web.sh   # use the dev server
#
# Requirements: Node + npm. The script installs web/ deps and the Chromium
# Playwright browser on first run.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WEB_DIR="$REPO_ROOT/web"
CONFIG="$SCRIPT_DIR/playwright.screenshots.config.ts"

cd "$WEB_DIR"

if [ ! -d node_modules ]; then
  echo "Installing web dependencies…"
  npm install
fi

# Ensure the Chromium browser Playwright needs is present (no-op if cached).
npx playwright install chromium

# Pass any args through as a --grep filter so `… render-web.sh web-rezepte`
# renders just that shot (matches the test title).
GREP_ARGS=()
if [ "$#" -gt 0 ]; then
  GREP_ARGS=(--grep "$*")
fi

# The config + spec live outside web/ (in scripts/screenshots/), so Node can't
# walk up into web/node_modules to resolve @playwright/test / the e2e helpers.
# NODE_PATH points the resolver at web/node_modules.
export NODE_PATH="$WEB_DIR/node_modules"

echo "Rendering web screenshots → $REPO_ROOT/docs/screenshots/"
npx playwright test -c "$CONFIG" "${GREP_ARGS[@]}"
echo "Done."
