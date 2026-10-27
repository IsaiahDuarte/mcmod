#!/usr/bin/env bash
# Line counts via scc (https://github.com/boyter/scc). Uses .gitignore plus build dirs.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
command -v scc >/dev/null || {
  echo "scc not found; install from https://github.com/boyter/scc" >&2
  exit 1
}
build_excludes=(--exclude-dir build,.gradle,run,target,out,bin)
if [[ "${1:-}" == java ]]; then
  shift
  exec scc "${build_excludes[@]}" --exclude-dir generated --exp-per-language-counters \
    src/main/java src/test/java src/clientTest/java src/gameTest/java "$@"
fi
targets=("$@")
[[ ${#targets[@]} -eq 0 ]] && targets=(.)
exec scc "${build_excludes[@]}" "${targets[@]}"
