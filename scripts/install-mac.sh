#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Aura VPN Desktop — macOS installer (curl | sh)
# ==============================================================================
# Downloads the latest .dmg from GitHub Releases, installs the app into
# /Applications, and clears the quarantine flag Gatekeeper would otherwise
# attach to a downloaded, unsigned/non-notarized app (desktop/README.md:
# electron-builder runs with mac.notarize: false — no Apple Developer
# Program cert exists in this repo). Without this, launching the .app for
# the first time shows "Apple could not verify... is free of malware" and
# forces a right-click → Open workaround; this script does the equivalent
# of that consent up front, non-interactively, the same way Homebrew casks
# do for unsigned formulae.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/roman-struchev/vpn/main/scripts/install-mac.sh | sh
#
# Also the macOS half of the in-app updater (desktop/src/main/autoUpdater.ts):
# electron-updater's own macOS installer (Squirrel.Mac) refuses unsigned
# apps, so the app runs this copy of the script — bundled in its Resources,
# not fetched from raw.githubusercontent.com, which is often blocked exactly
# where this app is used — with:
#   AURA_VPN_MANAGED_RELAUNCH=1  the app relaunches itself afterwards: don't
#                                quit or `open` it, and never prompt (no sudo)
#   AURA_VPN_INSTALL_DIR=<dir>   where the running app actually lives
# and https_proxy set to its own tunnel when the VPN is up.

REPO="roman-struchev/vpn"
APP_NAME="Aura VPN.app"
INSTALL_DIR="${AURA_VPN_INSTALL_DIR:-/Applications}"
MANAGED="${AURA_VPN_MANAGED_RELAUNCH:-}"

if [ "$(uname -s)" != "Darwin" ]; then
    echo "ERROR: this installer is for macOS only." >&2
    exit 1
fi

case "$(uname -m)" in
    arm64) ARCH_TAG="arm64" ;;
    x86_64) ARCH_TAG="x64" ;;
    *)
        echo "ERROR: unrecognized architecture $(uname -m)." >&2
        exit 1
        ;;
esac

echo "==> [1/5] Looking up the latest release for macOS (${ARCH_TAG})..."
RELEASE_JSON=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest")
DMG_URL=$(echo "$RELEASE_JSON" \
    | grep -o "\"browser_download_url\":[[:space:]]*\"[^\"]*mac-${ARCH_TAG}[^\"]*\.dmg\"" \
    | head -1 \
    | sed -E 's/.*"(https[^"]+)"/\1/')

if [ -z "$DMG_URL" ]; then
    echo "ERROR: no macOS ${ARCH_TAG} .dmg found in the latest release." >&2
    echo "        Check https://github.com/${REPO}/releases/latest manually." >&2
    exit 1
fi

DMG_NAME=$(basename "$DMG_URL")
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
DMG_PATH="${TMP_DIR}/${DMG_NAME}"

echo "==> [2/5] Downloading ${DMG_NAME}..."
if [ -n "$MANAGED" ]; then
    # The meter (on stderr) is what the app turns into a percentage.
    curl -fL --progress-bar -o "$DMG_PATH" "$DMG_URL"
else
    curl -fsSL -o "$DMG_PATH" "$DMG_URL"
fi

echo "==> [3/5] Mounting disk image..."
MOUNT_POINT=$(hdiutil attach "$DMG_PATH" -nobrowse -readonly | awk -F'\t' '/\/Volumes\// {print $NF; exit}')
if [ -z "$MOUNT_POINT" ] || [ ! -d "${MOUNT_POINT}/${APP_NAME}" ]; then
    echo "ERROR: couldn't find ${APP_NAME} inside the mounted image." >&2
    exit 1
fi

echo "==> [4/5] Installing into ${INSTALL_DIR}..."
# Replacing a running bundle is fine on macOS (the live process keeps its
# open files); in managed mode the app relaunches into the new one itself.
if [ -n "$MANAGED" ] && [ ! -w "$INSTALL_DIR" ]; then
    # No terminal to answer a sudo prompt: it would hang the update forever.
    echo "ERROR: no write access to ${INSTALL_DIR}; update from the Terminal instead." >&2
    hdiutil detach "$MOUNT_POINT" -quiet || true
    exit 2
fi
if [ -d "${INSTALL_DIR}/${APP_NAME}" ]; then
    rm -rf "${INSTALL_DIR:?}/${APP_NAME}" 2>/dev/null || sudo rm -rf "${INSTALL_DIR:?}/${APP_NAME}"
fi
if ! ditto "${MOUNT_POINT}/${APP_NAME}" "${INSTALL_DIR}/${APP_NAME}" 2>/dev/null; then
    echo "    No write access to ${INSTALL_DIR} without sudo — retrying with sudo..."
    sudo ditto "${MOUNT_POINT}/${APP_NAME}" "${INSTALL_DIR}/${APP_NAME}"
fi
hdiutil detach "$MOUNT_POINT" -quiet

echo "==> [5/5] Clearing the quarantine flag (this build isn't notarized by Apple)..."
if ! xattr -cr "${INSTALL_DIR}/${APP_NAME}" 2>/dev/null; then
    sudo xattr -cr "${INSTALL_DIR}/${APP_NAME}"
fi

# An in-place replace keeps the bundle path, so LaunchServices/Dock keep the
# icon they cached for the previous build (e.g. the stock Electron one from
# before the app had its own) — bump the mtime and re-register to refresh it.
touch "${INSTALL_DIR}/${APP_NAME}" 2>/dev/null || true
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
    -f "${INSTALL_DIR}/${APP_NAME}" >/dev/null 2>&1 || true

if [ -n "$MANAGED" ]; then
    echo "==> Done. Aura VPN will relaunch itself."
else
    echo "==> Done. Launch it from ${INSTALL_DIR}/${APP_NAME} or Spotlight (⌘Space → \"Aura VPN\")."
fi
