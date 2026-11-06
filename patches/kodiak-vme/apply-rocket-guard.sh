#!/usr/bin/env bash
# Apply the exact Rocket CSR guard used to validate KodiakVME1CoreConfig.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 /path/to/task-managed/rocket-chip" >&2
  exit 2
fi

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rocket="$1"
patch="$here/rocket-csr-scalar-guard.patch"
base=fc745a8c377fdbf1d278eea81da9ac47b4b5fbc0
patch_sha=b616b572e249953e2e77bd69abf443db6771ac8a63b9a5be622a55637ae463aa

[[ "$(git -C "$rocket" rev-parse HEAD)" == "$base" ]] || {
  echo "Rocket HEAD must be the Iris gitlink $base" >&2
  exit 1
}
[[ "$(sha256sum "$patch" | cut -d' ' -f1)" == "$patch_sha" ]] || {
  echo "Rocket guard patch SHA mismatch" >&2
  exit 1
}
git -C "$rocket" diff --quiet && git -C "$rocket" diff --cached --quiet || {
  echo "Rocket worktree must be clean before applying the guard" >&2
  exit 1
}
git -C "$rocket" apply --check "$patch"
git -C "$rocket" apply "$patch"
git -C "$rocket" diff --check
echo "Applied SHA-verified Rocket CSR guard to $rocket"
