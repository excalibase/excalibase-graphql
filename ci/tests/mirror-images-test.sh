#!/usr/bin/env sh
# EXC-881: the mirror's naming, the rewrite of compose files and the guard.
#   sh ci/tests/mirror-images-test.sh
set -u

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TOOL="$DIR/mirror-images.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
failed=0

expect() { # <name> <want> <got>
  if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: want '$2', got '$3'"; failed=$((failed + 1)); fi
}

# shellcheck disable=SC1090
. "$TOOL"

expect "library image loses its host and library/" "postgres:16" "$(hub_path docker.io/library/postgres:16)"
expect "implicit library image" "postgres:16" "$(hub_path postgres:16)"
expect "user image keeps its path" "grafana/loki:3.7.8" "$(hub_path docker.io/grafana/loki:3.7.8)"
expect "implicit user image" "grafana/loki:3.7.8" "$(hub_path grafana/loki:3.7.8)"
expect "ghcr image is not Docker Hub" "" "$(hub_path ghcr.io/cloudnative-pg/postgresql@sha256:abc)"
expect "registry with a port is not Docker Hub" "" "$(hub_path localhost:5000/app:1)"

unset IMAGE_MIRROR
expect "without a mirror a reference stays" "docker.io/library/postgres:16" "$(mirror_ref docker.io/library/postgres:16)"
IMAGE_MIRROR=ghcr.io/excalibase/mirror
expect "library image in the mirror" "ghcr.io/excalibase/mirror/postgres:16" "$(mirror_ref docker.io/library/postgres:16)"
expect "digest kept in the mirror" "ghcr.io/excalibase/mirror/grafana/loki:3@sha256:aa" "$(mirror_ref docker.io/grafana/loki:3@sha256:aa)"
expect "other registries are untouched" "ghcr.io/x/y:1" "$(mirror_ref ghcr.io/x/y:1)"
IMAGE_MIRROR=ghcr.io/excalibase/mirror/
expect "a trailing slash is tolerated" "ghcr.io/excalibase/mirror/nats:2.10" "$(mirror_ref nats:2.10)"
unset IMAGE_MIRROR

expect "entry repo" "grafana/loki" "$(entry_repo grafana/loki:3.7.8@sha256:aa)"
expect "entry tag" "3.7.8" "$(entry_tag grafana/loki:3.7.8@sha256:aa)"
expect "digest-only entry gets a derived tag" "d-0123456789ab" "$(entry_tag node@sha256:0123456789abcdef)"
expect "digest-only entry repo" "node" "$(entry_repo node@sha256:0123456789abcdef)"
expect "source repo of a library image" "library/postgres" "$(source_repo postgres)"
expect "source repo of a user image" "grafana/loki" "$(source_repo grafana/loki)"

# rewrite: only listed images move; the platform's own build keeps its name.
printf 'postgres:16\ngrafana/loki:3.7.8@sha256:aa\nexcalibase/excalibase-graphql:1.5.0\nexcalibase/provisioning:latest\n' > "$WORK/list.txt"
cat > "$WORK/compose.yaml" <<'YAML'
    image: docker.io/library/postgres:16
    image: docker.io/grafana/loki:3.7.8@sha256:aa
    image: docker.io/excalibase/provisioning:ci
    image: ${PLATFORM_TAG:-docker.io/excalibase/provisioning:${TAG:-latest}}
    image: ${GRAPHQL_IMAGE:-docker.io/excalibase/excalibase-graphql:${TAG:-1.5.0}}
    image: ghcr.io/x/y:1
YAML
MIRROR_LIST="$WORK/list.txt" IMAGE_MIRROR=ghcr.io/m sh "$TOOL" rewrite "$WORK/compose.yaml"
expect "rewrite" "    image: ghcr.io/m/postgres:16
    image: ghcr.io/m/grafana/loki:3.7.8@sha256:aa
    image: docker.io/excalibase/provisioning:ci
    image: \${PLATFORM_TAG:-docker.io/excalibase/provisioning:\${TAG:-latest}}
    image: \${GRAPHQL_IMAGE:-ghcr.io/m/excalibase/excalibase-graphql:\${TAG:-1.5.0}}
    image: ghcr.io/x/y:1" "$(cat "$WORK/compose.yaml")"
