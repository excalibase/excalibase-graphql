#!/usr/bin/env bash
# Source this. mvn_retry runs mvn and retries the whole goal on failure.
# Central answers a cold-cache burst with 429s and bogus "not found"s, and Maven
# remembers a not-found in *.lastUpdated and fails the next attempt without
# contacting Central, so those markers are dropped before every retry (EXC-783).
# MVN_CMD, M2_REPO and RETRY_UNIT (seconds) can be overridden in tests.
mvn_retry() {
  local attempt unit=${RETRY_UNIT:-30} repo=${M2_REPO:-$HOME/.m2/repository}
  mkdir -p "$repo" # a first failure can leave no repository, and find would then fail under set -e
  for attempt in 1 2 3 4; do
    "${MVN_CMD:-mvn}" --batch-mode "$@" && return 0
    echo "mvn $* failed (attempt $attempt), retrying in $((attempt * unit))s"
    find "$repo" -name '*.lastUpdated' -delete
    sleep $((attempt * unit))
  done
  return 1
}
