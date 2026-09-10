#!/usr/bin/env bash
# Downloads the prebuilt XTLS/libXray Android artifact and drops it into
# android/app/libs/libXray.aar. Not run automatically by Gradle: the .aar is
# ~95 MB of native Go code for 4 ABIs and is intentionally not committed to
# git (see android/.gitignore). Run this once before building the app module.
#
# See docs/PLAN.md §5: "готовый .aar из релизов libXray — обычная
# Java-зависимость".
set -euo pipefail

VERSION="${LIBXRAY_VERSION:-v26.9.9}"
REPO="XTLS/libXray"
ASSET="libxray-android.zip"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_LIBS_DIR="$SCRIPT_DIR/../app/libs"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

URL="https://github.com/${REPO}/releases/download/${VERSION}/${ASSET}"
echo "Downloading libXray ${VERSION} from ${URL} ..."
curl -sL -o "$TMP_DIR/$ASSET" "$URL"

unzip -oq "$TMP_DIR/$ASSET" -d "$TMP_DIR/extracted"
AAR_PATH="$(find "$TMP_DIR/extracted" -name 'libXray.aar' | head -n1)"
if [ -z "$AAR_PATH" ]; then
  echo "libXray.aar not found in downloaded archive" >&2
  exit 1
fi

mkdir -p "$APP_LIBS_DIR"
cp "$AAR_PATH" "$APP_LIBS_DIR/libXray.aar"
echo "Installed $APP_LIBS_DIR/libXray.aar ($(du -h "$APP_LIBS_DIR/libXray.aar" | cut -f1))"
echo "libXray API version bound by this app: 3 (see XrayInvoker.API_VERSION). Re-check docs/PLAN.md if you bump LIBXRAY_VERSION."
