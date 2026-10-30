#!/usr/bin/env bash
# Guard: Eccles Bluechat must never request the INTERNET permission.
# Checks the source manifest, and a built APK too when you pass one.
# Usage: bash scripts/check-no-internet.sh [path/to/app.apk]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ -f "$MANIFEST" ]] || die "manifest not found: $MANIFEST"

# Strip XML comments first so explanatory text can mention the permission.
stripped="$(python3 - "$MANIFEST" <<'PY' 2>/dev/null || true
import re, sys
print(re.sub(r"<!--.*?-->", "", open(sys.argv[1], encoding="utf-8").read(), flags=re.S))
PY
)"
if [[ -z "$stripped" ]]; then
  stripped="$(sed -e ':a' -e 's/<!--[^>]*-->//g' -e '/<!--/{N;ba}' "$MANIFEST")"
fi

if grep -Eq 'android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)' <<<"$stripped"; then
  die "source manifest requests a network permission. Bluechat is offline by design."
fi
ok "source manifest: no INTERNET permission"

if [[ $# -ge 1 ]]; then
  apk="$1"; [[ -f "$apk" ]] || die "APK not found: $apk"
  need unzip
  aapt2="$(find_build_tool aapt2)"
  if [[ -n "$aapt2" ]]; then
    perms="$("$aapt2" dump permissions "$apk" 2>/dev/null || true)"
  else
    need strings
    perms="$(unzip -p "$apk" AndroidManifest.xml | strings -e l)"
  fi
  if grep -q 'android\.permission\.INTERNET' <<<"$perms"; then
    die "APK requests INTERNET: $apk"
  fi
  ok "APK manifest: no INTERNET permission ($(basename "$apk"))"
fi
