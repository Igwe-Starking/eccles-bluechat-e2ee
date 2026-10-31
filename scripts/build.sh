#!/usr/bin/env bash
# Build an APK.
# Usage: bash scripts/build.sh [debug|release]

variant="${1:-debug}"
case "$variant" in
  debug)   task="assembleDebug";   out="app/build/outputs/apk/debug";;
  release) task="assembleRelease"; out="app/build/outputs/apk/release";;
  *) echo "usage: build.sh [debug|release]"; exit 1;;
esac

echo "==> gradle $task"

# Clean line endings and fix execution permissions on the runner
sed -i 's/\r$//' gradlew
chmod +x gradlew

# Execute gradle directly relative to where the workflow runner is standing
./gradlew --console=plain --no-daemon "$task"

echo "==> built:"
ls -1 "$out"/*.apk
