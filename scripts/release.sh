#!/usr/bin/env bash
# Build, sign (if secrets are present), verify and checksum a release into ./dist.
# Usage: bash scripts/release.sh 3.1.0
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
#cd "$ROOT"

version="${1:?usage: release.sh <version, e.g. 3.1.0>}"
dist="$ROOT/dist"; mkdir -p "$dist"

bash scripts/check-secrets.sh
bash scripts/check-no-internet.sh
bash scripts/build.sh release

src_dir="app/build/outputs/apk/release"
apk="$(ls "$src_dir"/*.apk | head -n 1)"
final="$dist/EcclesBluechat-$version.apk"

if [[ -n "${KEYSTORE_B64:-}${KEYSTORE_FILE:-}" ]]; then
  bash scripts/sign-apk.sh "$apk" "$final"
else
  [[ "${CI:-}" != "true" ]] || die "CI release without signing secrets would publish an uninstallable APK"
  warn "no keystore in environment: copying the unsigned APK (it cannot be installed until signed)"
  cp "$apk" "$final"
fi

bash scripts/check-no-internet.sh "$final"
( cd "$dist" && printf '%s  %s\n' "$(sha256_of "$final")" "$(basename "$final")" > "EcclesBluechat-$version.apk.sha256" )
ok "release ready in $dist"
cat "$dist/EcclesBluechat-$version.apk.sha256"
