#!/usr/bin/env bash
# Maven jobs run with -q or through make, which hides "Downloading from". This
# lists what a job added to ~/.m2/repository instead (EXC-783).
#   m2-files.sh snapshot   record the files present now (the cache action does it)
#   m2-files.sh report     print the files added since the snapshot
set -euo pipefail

repo=${M2_REPO:-$HOME/.m2/repository}
before="${RUNNER_TEMP:-/tmp}/m2-files-before.txt"
list() { [[ -d $repo ]] && find "$repo" -type f | sort; true; } # a cold run snapshots before the repository exists

case ${1:-} in
  snapshot) list >"$before" ;;
  report)
    # the job may have failed before the cache action ran
    [[ -f $before ]] || { echo "No Maven cache snapshot in this job, nothing to report"; exit 0; }
    added=$(comm -13 "$before" <(list))
    echo "Maven added $(grep -c . <<<"$added" || true) file(s) to ~/.m2 during this job"
    [[ -z $added ]] || head -20 <<<"$added"
    ;;
  *) echo "usage: m2-files.sh snapshot|report" >&2; exit 2 ;;
esac
