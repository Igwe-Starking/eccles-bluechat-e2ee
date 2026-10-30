#!/usr/bin/env bash
# Zipalign and sign a release APK.
# Reads the keystore from the environment. Nothing is ever written next to the repo.
#   KEYSTORE_B64      base64 of your .jks file      (or KEYSTORE_FILE=/path/to.jks)
#   KEYSTORE_PASSWORD keystore password
#   KEY_ALIAS         key alias
#   KEY_PASSWORD      key password (defaults to KEYSTORE_PASSWORD)
# Usage: bash scripts/sign-apk.sh input-unsigned.apk output-signed.apk
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 2 ]] || die "usage: sign-apk.sh <unsigned.apk> <signed.apk>"
in="$1"; out="$2"
[[ -f "$in" ]] || die "input APK not found: $in"
: "${KEYSTORE_PASSWORD:?set KEYSTORE_PASSWORD}"
: "${KEY_ALIAS:?set KEY_ALIAS}"
key_pass="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

zipalign="$(find_build_tool zipalign)"; apksigner="$(find_build_tool apksigner)"
[[ -n "$zipalign" && -n "$apksigner" ]] || die "zipalign/apksigner not found (run scripts/setup-sdk.sh)"

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
if [[ -n "${KEYSTORE_FILE:-}" ]]; then ks="$KEYSTORE_FILE"
else
  : "${KEYSTORE_B64:?set KEYSTORE_B64 or KEYSTORE_FILE}"
  ks="$work/release.jks"; base64 -d <<<"$KEYSTORE_B64" > "$ks"
fi

info "zipalign"
"$zipalign" -f -p 4 "$in" "$work/aligned.apk"
info "apksigner"
"$apksigner" sign --ks "$ks" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass "pass:$key_pass" \
  --out "$out" "$work/aligned.apk"
"$apksigner" verify --print-certs "$out" | head -n 3
ok "signed: $out"
