#!/usr/bin/env bash
# A build with -Dconsole.ui.skip=true must not pack a console an earlier build left in target/classes (a build without
# `clean`). Leaves a marker where the built console would be, builds the resources with the console skipped, and fails
# if the marker is still there; then checks that a build that does not skip leaves a console's own files alone.
set -euo pipefail
cd "$(dirname "$0")/../.."
stale=modules/excalibase-graphql-api/target/classes/console-next
mkdir -p "$stale/assets"
echo stale > "$stale/assets/stale.js"
mvn --batch-mode -q -pl modules/excalibase-graphql-api -am compile -Dconsole.ui.skip=true
if [[ -e $stale ]]; then
  echo "FAIL: a build that skips the console kept a stale target/classes/console-next"
  exit 1
fi
echo "ok: a skipped console build removes a stale console-next"
