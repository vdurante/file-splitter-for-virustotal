#!/bin/bash
# Publishes the signed release APK as a GitHub release, then offers to bump the version.
#
#   scripts/release.sh                         builds the release APK and publishes it
#   ./gradlew publishGithubRelease --console=plain   same through Gradle (plain console, so its
#                                              progress bar doesn't draw over the questions)
#   DRY_RUN=1 scripts/release.sh       checks and builds everything but changes nothing on GitHub
#
# Needs: the GitHub CLI (gh) logged in, keystore.properties (see README → Release builds), a clean
# working tree that is pushed. Run it from a terminal: it asks questions (no answer = "no").
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
GRADLE_FILE="app/build.gradle.kts"
DRY_RUN="${DRY_RUN:-0}"

fail() { echo "✗ $*" >&2; exit 1; }
# Prompts end with a newline so they show up even through Gradle's line-buffered output.
ask() { printf '%s [y/N]\n' "$1"; local answer=""; read -r answer || true; [[ "$answer" =~ ^[Yy] ]]; }
run() { if [[ "$DRY_RUN" == "1" ]]; then echo "  (dry run) $*"; else "$@"; fi; }

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$GRADLE_FILE")"
CODE="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$GRADLE_FILE")"
[[ -n "$VERSION" && -n "$CODE" ]] || fail "Couldn't read versionName/versionCode from $GRADLE_FILE"
TAG="v$VERSION"
echo "Releasing $TAG (versionCode $CODE)"

# --- Checks ------------------------------------------------------------------------------------
command -v gh >/dev/null || fail "GitHub CLI not found: install it from https://cli.github.com"
gh auth status >/dev/null 2>&1 || fail "gh isn't logged in: run 'gh auth login'"
[[ -f keystore.properties ]] || fail "keystore.properties not found, so the APK would be unsigned (see README)"
[[ -z "$(git status --porcelain)" ]] || fail "Uncommitted changes: commit or stash them first"
git fetch -q origin
[[ "$(git rev-parse HEAD)" == "$(git rev-parse '@{u}')" ]] || fail "HEAD isn't pushed (or is behind): run 'git push' / 'git pull' first"

if [[ "${1:-}" != "--no-build" ]]; then
  ./gradlew -q :app:assembleRelease
fi
APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || fail "$APK not found"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
[[ -x "$APKSIGNER" ]] || fail "apksigner not found under $SDK/build-tools"
CERTS="$("$APKSIGNER" verify --print-certs "$APK")" || fail "$APK isn't validly signed"
grep -q "CN=Android Debug" <<<"$CERTS" && fail "$APK is signed with the debug key, not the release key"
CERT_SHA="$(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$CERTS" | head -1)"

mkdir -p build/release
OUT="build/release/file-splitter-for-virustotal-$VERSION.apk"
cp "$APK" "$OUT"
APK_SHA="$(shasum -a 256 "$OUT" | cut -d' ' -f1)"
echo "✓ Signed APK: $OUT"

# --- Existing release or tag -------------------------------------------------------------------
if gh release view "$TAG" >/dev/null 2>&1; then
  ask "Release $TAG already exists on GitHub. Replace it with this build?" || fail "Aborted: bump versionName in $GRADLE_FILE for a new release"
  run gh release delete "$TAG" --yes --cleanup-tag
elif git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  ask "Tag $TAG already exists on GitHub (without a release). Move it to this commit?" || fail "Aborted"
  run git push -q origin --delete "$TAG"
fi
git tag -d "$TAG" >/dev/null 2>&1 || true

# --- Publish -----------------------------------------------------------------------------------
NOTES="$(mktemp)"
trap 'rm -f "$NOTES"' EXIT
cat > "$NOTES" <<EOF
## Install

Download \`file-splitter-for-virustotal-$VERSION.apk\` below and open it on your phone (Android 8.0+).

## Verify

- APK SHA-256: \`$APK_SHA\`
- Signing certificate SHA-256: \`$CERT_SHA\`
EOF
run gh release create "$TAG" "$OUT" \
  --target "$(git rev-parse HEAD)" \
  --title "$TAG" \
  --notes-file "$NOTES" \
  --generate-notes
[[ "$DRY_RUN" == "1" ]] && echo "(dry run) nothing was published" || echo "✓ Published $TAG"

# --- Version bump ------------------------------------------------------------------------------
IFS=. read -r MAJOR MINOR PATCH <<<"$VERSION"
NEXT="$MAJOR.$MINOR.$((${PATCH:-0} + 1))"
if ! ask "Bump the version for the next release?"; then
  echo "Version left at $VERSION"
  exit 0
fi
printf 'Next version [%s]:\n' "$NEXT"
ANSWER=""
read -r ANSWER || true
NEXT="${ANSWER:-$NEXT}"
[[ "$NEXT" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Not a version like 1.2.3: $NEXT"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "  (dry run) would set versionName $NEXT, versionCode $((CODE + 1)) and commit"
  exit 0
fi
sed -i '' -e "s/^\( *versionName = \)\".*\"$/\1\"$NEXT\"/" -e "s/^\( *versionCode = \)[0-9]*$/\1$((CODE + 1))/" "$GRADLE_FILE"
git commit -q -m "Bump version to $NEXT" "$GRADLE_FILE"
echo "✓ Bumped to $NEXT (versionCode $((CODE + 1))) and committed. Push it when ready: git push"
