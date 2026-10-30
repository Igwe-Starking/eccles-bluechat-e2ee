#!/usr/bin/env bash
# Verify a downloaded APK: checksum, signature, and the no-INTERNET promise.
# Usage: bash scripts/verify-apk.sh EcclesBluechat-3.1.0.apk [expected-sha256]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 ]] || die "usage: verify-apk.sh <apk> [expected-sha256]"
apk="$1"; expected="${2:-}"
[[ -f "$apk" ]] || die "not found: $apk"

actual="$(sha256_of "$apk")"
info "SHA-256  $actual"
if [[ -n "$expected" ]]; then
  [[ "${expected,,}" == "$actual" ]] || die "checksum mismatch. Do not install this file."
  ok "checksum matches"
fi

apksigner="$(find_build_tool apksigner)"
if [[ -n "$apksigner" ]]; then
  "$apksigner" verify --print-certs "$apk" | head -n 3 && ok "signature verifies"
else
  warn "apksigner not found: signature not checked (set ANDROID_HOME to enable)"
fi

bash "$(dirname "${BASH_SOURCE[0]}")/check-no-internet.sh" "$apk"
