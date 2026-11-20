#!/usr/bin/env bash
# Source this. mvn_retry runs mvn and retries the whole goal on failure.
# Central answers a cold-cache burst with 429s and bogus "not found"s, and Maven
# remembers a not-found in *.lastUpdated and fails the next attempt without
# contacting Central, so those markers are dropped before every retry (EXC-783).
# On pull_request runs only, attempts 3 and 4 go through Google's Central mirror
# (-s with central-fallback-settings.xml): Maven mirrors replace rather than fall
# back, so the switch has to be a whole new run. The mirror is not an officially
# supported Google product, so main pushes and scheduled runs keep retrying Central
# and what main caches and ships comes from Central alone. A run that used the mirror
# leaves a marker file, and the cache action does not save such a cache (EXC-786).
# MVN_CMD, M2_REPO, RETRY_UNIT (seconds), MVN_FALLBACK_SETTINGS, MVN_MIRROR_MARKER
# and GITHUB_EVENT_NAME can be overridden in tests.
_mvn_retry_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
mvn_retry() {
  local attempt unit=${RETRY_UNIT:-30} repo=${M2_REPO:-$HOME/.m2/repository}
  local fallback=${MVN_FALLBACK_SETTINGS:-$_mvn_retry_dir/central-fallback-settings.xml} settings=()
  local marker=${MVN_MIRROR_MARKER:-${RUNNER_TEMP:-/tmp}/maven-mirror-used}
  mkdir -p "$repo" # a first failure can leave no repository, and find would then fail under set -e
  for attempt in 1 2 3 4; do
    settings=()
    if ((attempt >= 3)) && [[ ${GITHUB_EVENT_NAME:-} == pull_request && -f $fallback ]]; then
      echo "Attempt $attempt goes through the Google Central mirror (pull request run)"
      settings=(-s "$fallback")
      touch "$marker"
    fi
    "${MVN_CMD:-mvn}" --batch-mode "${settings[@]}" "$@" && return 0
    echo "mvn $* failed (attempt $attempt)"
    find "$repo" -name '*.lastUpdated' -delete
    ((attempt < 4)) || break
    echo "retrying in $((attempt * unit))s"
    sleep $((attempt * unit))
  done
  return 1
}
