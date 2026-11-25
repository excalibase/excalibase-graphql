#!/usr/bin/env bash
# Prints "true" when image:tag already exists in the registry, "false" when it
# is confirmed absent. Any other failure (auth, network) fails the script so a
# trunk tag is never pushed on a guess (EXC-745).
# INSPECT_CMD can replace the registry lookup in tests.
set -uo pipefail

image=${1:?usage: trunk-tag-exists.sh <image> <tag>}
tag=${2:?usage: trunk-tag-exists.sh <image> <tag>}
inspect=${INSPECT_CMD:-docker buildx imagetools inspect}

if output=$($inspect "$image:$tag" 2>&1); then
  echo "true"
  exit 0
fi

if grep -qiE 'not found|manifest unknown|no such manifest' <<<"$output"; then
  echo "false"
  exit 0
fi

echo "cannot tell whether $image:$tag exists: $output" >&2
exit 2
