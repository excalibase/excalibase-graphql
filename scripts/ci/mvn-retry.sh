#!/usr/bin/env bash
# Source this. mvn_retry runs mvn and retries the whole goal on failure.
# Central answers a cold-cache burst with 429s and bogus "not found"s, and Maven
# remembers a not-found in *.lastUpdated and fails the next attempt without
# contacting Central, so those markers are dropped before every retry (EXC-783).
# From attempt 3 on, the run goes through Google's Central mirror (-s with
# central-fallback-settings.xml): Maven mirrors replace rather than fall back, so
# the switch has to be a whole new run (EXC-786).
# MVN_CMD, M2_REPO, RETRY_UNIT (seconds) and MVN_FALLBACK_SETTINGS can be overridden in tests.
_mvn_retry_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
mvn_retry() {
  local attempt unit=${RETRY_UNIT:-30} repo=${M2_REPO:-$HOME/.m2/repository}
  local fallback=${MVN_FALLBACK_SETTINGS:-$_mvn_retry_dir/central-fallback-settings.xml} settings=()
  mkdir -p "$repo" # a first failure can leave no repository, and find would then fail under set -e
  for attempt in 1 2 3 4; do
    settings=()
    if ((attempt >= 3)) && [[ -f $fallback ]]; then
      echo "Central failed twice, trying the Google mirror (attempt $attempt)"
      settings=(-s "$fallback")
    fi
    "${MVN_CMD:-mvn}" --batch-mode "${settings[@]}" "$@" && return 0
    echo "mvn $* failed (attempt $attempt), retrying in $((attempt * unit))s"
    find "$repo" -name '*.lastUpdated' -delete
    sleep $((attempt * unit))
  done
  return 1
}
