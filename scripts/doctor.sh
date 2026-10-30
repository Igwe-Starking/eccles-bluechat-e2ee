#!/usr/bin/env bash
# Check that this machine can build Eccles Bluechat.
# Usage: bash scripts/doctor.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

status=0
check() { # name, command, hint
  if eval "$2" >/dev/null 2>&1; then ok "$1"; else warn "$1 -> $3"; status=1; fi
}

info "Eccles Bluechat build doctor"
check "bash 4+"            '[[ ${BASH_VERSINFO[0]} -ge 4 ]]'            "install a newer bash"
check "JDK 17+"            'java -version 2>&1 | grep -Eq "\"(1[7-9]|[2-9][0-9])\."' "install JDK 17 (Temurin recommended)"
check "ANDROID_HOME set"   '[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]' "export ANDROID_HOME=/path/to/Android/Sdk"
check "platform android-35" '[[ -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platforms/android-35" ]]' "bash scripts/setup-sdk.sh"
check "gradlew executable" "[[ -x '$ROOT/gradlew' ]]"                    "chmod +x gradlew"
check "adb (optional)"     'command -v adb'                              "optional: needed only for 'adb install'"
exit "$status"
