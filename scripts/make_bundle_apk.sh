#!/bin/bash
# Builds by hand the same kind of bundle the app uploads: an APK holding only the original
# AndroidManifest.xml plus the given files at their original paths. Use it to check on VirusTotal
# whether such a bundle is detected as an Android package.
#
# Usage: scripts/make_bundle_apk.sh <app.apk> <out.apk> <path/inside/apk>...
#   e.g. scripts/make_bundle_apk.sh big.apk bundle.apk assets/dev/abcdef.bundle lib/arm64-v8a/libfoo.so
set -euo pipefail

if [[ $# -lt 3 ]]; then
  sed -n '6,7p' "$0"
  exit 1
fi
APK="$1"
OUT="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
shift 2
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

unzip -q "$APK" AndroidManifest.xml "$@" -d "$STAGE"
rm -f "$OUT"
(cd "$STAGE" && zip -q "$OUT" AndroidManifest.xml && zip -q -0 "$OUT" "$@")

echo "Created $OUT"
unzip -l "$OUT"
shasum -a 256 "$OUT"
