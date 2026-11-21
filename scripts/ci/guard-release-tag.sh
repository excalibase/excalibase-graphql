#!/usr/bin/env bash
# guard-release-tag.sh <image> <tag>
# CD calls this before pushing a release branch build. A release-<major>.<minor>-<sha7>
# tag is what the AIO gate tests and release.yml later promotes, so it is written once:
# exit 1 if it already exists, or if the registry cannot say (EXC-806).
# INSPECT_CMD can replace the registry lookup in tests.
set -u

image=${1:?usage: guard-release-tag.sh <image> <tag>}
tag=${2:?usage: guard-release-tag.sh <image> <tag>}
here=$(cd "$(dirname "$0")" && pwd)

printf '%s' "$image" | grep -Eq '^excalibase/[a-z0-9-]+$' || { echo "::error::refusing image $image" >&2; exit 1; }
printf '%s' "$tag" | grep -Eq '^release-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-[0-9a-f]{7}$' || { echo "::error::refusing tag $tag" >&2; exit 1; }

status=0
exists=$("$here/trunk-tag-exists.sh" "$image" "$tag") || status=$?
if [ "$status" -ne 0 ]; then
  echo "::error::could not check $image:$tag" >&2
  exit 1
fi
if [ "$exists" = "true" ]; then
  echo "::error::$image:$tag already exists and is immutable; a re-run must not replace a tested image" >&2
  exit 1
fi
exit 0
