#!/usr/bin/env bash
# Checks for the immutable trunk tag (EXC-745): the existence helper's three
# outcomes, and that cd.yml wires the guard, the CI run's own commit and a
# per-commit concurrency group.
set -uo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
helper="$root/scripts/ci/trunk-tag-exists.sh"
cd_yml="$root/.github/workflows/cd.yml"
failures=0

check() {
  local name=$1 expected=$2 actual=$3
  if [[ $expected == "$actual" ]]; then echo "ok   $name"; else echo "FAIL $name: expected '$expected', got '$actual'"; failures=$((failures + 1)); fi
}

stub=$(mktemp -d)
trap 'rm -rf "$stub"' EXIT
printf '#!/usr/bin/env bash\necho "Name: $1"\n' >"$stub/found"
printf '#!/usr/bin/env bash\necho "ERROR: $1: not found" >&2; exit 1\n' >"$stub/missing"
printf '#!/usr/bin/env bash\necho "denied: requested access to the resource is denied" >&2; exit 1\n' >"$stub/broken"
chmod +x "$stub"/*

check "existing tag reports true" "true" "$(INSPECT_CMD="$stub/found" "$helper" img main-abc1234)"
check "absent tag reports false" "false" "$(INSPECT_CMD="$stub/missing" "$helper" img main-abc1234)"
INSPECT_CMD="$stub/broken" "$helper" img main-abc1234 >/dev/null 2>&1
check "unknown failure is not treated as absent" "2" "$?"

grep -q 'trunk-tag-exists.sh' "$cd_yml"; check "cd.yml calls the guard" "0" "$?"
grep -q 'ref: ${{ github.event.workflow_run.head_sha' "$cd_yml"; check "cd.yml checks out the CI run's commit" "0" "$?"
grep -q '^concurrency:' "$cd_yml"; check "cd.yml has a concurrency group" "0" "$?"
grep -q 'type=sha' "$cd_yml"; check "cd.yml no longer derives tags from the branch head" "1" "$?"

exit "$failures"
