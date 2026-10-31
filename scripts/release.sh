#!/usr/bin/env bash
# Build, sign (if secrets are present), verify and checksum a release into ./dist.
# Usage: bash scripts/release.sh 3.1.0

version="${1:?usage: release.sh <version, e.g. 3.1.0>}"

# Hard-lock context exactly to the repository workspace root folder directory
cd "$(dirname "${BASH_SOURCE[0]}")/.."

dist="dist"
mkdir -p "$dist"

echo "==> Running local verification checks..."
bash scripts/check-secrets.sh
bash scripts/check-no-internet.sh

echo "==> gradle assembleRelease"
# Force Linux normalization right here on the script execution boundary
sed -i 's/\r$//' gradlew
chmod +x gradlew

./gradlew --console=plain --no-daemon assembleRelease

src_dir="app/build/outputs/apk/release"
apk="$(ls "$src_dir"/*.apk | head -n 1)"
final="$dist/EcclesBluechat-$version.apk"

if [[ -n "${KEYSTORE_B64:-}${KEYSTORE_FILE:-}" ]]; then
  bash scripts/sign-apk.sh "$apk" "$final"
else
  if [[ "${CI:-}" == "true" ]]; then
    echo "ERROR: CI release without signing secrets would publish an uninstallable APK"
    exit 1
  fi
  cp "$apk" "$final"
fi

bash scripts/check-no-internet.sh "$final"
echo "==> Release asset compiled successfully"
