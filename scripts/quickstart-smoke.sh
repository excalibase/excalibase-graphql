#!/usr/bin/env bash
# Runs the README quickstart exactly as a new visitor would: a copy of the
# tracked files only (no generated keys), `docker compose up -d`, then the
# README's own curl commands. Fails unless both calls return sample rows.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
workdir=$(mktemp -d)
project=quickstart-smoke

cleanup() {
  (cd "$workdir" && docker compose -p "$project" logs --no-color excalibase-app excalibase-auth mock-vault 2>/dev/null | tail -n 60) || true
  (cd "$workdir" && docker compose -p "$project" down -v --remove-orphans >/dev/null 2>&1) || true
  rm -rf "$workdir"
}
trap cleanup EXIT

(cd "$repo" && git ls-files -z --cached --others --exclude-standard | tar --null -T - -cf -) | tar -xf - -C "$workdir"

# The first ```bash block under "## Quickstart" in README.md.
block=$(awk '/^## Quickstart/{q=1} q&&/^```bash/{b=1;next} b&&/^```/{exit} b' "$workdir/README.md")
[ -n "$block" ] || { echo "no quickstart block in README.md" >&2; exit 1; }

output=$(cd "$workdir" && COMPOSE_PROJECT_NAME=$project bash -euo pipefail -c "$block")
echo "$output"

grep -q '"data":\[{' <<<"$output" || { echo "REST call returned no customer rows" >&2; exit 1; }
grep -q '"hanaCustomer":\[{' <<<"$output" || { echo "GraphQL call returned no customer rows" >&2; exit 1; }
echo "quickstart OK"
