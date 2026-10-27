#!/bin/bash
# Builds two test APKs from a real APK (binary AndroidManifest.xml, real classes.dex, resources),
# so VirusTotal recognises them as Android packages:
#   test_apk_files/small_test.apk  the base APK as-is (< 650 MB: submitted directly)
#   test_apk_files/small_test_salted.apk
#                                  same, plus assets/salt.txt with a random value, so its hash is new
#                                  on every run (VirusTotal won't know it: forces a real upload)
#   test_apk_files/big_test.apk    the base APK plus three ~500 MB random files (A, B, C) in separate
#                                  nested folders, so the app has to extract two of them
#
# Usage: scripts/make_test_apk.sh [base.apk] [size_mb_per_file]
#   base.apk defaults to this project's debug build (built if missing); size defaults to 500.
#   PUSH=1 scripts/make_test_apk.sh   also copies both to the connected device's Downloads
#   (with several devices, pick one: ANDROID_SERIAL=<serial from `adb devices`> PUSH=1 …).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
SIZE_MB="${2:-500}"
OUT_DIR="$ROOT/test_apk_files"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

if [[ ! -f "$BASE" ]]; then
  echo "Base APK not found, building the debug APK…"
  (cd "$ROOT" && ./gradlew -q :app:assembleDebug)
fi
unzip -l "$BASE" AndroidManifest.xml >/dev/null || { echo "$BASE has no AndroidManifest.xml"; exit 1; }

mkdir -p "$OUT_DIR" \
  "$STAGE/assets/level1/level2/videos" \
  "$STAGE/res/raw/nested/deeper" \
  "$STAGE/lib/arm64-v8a/data/blobs"

echo "Generating 3 x ${SIZE_MB}MB random files…"
dd if=/dev/urandom of="$STAGE/assets/level1/level2/videos/file_A.bin" bs=1m count="$SIZE_MB" 2>/dev/null
dd if=/dev/urandom of="$STAGE/res/raw/nested/deeper/file_B.bin" bs=1m count="$SIZE_MB" 2>/dev/null
dd if=/dev/urandom of="$STAGE/lib/arm64-v8a/data/blobs/file_C.bin" bs=1m count="$SIZE_MB" 2>/dev/null

cp "$BASE" "$OUT_DIR/small_test.apk"
cp "$BASE" "$OUT_DIR/small_test_salted.apk"
mkdir -p "$STAGE/salt/assets"
openssl rand -hex 16 > "$STAGE/salt/assets/salt.txt"
(cd "$STAGE/salt" && zip -q "$OUT_DIR/small_test_salted.apk" assets/salt.txt)
cp "$BASE" "$OUT_DIR/big_test.apk"
# -0 = stored: random data doesn't compress. Adding entries invalidates the APK's signature, which
# doesn't matter for scanning (and the app doesn't re-sign either).
(cd "$STAGE" && zip -q -r -0 "$OUT_DIR/big_test.apk" assets res lib)

echo "Created:"
ls -lh "$OUT_DIR/small_test.apk" "$OUT_DIR/small_test_salted.apk" "$OUT_DIR/big_test.apk"
echo "Salt: $(cat "$STAGE/salt/assets/salt.txt")"
shasum -a 256 "$OUT_DIR/small_test.apk" "$OUT_DIR/small_test_salted.apk"
unzip -l "$OUT_DIR/big_test.apk" | sort -k1 -n -r | head -8

if [[ "${PUSH:-0}" == "1" ]]; then
  ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
  "$ADB" push "$OUT_DIR/small_test.apk" /sdcard/Download/small_test.apk
  "$ADB" push "$OUT_DIR/small_test_salted.apk" /sdcard/Download/small_test_salted.apk
  "$ADB" push "$OUT_DIR/big_test.apk" /sdcard/Download/big_test.apk
fi
