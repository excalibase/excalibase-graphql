#!/usr/bin/env sh
# EXC-880: Testcontainers pulls its images when a test starts, and a rate-limited or flaky pull fails
# the test (ContainerFetch). Pulling each image once here, with retries, leaves the tests a local image.
# A pull that still fails is a warning: the test then tries itself, as before this step existed.
# EXC-881: with IMAGE_MIRROR set (CI) each Docker Hub image is pulled from ghcr.io/excalibase/mirror/,
# the name Testcontainers asks for through TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX.
#
#   sh scripts/ci/prepull-test-images.sh                  the images the whole suite uses
#   sh scripts/ci/prepull-test-images.sh <image>...       only those images
set -eu

# shellcheck disable=SC1091
. "$(dirname "$0")/../../ci/mirror-images.sh"

if [ "$#" -gt 0 ]; then
  images="$*"
else
  images="postgres:16-alpine mysql:8.4 mysql:8.0 mongo:7 nats:2.10 pgvector/pgvector:pg16 pgsty/minio:RELEASE.2026-08-04T00-00-00Z@sha256:b6bfe7239bfc83fb90d31612d9704d86039dd714f7904b3f1ad68f211e602372 testcontainers/ryuk:0.12.0 ghcr.io/documentdb/documentdb/documentdb-local@sha256:1d40546ccf53a0229f98a0848b7c3d6d74dc35a9fa535a1d1c8f70e3bed10d23"
fi

for image in $images; do
  source=$(mirror_ref "$image")
  attempt=1
  while :; do
    if docker pull -q "$source" >/dev/null; then break; fi
    echo "pull of $source failed (try $attempt of 3)" >&2
    if [ "$attempt" -ge 3 ]; then
      echo "::warning::could not pull $source; the test will try itself"
      break
    fi
    sleep $((attempt * 15))
    attempt=$((attempt + 1))
  done
done
echo "prepull: done"
