#!/usr/bin/env bash
# Install the debug APK on one connected Android device with adb and launch it on the secondary display.
#
# Usage: scripts/install.sh [path/to/app-debug.apk]
# Environment:
#   ANDROID_SERIAL      adb serial to use when more than one device is connected
#   OUTBOUND_DISPLAY_ID display id to launch on (skips auto-detection)
#   ADB                 adb binary (default: adb on PATH, else $ANDROID_HOME/platform-tools/adb)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
APP_ID=dev.local.outboundcompanion
fail() { echo "ERROR: $*" >&2; exit 1; }

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then ADB="$ANDROID_HOME/platform-tools/adb"
  else fail "adb not found. Install Android platform-tools or set ADB."; fi
fi
[ -f "$APK" ] || fail "APK not found: $APK (run scripts/build.sh first)"

# --- pick the device
if [ -z "${ANDROID_SERIAL:-}" ]; then
  DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
  COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"
  [ "$COUNT" -ge 1 ] || fail "No authorised device found. Enable USB debugging, connect the handheld and accept the prompt (adb devices)."
  [ "$COUNT" -eq 1 ] || fail "More than one device connected. Set ANDROID_SERIAL to one of: $(printf '%s' "$DEVICES" | tr '\n' ' ')"
  export ANDROID_SERIAL="$DEVICES"
fi

"$ADB" install -r "$APK"

# --- find the secondary display id
display_id="${OUTBOUND_DISPLAY_ID:-}"
if [ -z "$display_id" ]; then
  # Logical displays other than the default one. Try `cmd display get-displays`, then dumpsys display.
  ids_of() { tr -d '\r' | grep -oE '[Dd]isplay ?[Ii]d[=: ]+[0-9]+' | grep -oE '[0-9]+$' | sort -un | grep -vx 0 || true; }
  ids="$("$ADB" shell cmd display get-displays 2>/dev/null | ids_of || true)"
  if [ -z "$ids" ]; then
    ids="$("$ADB" shell dumpsys display 2>/dev/null | ids_of || true)"
  fi
  display_id="$(printf '%s\n' "$ids" | grep . | head -n1 || true)"
  if [ -n "$display_id" ] && [ "$(printf '%s\n' "$ids" | grep -c .)" -gt 1 ]; then
    echo "Several secondary displays found ($(printf '%s' "$ids" | tr '\n' ' ')); using $display_id. Override with OUTBOUND_DISPLAY_ID."
  fi
fi

if [ -n "$display_id" ]; then
  echo "Launching on display $display_id"
  "$ADB" shell am start --display "$display_id" -n "$APP_ID/.MainActivity" ||
    echo "Launch on display $display_id failed. Try OUTBOUND_DISPLAY_ID=<id> (see: adb shell dumpsys display) or open the app from the launcher."
else
  echo "No secondary display detected; launching normally."
  echo "Hint: if your handheld has a second screen, find its id with 'adb shell dumpsys display' and re-run with OUTBOUND_DISPLAY_ID=<id>."
  "$ADB" shell am start -n "$APP_ID/.MainActivity"
fi
