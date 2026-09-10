#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Release cutter
# ==============================================================================
# The single source of truth for the release version is the root VERSION
# file (semver, no leading "v"). This script bumps it, commits the bump,
# tags the commit `v<version>`, and pushes both. Pushing a `v*` tag is what
# triggers .github/workflows/release.yml, which builds and publishes:
#   - the server Docker image (server/, Dockerfile)
#   - the VPN node/agent Docker image (agent/, agent/Dockerfile)
#   - an Android APK (android/) attached to a GitHub Release
#   - a macOS DMG (desktop/) attached to the same GitHub Release
# all tagged/versioned from this same VERSION file — CI injects it into
# agent/package.json, desktop/package.json and the Android versionName/Code
# at build time (see release.yml), so those per-project files never need to
# be hand-edited or committed on every release.
#
# Usage: scripts/release.sh [major|minor|patch]   (default: patch)
#    or: npm run release [-- major|minor|patch]   (convenience wrapper, see
#        root package.json — same idea as aura-pad's `npm run release`)
# Mirrors the release flow of https://github.com/roman-struchev/aura-pad
# (scripts/release.sh + .github/workflows/build.yml there), adapted for a
# multi-language monorepo with a shared VERSION file instead of one
# package.json.

cd "$(git rev-parse --show-toplevel)"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit or stash changes first." >&2
  exit 1
fi

BUMP="${1:-patch}"
CURRENT="$(cat VERSION)"
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT"

case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
  *)
    echo "Usage: $0 [major|minor|patch]" >&2
    exit 1
    ;;
esac

VERSION="$MAJOR.$MINOR.$PATCH"
TAG="v$VERSION"

echo "$VERSION" > VERSION
git add VERSION
git commit -m "release $TAG"
git tag "$TAG"
git push origin HEAD "$TAG"

echo "Pushed commit and tag $TAG. Watch the Actions tab for the release build."
