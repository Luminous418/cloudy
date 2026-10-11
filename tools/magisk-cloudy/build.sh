#!/usr/bin/env bash
#
# Build (and optionally install) a Magisk module that overlays Cloudy's priv-app APK.
# This is the way to update Cloudy on a device where a normal `adb install` is refused
# because the app ships as a system priv-app (same package, same platform signature).
#
#   tools/magisk-cloudy/build.sh                     # -> dist/Cloudy-<ver>-magisk.zip
#   tools/magisk-cloudy/build.sh --install [SERIAL]  # also push + `magisk --install-module`
#
# The release APK must be signed with the ROM's platform key (see keystore.properties)
# so the mounted APK keeps the same signer as the one it replaces.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
    ./gradlew assembleRelease
fi

VER="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)"
VC="$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)"
DIST="dist"
OUT="$DIST/Cloudy-${VER}-magisk.zip"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# module.prop with the version taken from the app itself.
sed -e "s/^version=.*/version=${VER}/" -e "s/^versionCode=.*/versionCode=${VC}/" \
    tools/magisk-cloudy/module.prop > "$TMP/module.prop"

mkdir -p "$TMP/system/priv-app/Cloudy"
cp "$APK" "$TMP/system/priv-app/Cloudy/Cloudy.apk"

mkdir -p "$DIST"
rm -f "$OUT"
( cd "$TMP" && zip -r9q "$ROOT/$OUT" . )
echo "module: $OUT ($(du -h "$OUT" | cut -f1))"

if [ "${1:-}" = "--install" ]; then
    if [ -n "${2:-}" ]; then ADB=(adb -s "$2"); else ADB=(adb); fi
    "${ADB[@]}" push "$OUT" /data/local/tmp/cloudy-magisk.zip >/dev/null
    "${ADB[@]}" shell su -c "magisk --install-module /data/local/tmp/cloudy-magisk.zip"
    echo "installed - reboot to apply"
fi
