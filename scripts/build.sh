#!/usr/bin/env bash
# Build the Outbound Companion APK locally (macOS / Linux; on Windows use WSL or Android Studio).
# Runs: ./gradlew :core:test :app:assembleDebug
#
# Needs:
#   - JDK 17 on PATH (or JAVA_HOME pointing at one)
#   - ANDROID_HOME (Android SDK with platform android-37.0 and build-tools 36.0.0)
#   - OUTBOUND_KEY_FILE: path to the 32-byte save key (default ./secrets/outbound.key; see tools/derive_key.py)
#   - the four map PNGs in app/src/main/assets/maps/ (see tools/export_maps.py)
# Extra arguments are passed to Gradle instead of the default tasks, e.g. scripts/build.sh :core:test
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail() { echo "ERROR: $*" >&2; exit 1; }

# --- JDK 17
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then JAVA="$(command -v java)"
else fail "Java not found. Install JDK 17 (e.g. Temurin 17) and put it on PATH or set JAVA_HOME."; fi
JV="$("$JAVA" -version 2>&1 | head -n1)"
case "$JV" in
  *'"17.'*) ;;
  *) fail "JDK 17 is required, found: $JV. Set JAVA_HOME to a JDK 17." ;;
esac

# --- Android SDK
[ -n "${ANDROID_HOME:-}" ] || fail "ANDROID_HOME is not set. Point it at your Android SDK (platform android-37.0, build-tools 36.0.0)."
[ -d "$ANDROID_HOME" ] || fail "ANDROID_HOME does not exist: $ANDROID_HOME"

# --- key file (never printed)
export OUTBOUND_KEY_FILE="${OUTBOUND_KEY_FILE:-$ROOT/secrets/outbound.key}"
[ -f "$OUTBOUND_KEY_FILE" ] || fail "Key file not found: $OUTBOUND_KEY_FILE. Create it with tools/derive_key.py, or set OUTBOUND_KEY_FILE."
KEY_SIZE="$(wc -c < "$OUTBOUND_KEY_FILE" | tr -d ' ')"
[ "$KEY_SIZE" = "32" ] || fail "Key file must be exactly 32 bytes (got $KEY_SIZE): $OUTBOUND_KEY_FILE"

# --- map PNGs
MAPS="$ROOT/app/src/main/assets/maps"
missing=0
for m in map-pacific-coast map-starting-level map-canyons map-mountains; do
  [ -f "$MAPS/$m.png" ] || { echo "ERROR: missing $MAPS/$m.png" >&2; missing=1; }
done
[ "$missing" = 0 ] || fail "Map images missing. Run tools/export_maps.py (see README step 5)."

# --- build
chmod +x ./gradlew
if [ "$#" -eq 0 ]; then set -- :core:test :app:assembleDebug; fi
./gradlew "$@"

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo
  echo "APK: $APK"
  echo "This APK contains your save key. Do not share it."
fi
