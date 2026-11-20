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
[[ -n ${STUB_LOG_ARGS:-} ]] && echo "$*" >>"$STUB_LOG_ARGS"
# Central is down: only a run that was given a settings file (-s) gets through
[[ -n ${STUB_NEEDS_MIRROR:-} && " $* " != *" -s "* ]] && exit 1
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

# Central stays down: the last attempts go through the fallback mirror settings, the first ones do not
export MVN_FALLBACK_SETTINGS="$work/fallback.xml"; touch "$MVN_FALLBACK_SETTINGS"
rm -f "$CALLS" "$work/argv"
STUB_LOG_ARGS="$work/argv" STUB_NEEDS_MIRROR=1 mvn_retry verify -pl x >/dev/null; check "a Central outage is survived through the mirror" "0" "$?"
check "attempts 1 and 2 stay on Central" "0" "$(head -2 "$work/argv" | grep -c -- ' -s ' | tr -d ' ')"
check "attempt 3 switches to the mirror settings" "--batch-mode -s $MVN_FALLBACK_SETTINGS verify -pl x" "$(sed -n 3p "$work/argv")"
check "and succeeds there, three calls" "3" "$(calls | tr -d ' ')"

rm -f "$CALLS" "$work/argv"
STUB_LOG_ARGS="$work/argv" STUB_ALWAYS_FAIL=1 mvn_retry verify >/dev/null
check "a mirror that fails too still fails the goal" "4" "$(calls | tr -d ' ')"
check "attempt 4 also uses the mirror" "1" "$(sed -n 4p "$work/argv" | grep -c -- ' -s ')"

MVN_FALLBACK_SETTINGS="$work/absent.xml" STUB_LOG_ARGS="$work/argv2" STUB_ALWAYS_FAIL=1 mvn_retry verify >/dev/null
check "no settings file, no -s on any attempt" "0" "$(grep -c -- ' -s ' "$work/argv2" | tr -d ' ')"
unset MVN_FALLBACK_SETTINGS

# the shipped settings file: Google's Central mirror, nothing else, HTTPS only
settings="$root/scripts/ci/central-fallback-settings.xml"
check "the default fallback file is the shipped one" "0" "$(grep -q 'central-fallback-settings.xml' "$root/scripts/ci/mvn-retry.sh"; echo $?)"
check "it mirrors only central" "1" "$(grep -c '<mirrorOf>' "$settings" | tr -d ' ')"
check "mirrorOf is exactly central" "0" "$(grep -q '<mirrorOf>central</mirrorOf>' "$settings"; echo $?)"
check "the mirror keeps the id central, so artifacts already cached stay valid" "0" "$(grep -q '<id>central</id>' "$settings"; echo $?)"
check "the mirror is Google's Central mirror over HTTPS" "0" "$(grep -q '<url>https://maven-central.storage-download.googleapis.com/maven2/</url>' "$settings"; echo $?)"
check "no plain-http repository url" "0" "$(grep -qi "<url>http:" "$settings"; echo $((1 - $?)))"
check "checksums are not relaxed (no policy, no lax flag)" "0" "$(grep -qiE "checksumPolicy|lax-checksums|[[:space:]]-c[[:space:]]" "$settings" "$root/scripts/ci/mvn-retry.sh"; echo $((1 - $?)))"
check "strict checksums stay on in maven.config" "0" "$(grep -qx -- '--strict-checksums' "$root/.mvn/maven.config"; echo $?)"

# the always() report step must not fail a job that died before the cache action ran
RUNNER_TEMP="$work/no-snapshot" "$root/scripts/ci/m2-files.sh" report >/dev/null 2>&1; check "report without a snapshot exits 0" "0" "$?"

grep -q 'mvn-retry.sh' "$root/.github/actions/maven-cache/action.yml"; check "the cache action uses the tested loop" "0" "$?"

grep -q 'graalvm-reachability-metadata:${graalvm_buildtools}' "$root/.github/actions/maven-cache/action.yml"; check "reachability metadata version comes from the pom" "0" "$?"

exit $((failures > 0))