printf '    image: docker.io/library/postgres:16\n' > "$WORK/local.yaml"
MIRROR_LIST="$WORK/list.txt" sh "$TOOL" rewrite "$WORK/local.yaml"
expect "rewrite without a mirror changes nothing" "    image: docker.io/library/postgres:16" "$(cat "$WORK/local.yaml")"

# prepull: pulls through the mirror when there is one, Docker Hub otherwise.
mkdir "$WORK/bin"
printf '#!/usr/bin/env sh\necho "$*" >> "$WORK/calls"\n' > "$WORK/bin/docker"
chmod +x "$WORK/bin/docker"
export WORK
: > "$WORK/calls"
PATH="$WORK/bin:$PATH" IMAGE_MIRROR=ghcr.io/excalibase/mirror sh "$DIR/../scripts/ci/prepull-test-images.sh" postgres:16-alpine pgvector/pgvector:pg16 >/dev/null
expect "prepull uses the mirror" "pull -q ghcr.io/excalibase/mirror/postgres:16-alpine
pull -q ghcr.io/excalibase/mirror/pgvector/pgvector:pg16" "$(cat "$WORK/calls")"
: > "$WORK/calls"
PATH="$WORK/bin:$PATH" sh "$DIR/../scripts/ci/prepull-test-images.sh" postgres:16-alpine >/dev/null
expect "prepull without a mirror pulls Docker Hub" "pull -q postgres:16-alpine" "$(cat "$WORK/calls")"

# guard, case by case: a throwaway repository holds one injected reference the guard has to catch.
fixture() { # <name>: a git repository with this repository's list and script
  dir="$WORK/fx-$1"; rm -rf "$dir"; mkdir -p "$dir/ci"
  cp "$DIR/mirror-images.sh" "$DIR/mirror-images.txt" "$dir/ci/"
  git -C "$dir" init -q
}
guard_in() { # <fixture> : runs the guard there, output in $WORK/out
  git -C "$WORK/fx-$1" add -A
  git -C "$WORK/fx-$1" -c user.email=t@t -c user.name=t commit -qm x --allow-empty
  sh "$WORK/fx-$1/ci/mirror-images.sh" guard >"$WORK/out" 2>&1
}
catches() { # <case> <needle> <path> <content>
  fixture "$1"; mkdir -p "$WORK/fx-$1/$(dirname "$3")"; printf '%s\n' "$4" > "$WORK/fx-$1/$3"
  if guard_in "$1"; then echo "FAIL guard missed: $1"; failed=$((failed + 1))
  elif grep -qF -- "$2" "$WORK/out"; then echo "PASS guard catches $1"
  else echo "FAIL guard failed on $1 without naming $2: $(cat "$WORK/out")"; failed=$((failed + 1)); fi
}
passes() { # <case> <path> <content>
  fixture "$1"; mkdir -p "$WORK/fx-$1/$(dirname "$2")"; printf '%s\n' "$3" > "$WORK/fx-$1/$2"
  if guard_in "$1"; then echo "PASS guard accepts $1"; else echo "FAIL guard refused $1: $(cat "$WORK/out")"; failed=$((failed + 1)); fi
}
catches "docker run in a script" "nosuch/run:1" scripts/x.sh 'docker run --rm -e A=b -v /a:/b -p 80:80 --name n nosuch/run:1 cmd'
catches "docker run continued over lines" "nosuch/cont:1" scripts/x.sh 'docker run -d \
  --name n \
  nosuch/cont:1'
catches "docker pull in a workflow run" "nosuch/pull:2" .github/workflows/w.yml 'jobs:
  j:
    steps:
      - run: docker pull nosuch/pull:2'
catches "docker create" "nosuch/create:1" scripts/x.sh 'docker create nosuch/create:1'
catches "an image in a workflow env value" "nosuch/k3s:3" .github/workflows/w.yml 'env:
  K3S_IMAGE: nosuch/k3s:3'
