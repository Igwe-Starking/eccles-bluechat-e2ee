#!/usr/bin/env bash
# Guard: refuse to proceed if signing material or local config is in the tree.
# Usage: bash scripts/check-secrets.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  mapfile -t files < <(git ls-files)
else
  mapfile -t files < <(find . -type f -not -path './.git/*' -not -path './.gradle/*' -not -path '*/build/*' | sed 's|^\./||')
fi

bad=()
for f in "${files[@]}"; do
  case "$f" in
    *.jks|*.keystore|*.p12|*.pem|*.pfx|keystore.properties|local.properties|.env|.env.*) bad+=("$f");;
  esac
done

if ((${#bad[@]})); then
  printf '  %s\n' "${bad[@]}" >&2
  die "signing material or local config is tracked. Remove it and rotate anything exposed."
fi
ok "no keystores, keys or local config in the tree"
