#!/usr/bin/env bash
# Run the JVM unit tests (crypto, framing, trust store, utilities).
# Usage: bash scripts/test.sh [extra gradle args]

# Safe cross-platform fallback path helper
if [ -f "$(dirname "${BASH_SOURCE}")/lib.sh" ]; then
    source "$(dirname "${BASH_SOURCE}")/lib.sh"
fi

# Lock path context directly to the repository root directory
cd "$(dirname "${BASH_SOURCE}")/.."

if command -v info &> /dev/null; then info "unit tests"; else echo "==> unit tests"; fi

# WORKAROUND: Clean line endings and permissions directly on the Linux runner
sed -i 's/\r$//' gradlew
chmod +x gradlew

# Execute tests via the root wrapper safely
./gradlew --console=plain --no-daemon testDebugUnitTest "$@"

if command -v ok &> /dev/null; then ok "unit tests passed"; else echo "==> unit tests passed"; fi
