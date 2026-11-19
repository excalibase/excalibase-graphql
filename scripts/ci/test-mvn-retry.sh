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

args=(dependency:get "-Dartifact=g:a:1 with space")
rm -f "$CALLS"; mvn_retry "${args[@]}" >/dev/null; check "arguments with spaces pass through as one" "0" "$?"

grep -q 'mvn-retry.sh' "$root/.github/actions/maven-cache/action.yml"; check "the cache action uses the tested loop" "0" "$?"

exit $((failures > 0))