catches "a container constructor split over lines" "nosuch/split:4" modules/m/src/test/java/A.java 'class A { Object c = new GenericContainer<>(
        "nosuch/split:4"); }'
catches "DockerImageName.parse split over lines" "nosuch/parse:4" modules/m/src/test/java/A.java 'class A { Object c = DockerImageName.parse(
   "nosuch/parse:4"); }'
catches "an image constant" "nosuch/const:6" modules/m/src/test/java/A.java 'class A { static final String TEST_IMAGE = "nosuch/const:6"; }'
catches "an untagged container image" "nosuchuntagged:latest" modules/m/src/test/java/A.java 'class A { Object c = new GenericContainer<>("nosuchuntagged"); }'
catches "COPY --from an image" "nosuch/copy:5" Dockerfile 'FROM scratch AS build
COPY --from=nosuch/copy:5 /a /b'
catches "a pushing workflow that names HUB_MIRROR" "p.yml" .github/workflows/p.yml 'jobs:
  j:
    steps:
      - uses: docker/build-push-action@x
        with:
          push: true
          build-args: HUB_MIRROR=ghcr.io/excalibase/mirror/'
catches "a pushing workflow that names the mirror path" "q.yml" .github/workflows/q.yml 'jobs:
  j:
    steps:
      - run: docker push x
      - run: echo ghcr.io/excalibase/mirror/postgres'
catches "a docker push step that sets IMAGE_MIRROR" "r.yml" .github/workflows/r.yml 'env:
  IMAGE_MIRROR: x
jobs:
  j:
    steps:
      - run: docker buildx build --push -t a/b .'
catches "DockerImageName . parse with spaces" "nosuch/ws:7" modules/m/src/test/java/A.java 'class A { Object c = DockerImageName . parse (
   "nosuch/ws:7"); }'
catches "a constant of any name holding a tag the list lacks" "postgres:15" modules/m/src/test/java/A.java 'class A { static final String BASE = "postgres:15"; }'
catches "a constant shaped like user/repo:tag" "nosuch/user:1.2" modules/m/src/test/java/A.java 'class A { static final String SOMETHING = "nosuch/user:1.2"; }'
passes "host:port, scope and plain-word literals" modules/m/src/test/java/A.java 'class A { String a = "host:8080"; String b = "metrics:read"; String c = "plain"; String d = "application/json"; }'
passes "a stage copied from, options and volumes" scripts/x.sh 'docker run --rm -e A=b -v /a:/b $IMAGE cmd'
passes "COPY --from a stage" Dockerfile 'FROM scratch AS build
COPY --from=build /a /b'
passes "a build workflow that mirrors but does not push" .github/workflows/b.yml 'env:
  HUB_MIRROR: ghcr.io/excalibase/mirror/'
passes "old and new as Java constants" modules/m/src/main/java/A.java 'class A { static final String IMAGE_OLD = "old"; }'

# guard: the shipped list passes; a list without an image CI uses fails and says how to add it.
sh "$TOOL" guard >/dev/null 2>&1 && echo "PASS guard passes on the shipped list" || { echo "FAIL guard fails on the shipped list"; failed=$((failed + 1)); }
grep -v '^wiremock/' "$DIR/mirror-images.txt" > "$WORK/short.txt"
if MIRROR_LIST="$WORK/short.txt" sh "$TOOL" guard >"$WORK/out" 2>&1; then
  echo "FAIL guard passed with wiremock missing"; failed=$((failed + 1))
else
  grep -q 'wiremock/wiremock:3.10.0' "$WORK/out" && grep -q 'mirror-images.txt' "$WORK/out" \
    && echo "PASS guard names the missing image and the file" || { echo "FAIL guard message: $(cat "$WORK/out")"; failed=$((failed + 1)); }
fi

[ "$failed" -eq 0 ] || { echo "$failed check(s) failed"; exit 1; }
echo "all mirror checks passed"
