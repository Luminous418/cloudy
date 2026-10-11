#!/usr/bin/env bash
#
# Test an OTA/ROM build over adb without typing anything on the device.
#
# Generates a manifest from the ZIP's build_info.txt, serves it (plus the ZIP) over a
# local HTTP server, wires `adb reverse` so the device can reach it as 127.0.0.1, and
# tells Cloudy to use it via DevConfigReceiver.
#
#   tools/adb-local-ota.sh <ROM.zip> [changelog;entries]
#   tools/adb-local-ota.sh --clear     # restore the app's default sources
#   tools/adb-local-ota.sh --stop      # stop the local server + adb reverse
#
# Options:
#   -p, --port PORT     local HTTP port (default: 8000)
#   -s, --serial SN     target device serial (default: the only connected device)
#   --no-hash           skip SHA-256 (faster start; the app then skips verification)
#   -h, --help          show this help
#
set -euo pipefail

PORT=8000
SERIAL=""
PKG="dev.cloudy.ota"
HASH=1
SERVE_DIR="${TMPDIR:-/tmp}/cloudy-local-ota"
PIDFILE="$SERVE_DIR/http.pid"

# Component is resolved from PKG at start time (see below), so --package can override it.

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

usage() {
    cat <<'EOF'
Test an OTA/ROM build over adb without typing anything on the device.

  tools/adb-local-ota.sh [options] <ROM.zip> [changelog;entries]
  tools/adb-local-ota.sh --clear     # restore the app's default sources
  tools/adb-local-ota.sh --stop      # stop the local server + adb reverse

Options:
  -p, --port PORT       local HTTP port (default: 8000)
  -s, --serial SN       target device serial (default: the only connected device)
  -k, --package PKG     app package id (default: dev.cloudy.ota)
  --no-hash             skip SHA-256 (faster start; the app then skips verification)
  -h, --help            show this help
EOF
}

# ---- flags ------------------------------------------------------------------
ACTION="start"
ZIP=""
CHANGELOG="Local test build"
POSITIONAL=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        -p|--port)    PORT="$2"; shift 2 ;;
        -s|--serial)  SERIAL="$2"; shift 2 ;;
        -k|--package) PKG="$2"; shift 2 ;;
        --no-hash)    HASH=0; shift ;;
        --clear)      ACTION="clear"; shift ;;
        --stop)       ACTION="stop"; shift ;;
        -h|--help)    usage; exit 0 ;;
        --) shift; while [ "$#" -gt 0 ]; do POSITIONAL+=("$1"); shift; done ;;
        -*) die "unknown option: $1 (try --help)" ;;
        *)  POSITIONAL+=("$1"); shift ;;
    esac
done
COMPONENT="$PKG/.ota.DevConfigReceiver"
if [ "${#POSITIONAL[@]}" -gt 0 ]; then
    ZIP="${POSITIONAL[0]}"
    if [ "${#POSITIONAL[@]}" -gt 1 ]; then CHANGELOG="$(IFS=';'; echo "${POSITIONAL[*]:1}")"; fi
fi

command -v adb   >/dev/null || die "adb not found in PATH"
command -v jq    >/dev/null || die "jq not found in PATH"
command -v unzip >/dev/null || die "unzip not found in PATH"

if [ -z "$SERIAL" ]; then
    mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    [ "${#DEVICES[@]}" -eq 1 ] || die "expected exactly one device, found ${#DEVICES[@]} (use -s SERIAL)"
    SERIAL="${DEVICES[0]}"
fi
ADB=(adb -s "$SERIAL")
log "device: $SERIAL"

case "$ACTION" in
    stop)
        if [ -f "$PIDFILE" ]; then kill "$(cat "$PIDFILE")" 2>/dev/null || true; rm -f "$PIDFILE"; fi
        "${ADB[@]}" reverse --remove "tcp:$PORT" 2>/dev/null || true
        log "stopped local server + adb reverse on port $PORT"
        exit 0
        ;;
    clear)
        "${ADB[@]}" shell am broadcast -n "$COMPONENT" --ez clear true >/dev/null
        log "Cloudy sources reset to defaults"
        exit 0
        ;;
esac

[ -n "$ZIP" ] || { usage; exit 1; }
[ -f "$ZIP" ] || die "zip not found: $ZIP"

