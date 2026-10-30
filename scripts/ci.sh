#!/usr/bin/env bash
# The one command CI runs. Run it locally before you push.
# Usage: bash scripts/ci.sh [--fast]     (--fast skips Gradle: guards and script checks only)
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"

fast=0; [[ "${1:-}" == "--fast" ]] && fast=1

info "1/4 script syntax"
for f in scripts/*.sh; do bash -n "$f" || die "syntax error in $f"; done
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -x -S warning scripts/*.sh && ok "shellcheck clean"
else
  warn "shellcheck not installed: skipped"
fi

info "2/4 guards"
bash scripts/check-secrets.sh
bash scripts/check-no-internet.sh

if ((fast)); then ok "fast mode: skipping Gradle"; exit 0; fi

info "3/4 unit tests"
bash scripts/test.sh

info "4/4 debug build"
bash scripts/build.sh debug
ok "CI passed"
