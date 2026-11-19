#!/usr/bin/env bash
# Checks mvn-retry.sh: a retry must not be defeated by a failure Maven cached
# (*.lastUpdated), and a goal that never succeeds must still fail (EXC-783).
set -uo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
failures=0
check() {
  local name=$1 expected=$2 actual=$3
  if [[ $expected == "$actual" ]]; then echo "ok   $name"; else echo "FAIL $name: expected '$expected', got '$actual'"; failures=$((failures + 1)); fi
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export M2_REPO="$work/repo" RETRY_UNIT=0 CALLS="$work/calls"
cached_failure="$M2_REPO/org/example/lib/1.0/lib-1.0.jar.lastUpdated"

# Stub mvn: like Maven, it fails without "contacting Central" while a
# *.lastUpdated is on disk; STUB_ALWAYS_FAIL simulates Central staying down.
cat >"$work/mvn" <<'STUB'
#!/usr/bin/env bash
echo x >>"$CALLS"
[[ -n ${STUB_ARGS_FILE:-} ]] && printf '%s|%s\n' "$#" "$3" >"$STUB_ARGS_FILE"
if [[ -f ${STUB_FAIL_ONCE:-} ]]; then rm -f "$STUB_FAIL_ONCE"; exit 1; fi
[[ -n ${STUB_ALWAYS_FAIL:-} ]] && exit 1
[[ -z $(find "$M2_REPO" -name '*.lastUpdated' 2>/dev/null) ]]
STUB
chmod +x "$work/mvn"
export MVN_CMD="$work/mvn"

# shellcheck source=scripts/ci/mvn-retry.sh
source "$root/scripts/ci/mvn-retry.sh"
calls() { wc -l <"$CALLS" 2>/dev/null || echo 0; }

mkdir -p "$(dirname "$cached_failure")" && touch "$cached_failure"
mvn_retry verify -pl some-module >/dev/null; check "recovers from a cached not-found" "0" "$?"
check "that took exactly one retry" "2" "$(calls | tr -d ' ')"
check "the cached failure is gone" "0" "$(find "$M2_REPO" -name '*.lastUpdated' | wc -l | tr -d ' ')"

rm -f "$CALLS"
mvn_retry verify >/dev/null; check "a clean cache succeeds first time" "0" "$?"
check "with a single call" "1" "$(calls | tr -d ' ')"

rm -f "$CALLS"
STUB_ALWAYS_FAIL=1 mvn_retry verify >/dev/null; check "a goal that never succeeds fails" "1" "$?"
check "after four attempts" "4" "$(calls | tr -d ' ')"

# mvn gets --batch-mode first, so it must see 3 arguments and the spaced one as $3
args=(dependency:get "-Dartifact=g:a:1 with space")
rm -f "$CALLS"; STUB_ARGS_FILE="$work/args" mvn_retry "${args[@]}" >/dev/null
check "arguments reach mvn intact, spaces kept" "3|-Dartifact=g:a:1 with space" "$(cat "$work/args")"

# a repository dir that does not exist yet must not end the warm step under set -e
M2_REPO="$work/absent/repo"; touch "$work/once"; rm -f "$CALLS"
(set -e; STUB_FAIL_ONCE="$work/once" mvn_retry verify >/dev/null); check "a missing repository dir still retries" "0" "$?"
check "and retried once" "2" "$(calls | tr -d ' ')"

# the always() report step must not fail a job that died before the cache action ran
RUNNER_TEMP="$work/no-snapshot" "$root/scripts/ci/m2-files.sh" report >/dev/null 2>&1; check "report without a snapshot exits 0" "0" "$?"

grep -q 'mvn-retry.sh' "$root/.github/actions/maven-cache/action.yml"; check "the cache action uses the tested loop" "0" "$?"

grep -q 'graalvm-reachability-metadata:${graalvm_buildtools}' "$root/.github/actions/maven-cache/action.yml"; check "reachability metadata version comes from the pom" "0" "$?"

exit $((failures > 0))
