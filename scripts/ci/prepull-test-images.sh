#!/usr/bin/env sh
# EXC-880: Testcontainers pulls its images when a test starts, and a rate-limited or flaky pull fails
# the test (ContainerFetch). Pulling each image once here, with retries, leaves the tests a local image.
# A pull that still fails is a warning: the test then tries itself, as before this step existed.
#
#   sh scripts/ci/prepull-test-images.sh
set -eu

images="postgres:16-alpine mysql:8.4 mysql:8.0 nats:2.10 pgvector/pgvector:pg16 docker.io/pgsty/minio:RELEASE.2026-08-04T00-00-00Z"

for image in $images; do
  attempt=1
  while [ "$attempt" -le 3 ]; do
    docker pull -q "$image" >/dev/null && break
    echo "pull of $image failed (try $attempt of 3)" >&2
    sleep $((attempt * 15))
    attempt=$((attempt + 1))
  done
  [ "$attempt" -le 3 ] || echo "::warning::could not pull $image; the test will try itself"
done
echo "prepull: done"
