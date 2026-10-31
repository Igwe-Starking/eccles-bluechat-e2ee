#!/usr/bin/env bash
# Run the JVM unit tests (crypto, framing, trust store, utilities).
# Usage: bash scripts/test.sh [extra gradle args]

echo "==> unit tests"

# Force cleanup on whatever path we are currently standing in
sed -i 's/\r$//' gradlew
chmod +x gradlew

# Execute gradle directly relative to where the runner called us
./gradlew --console=plain --no-daemon testDebugUnitTest "$@"

echo "==> unit tests passed"
