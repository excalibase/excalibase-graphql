#!/usr/bin/env bash
# Checks the warm-once layout (EXC-786): each workflow with Maven jobs has exactly one
# maven-warm job that warms the cache, and every other job using the shared cache
# needs it and only restores. A new Maven job that forgets this would bring back the
# cold-cache burst against Maven Central.
set -uo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
failures=0
check() {
  local name=$1 expected=$2 actual=$3
  if [[ $expected == "$actual" ]]; then echo "ok   $name"; else echo "FAIL $name: expected '$expected', got '$actual'"; failures=$((failures + 1)); fi
}

report=$(python3 -I - "$root/.github/workflows" <<'PY'
import sys, glob, os, yaml

ACTION = "./.github/actions/maven-cache"

def uses_cache(job):
    return [s for s in job.get("steps", []) if s.get("uses") == ACTION]

def needs(job):
    n = job.get("needs", [])
    return [n] if isinstance(n, str) else list(n)

for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.yml"))):
    wf = os.path.basename(path)
    jobs = yaml.safe_load(open(path))["jobs"]
    users = {name: uses_cache(j) for name, j in jobs.items() if uses_cache(j)}
    if not users:
        continue
    warmers = [n for n, steps in users.items() if any(str(s.get("with", {}).get("warm")) == "true" for s in steps)]
    print(f"{wf} warm-jobs {','.join(warmers) or 'none'}")
    print(f"{wf} warm-job-named-maven-warm {'maven-warm' in warmers and len(warmers) == 1}")
    print(f"{wf} warm-job-needs-nothing {not needs(jobs.get('maven-warm', {}))}")
    for name in users:
        if name == "maven-warm":
            continue
        print(f"{wf} {name} needs-maven-warm {'maven-warm' in needs(jobs[name])}")
        cond = str(jobs[name].get("if", ""))
        # a skipped job counts as passing for a required check, so a failed warm must not skip it
        print(f"{wf} {name} guarded {'!cancelled()' in cond or 'always()' in cond}")
        if name == "build":
            gate = "needs.test.result == 'success'" in cond
            print(f"{wf} {name} keeps-test-gate {gate}")
        if name == "build-native":
            main_only = "refs/heads/main" in cond
            print(f"{wf} {name} keeps-main-only {main_only}")
PY
)
[[ -n $report ]] || { echo "FAIL no workflow uses the shared Maven cache"; exit 1; }

while read -r wf rest; do
  case $rest in
    "warm-jobs "*) check "$wf: only maven-warm warms" "maven-warm" "${rest#warm-jobs }" ;;
    "warm-job-named-maven-warm "*) check "$wf: exactly one warm job" "True" "${rest#warm-job-named-maven-warm }" ;;
    "warm-job-needs-nothing "*) check "$wf: maven-warm waits for nothing" "True" "${rest#warm-job-needs-nothing }" ;;
    *" guarded "*) job=${rest%% *}; check "$wf: $job still runs when maven-warm fails" "True" "${rest##* }" ;;
    *" keeps-test-gate "*) job=${rest%% *}; check "$wf: $job still needs test to succeed" "True" "${rest##* }" ;;
    *" keeps-main-only "*) job=${rest%% *}; check "$wf: $job stays push-to-main only" "True" "${rest##* }" ;;
    *) job=${rest%% *}; check "$wf: $job needs maven-warm" "True" "${rest##* }" ;;
  esac
done <<<"$report"

# restore-only jobs must not be able to write the cache
saves=$(grep -rn 'actions/cache/save' "$root/.github/workflows" | wc -l | tr -d ' ')
check "workflows never save the Maven cache themselves" "0" "$saves"

action="$root/.github/actions/maven-cache/action.yml"
grep -q 'timeout --kill-after=[0-9a-z]* 25m scripts/ci/warm-maven.sh' "$action"; check "the warm body is bounded below the job timeout" "0" "$?"
grep -q 'maven-mirror-used' "$action"; check "the action looks for the mirror marker" "0" "$?"
grep -A3 'name: Save Maven cache' "$action" | grep -q "steps.mirror.outputs.used != 'true'"; check "a cache warmed through the mirror is not saved" "0" "$?"
grep -q "'Makefile'" "$action"; check "the cache key covers the Makefile" "0" "$?"

exit $((failures > 0))
