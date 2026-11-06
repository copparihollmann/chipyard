#!/usr/bin/env bash
# Remove docs publishing wiring that is incompatible with task-managed worktrees.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 /path/to/task-managed/firesim" >&2
  exit 2
fi

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
firesim="$1"
patch="$here/firesim-managed-worktree-ghpages.patch"
base=e1bdea8bb042b900995436ecc3d7c44100eb9da0
patch_sha=36a72ab105a650af30c456a3a8478da2765bac7bc3a5248e2e6e4279b0ceec77

[[ "$(git -C "$firesim" rev-parse HEAD)" == "$base" ]] || {
  echo "FireSim HEAD must be the Iris gitlink $base" >&2
  exit 1
}
[[ "$(sha256sum "$patch" | cut -d' ' -f1)" == "$patch_sha" ]] || {
  echo "FireSim build patch SHA mismatch" >&2
  exit 1
}
git -C "$firesim" diff --quiet && git -C "$firesim" diff --cached --quiet || {
  echo "FireSim worktree must be clean before applying the patch" >&2
  exit 1
}
git -C "$firesim" apply --check "$patch"
git -C "$firesim" apply "$patch"
git -C "$firesim" diff --check
echo "Applied SHA-verified FireSim managed-worktree build patch to $firesim"
