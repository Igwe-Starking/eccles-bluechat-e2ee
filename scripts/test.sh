#!/usr/bin/env bash
# Run the JVM unit tests (crypto, framing, trust store, utilities).
# Usage: bash scripts/test.sh [extra gradle args]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"
info "unit tests"
./gradlew --console=plain --no-daemon testDebugUnitTest "$@"
ok "unit tests passed"
