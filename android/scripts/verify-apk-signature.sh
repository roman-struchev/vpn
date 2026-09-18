#!/usr/bin/env bash
# Fails unless an APK is signed by the project's one release certificate.
#
# Why this is worth a build gate: an APK signed by any other key still
# installs fine on a clean device and looks completely healthy in CI — the
# breakage only shows up later, on a phone that already has the app, as
# "App not installed as package conflicts with an existing package", with no
# way out but uninstalling (losing the login and every local setting). That
# is exactly what shipped for months while release builds fell back to an
# auto-generated debug keystore that differed per CI run.
#
# The pinned value is a certificate fingerprint: public information (every
# installed copy of the APK carries it), unlike the keystore and its password.
#
# Usage: android/scripts/verify-apk-signature.sh <path-to-apk> [expected-sha256]
set -euo pipefail

# SHA-256 of the release signing certificate (keystore: vpn-release.jks,
# alias "vpn"). Change this only when deliberately rotating the signing key —
# which forces every existing install to be uninstalled first, so it should
# essentially never happen.
EXPECTED_DEFAULT="9c32a1ec423e291d0a8adea39d991e35e0f9cbd101f52a2d8aa9900b8bde6f3a"

APK="${1:-}"
EXPECTED="${2:-$EXPECTED_DEFAULT}"

if [ -z "$APK" ]; then
    echo "usage: $0 <path-to-apk> [expected-sha256]" >&2
    exit 2
fi
if [ ! -f "$APK" ]; then
    echo "ERROR: no such APK: $APK" >&2
    exit 2
fi

# apksigner lives in a versioned build-tools dir and is not on PATH by
# default; take the highest version available under the SDK.
find_apksigner() {
    if command -v apksigner >/dev/null 2>&1; then
        command -v apksigner
        return 0
    fi
    local sdk
    for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/local/lib/android/sdk"; do
        [ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || continue
        local candidate
        candidate="$(find "$sdk/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -n 1)"
        if [ -n "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}

APKSIGNER="$(find_apksigner)" || {
    echo "ERROR: apksigner not found (set ANDROID_SDK_ROOT or install Android build-tools)." >&2
    exit 2
}

# Digest lines are labelled differently across build-tools versions
# ("Signer #1 certificate SHA-256 digest:" up to 36, "V2 Signer: certificate
# SHA-256 digest:" in 37) and one key is reported once per signature scheme
# it signed with (v1/v2/v3), so match on the common substring and de-duplicate.
VERIFY_OUTPUT="$("$APKSIGNER" verify --print-certs "$APK" 2>&1)" || {
    echo "ERROR: apksigner could not verify $APK:" >&2
    echo "$VERIFY_OUTPUT" >&2
    exit 1
}
ACTUAL="$(printf '%s\n' "$VERIFY_OUTPUT" \
    | awk -F': ' '/certificate SHA-256 digest/ { print tolower($NF) }' \
    | tr -d '[:blank:]' | sort -u)"

if [ -z "$ACTUAL" ]; then
    echo "ERROR: $APK is not signed (no signer certificate found)." >&2
    exit 1
fi
if [ "$(printf '%s\n' "$ACTUAL" | wc -l | tr -d ' ')" != "1" ]; then
    echo "ERROR: $APK is signed by more than one certificate:" >&2
    printf '  %s\n' $ACTUAL >&2
    exit 1
fi

if [ "$ACTUAL" != "$EXPECTED" ]; then
    cat >&2 <<EOF
ERROR: $(basename "$APK") is signed with the WRONG certificate.
  expected: $EXPECTED
  actual:   $ACTUAL
Publishing it would make the app un-updatable for everyone who already has it
installed ("package conflicts with an existing package"). Build with the
release keystore configured — see android/app/build.gradle's signingConfigs.
EOF
    exit 1
fi

echo "OK: $(basename "$APK") is signed with the expected release certificate ($ACTUAL)."
