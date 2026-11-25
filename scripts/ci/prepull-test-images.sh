#!/usr/bin/env sh
# EXC-880: Testcontainers pulls its images when a test starts, and a rate-limited or flaky pull fails
# the test (ContainerFetch). Pulling each image once here, with retries, leaves the tests a local image.
# A pull that still fails is a warning: the test then tries itself, as before this step existed.
#
#   sh scripts/ci/prepull-test-images.sh                  the images the whole suite uses
#   sh scripts/ci/prepull-test-images.sh <image>...       only those images
set -eu

if [ "$#" -gt 0 ]; then
  images="$*"
else
  images="postgres:16-alpine mysql:8.4 mysql:8.0 mongo:7 nats:2.10 pgvector/pgvector:pg16 docker.io/pgsty/minio:RELEASE.2026-08-04T00-00-00Z ghcr.io/documentdb/documentdb/documentdb-local@sha256:1d40546ccf53a0229f98a0848b7c3d6d74dc35a9fa535a1d1c8f70e3bed10d23"
fi

for image in $images; do
  attempt=1
  while :; do
    if docker pull -q "$image" >/dev/null; then break; fi
    echo "pull of $image failed (try $attempt of 3)" >&2
    if [ "$attempt" -ge 3 ]; then
      echo "::warning::could not pull $image; the test will try itself"
      break
    fi
    sleep $((attempt * 15))
    attempt=$((attempt + 1))
  done
done
echo "prepull: done"
