#!/usr/bin/env bash
# Build an APK.
# Usage: bash scripts/build.sh [debug|release]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"

variant="${1:-debug}"
case "$variant" in
  debug)   task="assembleDebug";   out="app/build/outputs/apk/debug";;
  release) task="assembleRelease"; out="app/build/outputs/apk/release";;
  *) die "usage: build.sh [debug|release]";;
esac

# WORKAROUND: Force clean line endings and execution rights on Linux runners
sed -i 's/\r$//' gradlew
chmod +x gradlew

info "gradle $task"
./gradlew --console=plain --no-daemon "$task"
ok "built:"
ls -1 "$out"/*.apk
