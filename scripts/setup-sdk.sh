#!/usr/bin/env bash
# Accept SDK licenses and install the packages this project compiles against.
# Usage: bash scripts/setup-sdk.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK" ]] || die "ANDROID_HOME is not set"

SDKMANAGER="$(command -v sdkmanager || true)"
[[ -n "$SDKMANAGER" ]] || SDKMANAGER="$(find "$SDK/cmdline-tools" -name sdkmanager -type f 2>/dev/null | sort -V | tail -n 1)"
[[ -n "$SDKMANAGER" ]] || die "sdkmanager not found; install the Android command-line tools"

info "accepting licenses"
yes | "$SDKMANAGER" --licenses >/dev/null || true
info "installing platform android-35 and build-tools 35.0.0"
"$SDKMANAGER" "platforms;android-35" "build-tools;35.0.0" >/dev/null
ok "Android SDK ready"
