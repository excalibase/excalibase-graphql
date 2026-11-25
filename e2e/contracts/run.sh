#!/usr/bin/env bash
# Starts the contract stack for the engine image in ENGINE_IMAGE, runs the contracts, and always cleans up.
# UPDATE_CONTRACTS=1 rewrites the fixtures (review the diff); extra arguments go to jest.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
compose=(docker compose -f "$here/docker-compose.contracts.yml")
export ENGINE_IMAGE="${ENGINE_IMAGE:-excalibase/excalibase-graphql}"
export CONTRACT_VARIANT="${CONTRACT_VARIANT-micronaut}"   # fixtures for the signals this framework cannot produce; empty runs the originals

cleanup() {
  "${compose[@]}" logs engine > "$here/engine.log" 2>&1 || true
  "${compose[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
  docker ps -aq --filter name=contract-engine- | xargs -r docker rm -f > /dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$here/out" && chmod 777 "$here/out" && rm -f "$here"/out/*.jsonl
"${compose[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
"${compose[@]}" up -d

wait_for() {
  for _ in $(seq 1 60); do
    curl -sf "$1" > /dev/null 2>&1 && return 0
    sleep 2
  done
  echo "not ready: $1" >&2
  return 1
}
wait_for http://127.0.0.1:14081/actuator/health
wait_for http://127.0.0.1:14181/actuator/health
wait_for http://127.0.0.1:14281/actuator/health

cd "$here/.." && npm ci --silent && npx jest contracts --runInBand --forceExit "$@"
