#!/usr/bin/env bash
# Builds release/v<version>/ with the APK (R8, debug-signed: personal sideload), the quick guide,
# the license files and SHA256SUMS. Bump versionCode/versionName in app/build.gradle.kts first.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
OUT="release/v$VERSION"
./gradlew --console=plain -q assembleRelease
rm -rf "$OUT" && mkdir -p "$OUT"
cp app/build/outputs/apk/release/app-release.apk "$OUT/sidecar_launcher-$VERSION.apk"
cp release/GUIDA.md LICENSE.md THIRD_PARTY_NOTICES.md "$OUT/"
(cd "$OUT" && sha256sum -- * > SHA256SUMS)
echo "Release ready in $OUT:"; ls -la "$OUT"
