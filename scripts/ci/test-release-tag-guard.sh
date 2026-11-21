#!/usr/bin/env bash
# Checks the write-once guard for release-<major>.<minor>-<sha7> tags (EXC-806)
# and that cd.yml builds release branches through it.
set -uo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
guard="$root/scripts/ci/guard-release-tag.sh"
cd_yml="$root/.github/workflows/cd.yml"
ci_yml="$root/.github/workflows/ci.yml"
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

run_guard() { INSPECT_CMD="$1" "$guard" "${2:-excalibase/excalibase-graphql}" "${3:-release-1.7-abc1234}" >/dev/null 2>&1; echo $?; }

check "a new release tag may be written" "0" "$(run_guard "$stub/missing")"
check "an existing release tag is refused" "1" "$(run_guard "$stub/found")"
check "a registry failure is not treated as absent" "1" "$(run_guard "$stub/broken")"
check "a main tag is refused" "1" "$(run_guard "$stub/missing" excalibase/excalibase-graphql main-abc1234)"
check "latest is refused" "1" "$(run_guard "$stub/missing" excalibase/excalibase-graphql latest)"
check "a version tag is refused" "1" "$(run_guard "$stub/missing" excalibase/excalibase-graphql 1.7.2)"
check "a release tag with a short sha is refused" "1" "$(run_guard "$stub/missing" excalibase/excalibase-graphql release-1.7-abc12)"
check "another registry namespace is refused" "1" "$(run_guard "$stub/missing" other/image release-1.7-abc1234)"

grep -qF 'guard-release-tag.sh' "$cd_yml"; check "cd.yml calls the release guard" "0" "$?"
grep -qF '"release/**"' "$cd_yml"; check "cd.yml listens to release branches" "0" "$?"
grep -qF '"release/**"' "$ci_yml"; check "ci.yml runs on release branches" "0" "$?"
grep -qF "'^release/(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\$'" "$cd_yml"; check "cd.yml accepts only release/<major>.<minor>" "0" "$?"
awk '/^  docker-release:/{f=1; next} /^  [a-z-]+:$/{f=0} f' "$cd_yml" | grep -qE 'type=raw,value=(latest|[0-9]|main)'; check "release builds publish no latest, version or main tag" "1" "$?"

condition=$(awk '/^  docker-release:/{f=1; next} f && /^    if:/ {sub(/^    if: */, ""); print; exit}' "$cd_yml" | tr -s ' ')
[[ $condition == *"github.event.workflow_run.conclusion == 'success' && github.event.workflow_run.event == 'push' && github.event.workflow_run.head_repository.full_name == github.repository"* ]]; check "docker-release checks out only a successful push to this repository" "0" "$?"

exit "$failures"