# ---- read build_info.txt from the zip ---------------------------------------
BUILD_INFO="$(unzip -p "$ZIP" build_info.txt 2>/dev/null)" \
    || die "no build_info.txt inside $ZIP (is it a LumiROM package?)"
bib() { printf '%s\n' "$BUILD_INFO" | { grep "^$1=" || true; } | head -1 | cut -d= -f2-; }

DEVICE="$(bib device)"; DEVICE="${DEVICE:-unknown}"
VERSION="$(bib version)"
VERSION_CODE="$(bib version_code)"
BUILD_DATE="$(bib build_date)"
ANDROID_VERSION="$(bib android_version)"
ONEUI="$(bib oneui_code)"; ONEUI="${ONEUI:-$(bib oneui_version)}"
SECURITY_PATCH="$(bib security_patch)"
FINGERPRINT="$(bib build_fingerprint)"
DEVICE_MODEL="$(bib device_model)"
KERNEL_VERSION="$(bib kernel_version)"
PARTITION_LAYOUT="$(bib partition_layout)"; PARTITION_LAYOUT="${PARTITION_LAYOUT:-a-only}"
[ -n "$VERSION" ] || die "build_info.txt has no version field"

# ---- stage the serve dir ----------------------------------------------------
mkdir -p "$SERVE_DIR"
if [ -f "$PIDFILE" ]; then kill "$(cat "$PIDFILE")" 2>/dev/null || true; rm -f "$PIDFILE"; fi
ln -sf "$(readlink -f "$ZIP")" "$SERVE_DIR/$(basename "$ZIP")"

FILENAME="$(basename "$ZIP")"
SIZE="$(stat -c %s "$ZIP")"
SHA256=""
if [ "$HASH" -eq 1 ]; then
    log "hashing $FILENAME (this can take a moment)…"
    SHA256="$(sha256sum "$ZIP" | cut -d' ' -f1)"
fi

BASE="http://127.0.0.1:$PORT"
jq -n \
    --arg device "$DEVICE" --arg version "$VERSION" --arg version_code "$VERSION_CODE" \
    --arg build_date "$BUILD_DATE" --arg android_version "$ANDROID_VERSION" \
    --arg oneui "$ONEUI" --arg security_patch "$SECURITY_PATCH" \
    --arg fingerprint "$FINGERPRINT" --arg device_model "$DEVICE_MODEL" \
    --arg kernel_version "$KERNEL_VERSION" --arg layout "$PARTITION_LAYOUT" \
    --arg changelog "$CHANGELOG" --arg url "$BASE/$FILENAME" --arg filename "$FILENAME" \
    --arg size "$SIZE" --arg sha256 "$SHA256" \
    '{
        rom_name: "LumiROM (local test)",
        maintainer: {
            name: "Local", handle: "@local", device: $device, codename: $device,
            avatar_url: null, telegram: null, donate_url: null
        },
        releases: [{
            version: $version,
            version_code: ($version_code | if length > 0 then tonumber else null end),
            build_date: $build_date, android_version: $android_version,
            oneui_version: ($oneui | if length > 0 then . else null end),
            security_patch: $security_patch, build_fingerprint: $fingerprint,
            device_model: $device_model, kernel_version: $kernel_version,
            partition_layout: $layout,
            changelog: ($changelog | split(";") | map(select(length > 0))),
            download: {
                url: $url, filename: $filename, size_bytes: ($size | tonumber),
                sha256: ($sha256 | if length > 0 then . else null end),
                install_type: "recovery_zip"
            }
        }]
    }' > "$SERVE_DIR/$DEVICE.json"

# ---- serve + wire adb -------------------------------------------------------
log "serving $SERVE_DIR on $BASE"
nohup python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$SERVE_DIR" \
    >/dev/null 2>&1 &
echo $! > "$PIDFILE"
sleep 1
"${ADB[@]}" reverse "tcp:$PORT" "tcp:$PORT"
"${ADB[@]}" shell am broadcast -n "$COMPONENT" --es url "$BASE/$DEVICE.json" >/dev/null

cat <<EOF

Cloudy now points at: $BASE/$DEVICE.json
  device : $DEVICE ($DEVICE_MODEL)  version $VERSION
  package: $FILENAME

Open the app (OTA / ROM tab) and pull the update. To undo:
  $0 --stop      # stop the server + adb reverse (keeps the URL in the app)
  $0 --clear     # reset Cloudy's sources to the built-in defaults
EOF
