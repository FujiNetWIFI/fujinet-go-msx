#!/usr/bin/env bash
# Build the signed AAB for Google Play. Complements (does not replace) the
# GitHub debug-APK prerelease flow.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f keystore.properties ]] || { echo "keystore.properties missing (see docs/play-checklist.md)" >&2; exit 1; }
./gradlew bundleRelease
AAB=app/build/outputs/bundle/release/app-release.aab
[[ -f "$AAB" ]] || { echo "expected bundle not found: $AAB" >&2; exit 1; }
keytool -printcert -jarfile "$AAB" | head -5
echo "Upload to Play Console: $AAB"
