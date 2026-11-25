#!/usr/bin/env sh
# EXC-881: CI used to pull every third-party image from Docker Hub and hit its pull rate limit, even
# logged in. The images are copied once a day into ghcr.io/excalibase/mirror/ (the Mirror images
# workflow of the platform repository, which mirrors this list too) and CI pulls them from there.
# ci/mirror-images.txt is this repository's list.
#
#   sh ci/mirror-images.sh ref <ref>            the reference CI pulls for <ref>
#   sh ci/mirror-images.sh rewrite <file>...    point the Docker Hub images in a file at the mirror
#   sh ci/mirror-images.sh sync [list]          copy the list into the mirror (needs skopeo and a login)
#   sh ci/mirror-images.sh guard                fail when CI names a Docker Hub image the list lacks
#
# A script may also `. ci/mirror-images.sh` and call mirror_ref. With IMAGE_MIRROR unset
# (a developer's machine) a reference stays what it was, so nothing needs GHCR locally.
#   IMAGE_MIRROR=ghcr.io/excalibase/mirror   what CI sets
#
# Mirror names: the Docker Hub path without the host and without library/ (the paths testcontainers
# builds from TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX), the tag kept. A reference with only a digest
# gets the tag d-<12 digest characters>.

# hub_path <ref>: the Docker Hub path of a reference, or nothing when it names another registry.
hub_path() {
  ref=$1
  case $ref in
    docker.io/*|index.docker.io/*|registry-1.docker.io/*) ref=${ref#*/} ;;
    */*)
      host=${ref%%/*}
      case $host in *.*|*:*|localhost) return 0 ;; esac
      ;;
  esac
  printf '%s\n' "${ref#library/}"
}

# mirror_ref <ref>: the reference CI pulls, the mirror's when IMAGE_MIRROR is set.
mirror_ref() {
  path=$(hub_path "$1")
  if [ -z "${IMAGE_MIRROR:-}" ] || [ -z "$path" ]; then
    printf '%s\n' "$1"
  else
    printf '%s/%s\n' "${IMAGE_MIRROR%/}" "$path"
  fi
}

# list_entries <file>: the references of a list, comments and blank lines dropped.
list_entries() {
  sed -e 's/[[:space:]]*#.*$//' -e '/^[[:space:]]*$/d' "$1"
}

# entry_repo <entry>: the path without tag or digest. entry_tag: its tag, or d-<digest> when it has none.
entry_repo() {
  e=${1%%@*}
  case ${e##*/} in *:*) e=${e%:*} ;; esac
  printf '%s\n' "$e"
}
entry_tag() {
  e=${1%%@*}
  case ${e##*/} in
    *:*) printf '%s\n' "${e##*:}" ;;
    *) d=${1#*@sha256:}; printf 'd-%s\n' "$(printf '%s' "$d" | cut -c1-12)" ;;
  esac
}
entry_digest() {
  case $1 in *@sha256:*) printf 'sha256:%s\n' "${1#*@sha256:}" ;; esac
}

# Hub repositories without a namespace live under library/.
source_repo() {
  case $1 in */*) printf '%s\n' "$1" ;; *) printf 'library/%s\n' "$1" ;; esac
}

sync_entry() {
  entry=$1
  repo=$(entry_repo "$entry")
  tag=$(entry_tag "$entry")
  digest=$(entry_digest "$entry")
  source="docker://docker.io/$(source_repo "$repo")"
  if [ -n "$digest" ]; then source="$source@$digest"; else source="$source:$tag"; fi
  target="docker://${MIRROR_REGISTRY:-ghcr.io/excalibase/mirror}/$repo:$tag"
  skopeo copy --all --preserve-digests "$source" "$target" || { echo "could not copy $entry" >&2; return 1; }
  [ -n "$digest" ] || return 0
  # A copy that changed the manifest would break every digest pin, so it is checked, not trusted.
  got=$(skopeo inspect --raw "$target" | sha256sum | cut -d' ' -f1)
  [ "sha256:$got" = "$digest" ] || { echo "$entry: the mirror holds sha256:$got, not $digest" >&2; return 1; }
}

cmd_sync() {
  list=${1:-$MIRROR_LIST_FILE}
  failed=0
  for entry in $(list_entries "$list"); do
    echo "mirror $entry"
    sync_entry "$entry" || failed=$((failed + 1))
  done
  [ "$failed" -eq 0 ] || { echo "$failed image(s) not mirrored" >&2; return 1; }
}

# rewrite_stream: stdin to stdout, each explicit docker.io reference whose image the list names
# pointed at the mirror. Images CI builds itself keep their names.
rewrite_stream() {
  [ -n "${IMAGE_MIRROR:-}" ] || { cat; return; }
  awk -v mirror="${IMAGE_MIRROR%/}" -v list="$MIRROR_LIST_FILE" '
    BEGIN {
      while ((getline line < list) > 0) {
        sub(/[ \t]*#.*$/, "", line)
        if (line == "") continue
        sub(/@.*/, "", line)
        if (line ~ /:[^\/]*$/) line = substr(line, 1, match(line, /:[^\/]*$/) - 1)
        names[line] = 1
      }
    }
    {
      out = ""; rest = $0
      while (match(rest, "docker[.]io/(library/)?[a-z0-9][a-z0-9._/-]*")) {
        token = substr(rest, RSTART, RLENGTH)
        path = token; sub(/^docker[.]io[/]/, "", path); sub(/^library[/]/, "", path)
        # provisioning and studio are built by CI under their release names: never pulled, never mirrored.
        if ((path in names) && path !~ /^excalibase[/](provisioning|studio)$/) token = mirror "/" path
        out = out substr(rest, 1, RSTART - 1) token
        rest = substr(rest, RSTART + RLENGTH)
      }
      print out rest
    }'
}

cmd_rewrite() {
  for file in "$@"; do
    tmp=$(mktemp)
    rewrite_stream < "$file" > "$tmp" && cat "$tmp" > "$file"
    rm -f "$tmp"
  done
}

# An untagged, undigested reference is :latest.
untagged_is_latest() {
  while read -r ref; do
    case ${ref##*/} in *:*|*@*) printf '%s\n' "$ref" ;; *) printf '%s:latest\n' "$ref" ;; esac
  done
}

# docker_cli_images: stdin lines, the image each `docker run|pull|create` names (the first word that is
# not an option or an option's value).
docker_cli_images() {
  # A quoted argument with spaces (--health-cmd "pg_isready -U app") is one word.
  sed -E 's/"[^"]* [^"]*"/Q/g' | awk 'BEGIN {
         n = split("-e -v -p -u -w -l -h --name --network --net --entrypoint --env --env-file --volume --publish --label --restart --user --workdir --add-host --cap-add --cap-drop --memory --cpus --platform --health-cmd --health-interval --health-retries --health-timeout --stop-signal --mount --tmpfs --device --dns --hostname --ulimit --security-opt --group-add --pid --ipc --runtime --cgroupns --pull --userns --shm-size --group", names, " ")
         for (k = 1; k <= n; k++) takes[names[k]] = 1
       }
       {
         m = split($0, w, /[ \t]+/)
         for (i = 1; i < m; i++) {
           if (w[i] != "docker" || (w[i + 1] != "run" && w[i + 1] != "pull" && w[i + 1] != "create")) continue
           j = i + 2
           while (j <= m && w[j] ~ /^-/) { if (w[j] !~ /=/ && (w[j] in takes)) j++; j++ }
           if (j <= m && w[j] !~ /[$(){}<>|;]/) print w[j]
         }
       }'
}

# java_literal_images: stdin Java text; the string literals that are image references.
java_literal_images() {
  repos=$(list_entries "$MIRROR_LIST_FILE" | while read -r entry; do entry_repo "$entry"; done | sort -u)
  grep -oE '"[a-z0-9][a-z0-9._-]*(/[a-z0-9._-]+)*(:[A-Za-z0-9._-]+)?(@sha256:[0-9a-f]+)?"' | tr -d '"' |
    while read -r literal; do
      case $literal in *:*|*@sha256:*) ;; *) continue ;; esac
      repo=$(entry_repo "$literal")
      if printf '%s\n' "$repos" | grep -qxF "$repo"; then printf '%s\n' "$literal"
      elif printf '%s\n' "$literal" | grep -qE '^[a-z0-9._-]+(/[a-z0-9._-]+)+:(v?[0-9][A-Za-z0-9._-]*|latest)(@sha256:[0-9a-f]+)?$'; then printf '%s\n' "$literal"
      fi
    done
}

# guard_candidates: every Docker Hub image the files CI runs name, one normalized path per line.
# Sources: explicit docker.io references, compose and workflow `image:` lines, the FROM lines of the
# Dockerfiles, the image arguments of the Java tests' containers, and prepull's default list.
guard_candidates() {
  (
    # A file without a match is no failure, whatever set -e the caller runs under.
    set +e
    cd "$MIRROR_ROOT"
    scanned=$(git ls-files .github scripts e2e Dockerfile Dockerfile.native 'docker-compose*.yml' |
      grep -vE '(^|/)(docker-compose\.benchmark\.yml|mirror-images\.(txt|sh)|mirror-images-test\.sh)$|\.md$|\.sql$|/node_modules/' || true)
    java_files=$(git ls-files 'modules/*.java')
    # shellcheck disable=SC2086 # one word per file
    {
      grep -hoE 'docker\.io/[A-Za-z0-9][A-Za-z0-9._/-]*(:[A-Za-z0-9._-]+)?(@sha256:[0-9a-f]+)?' /dev/null $scanned $java_files 2>/dev/null
      grep -hE '^[[:space:]]*(-[[:space:]]+)?image:[[:space:]]+[^[:space:]]+[[:space:]]*$' /dev/null $scanned 2>/dev/null |
        sed -E 's/^[[:space:]]*(-[[:space:]]+)?image:[[:space:]]+//; s/[[:space:]]+$//; s/[$][{]HUB_MIRROR:-[}]//' | tr -d "\"'" |
        grep -v '[$]' | while read -r ref; do
          # An untagged reference is :latest.
          case ${ref##*/} in *:*|*@*) printf '%s\n' "$ref" ;; *) printf '%s:latest\n' "$ref" ;; esac
        done
      # The images a test hands to a container, or names in a constant. Lines are joined first: a
      # call split across lines (DockerImageName.parse(\n "x:1")) is the same call.
      cat /dev/null $java_files 2>/dev/null | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' |
        grep -oE '(DockerImageName ?\. ?parse|Container<[^>]*>|new Backend) ?\( ?"[^"]+"' |
        sed -E 's/.*"([^"]+)"$/\1/' | grep -E '^[a-z0-9][a-z0-9._/-]*(:[A-Za-z0-9._-]+)?(@sha256:[0-9a-f]+)?$' | untagged_is_latest
      # Any string literal shaped like an image reference, whatever its constant is called: name:tag or
      # name@sha256, where the name is one the list holds (a tag the list lacks) or a user/repo path with a
      # version-like tag. "host:8080", "metrics:read" and plain words are left alone.
      cat /dev/null $java_files 2>/dev/null | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g' | java_literal_images
      # Images named in environment variables of workflows (K3S_IMAGE: rancher/k3s:v1).
      grep -hE '^[[:space:]]+[A-Z0-9_]*IMAGE[A-Z0-9_]*:[[:space:]]+[^[:space:]]+[[:space:]]*$' /dev/null $(printf '%s\n' $scanned | grep '^\.github/') 2>/dev/null |
        sed -E 's/^[[:space:]]+[A-Z0-9_]+:[[:space:]]+//; s/[[:space:]]+$//' | tr -d "\"'" | grep -v '[$]' | untagged_is_latest
      # `docker run`, `docker pull` and `docker create` in scripts and workflow run: lines, continuation lines joined.
      for file in $scanned; do
        sed -e ':a' -e '/\\$/N' -e 's/\\\n/ /' -e 'ta' "$file" | docker_cli_images
      done | tr -d "\"'" | grep -E '^[a-z0-9][a-z0-9._/-]*(:[A-Za-z0-9._-]+)?(@sha256:[0-9a-f]+)?$' | untagged_is_latest
      # prepull-test-images.sh names a default list of its own.
      sed -n 's/^  images="\(.*\)"$/\1/p' scripts/ci/prepull-test-images.sh 2>/dev/null | tr ' ' '\n'
      for dockerfile in $(printf '%s\n' $scanned | grep -E '(^|/)Dockerfile[^/]*$'); do
        awk 'toupper($1) == "FROM" {
               ref = $2; if (ref ~ /^--platform/) ref = $3
               gsub(/[$][{]HUB_MIRROR[}]/, "", ref)
               if (ref !~ /[$]/ && ref != "scratch" && !(ref in stage)) print ref
               for (i = 2; i < NF; i++) if (toupper($i) == "AS") stage[$(i + 1)] = 1
             }
             toupper($1) == "COPY" {
               for (i = 2; i <= NF; i++) if ($i ~ /^--from=/) {
                 ref = substr($i, 8)
                 if (ref !~ /[$]/ && !(ref in stage) && ref !~ /^[0-9]+$/) print ref
               }
             }' "$dockerfile" | untagged_is_latest
      done
    } | while read -r ref; do
      path=$(hub_path "$ref")
      [ -n "$path" ] || continue
      case $path in *:*|*@sha256:*) ;; *) continue ;; esac
      # Built by CI itself, or a name a test uses to prove a pull fails.
      case $path in excalibase/excalibase-graphql:*|*-candidate:*|*:local|*:native|*:9.9.9|pgsty/minio:RELEASE.1) continue ;; esac
      printf '%s\n' "$path"
    done | sort -u
  )
}

# in_list <path>: 0 when the list names the path: exactly when it has a digest, by name and tag otherwise.
in_list() {
  candidate=$1
  case $candidate in
    *@sha256:*) list_entries "$MIRROR_LIST_FILE" | grep -qxF "$candidate" && return 0
                # tagless digest entries match a tagged candidate with the same digest
                list_entries "$MIRROR_LIST_FILE" | grep -qF "@${candidate#*@}" ;;
    *) list_entries "$MIRROR_LIST_FILE" | sed 's/@sha256:.*$//' | grep -qxF "$candidate" ;;
  esac
}

# release_mirror_uses: workflow files that push an image (push: true) or release one (cd, release*) and
# still name HUB_MIRROR. What is pushed must never be built from the mirror: its base layers come from Docker Hub.
release_mirror_uses() {
  (
    set +e
    cd "$MIRROR_ROOT"
    for file in $(git ls-files '.github/workflows/*.yml'); do
      case ${file##*/} in
        cd.yml|release*.yml) pushes=1 ;;
        *) grep -qE '^[[:space:]]*push:[[:space:]]*true|docker[[:space:]]+push|buildx.*--push|--push' "$file" && pushes=1 || pushes=0 ;;
      esac
      [ "$pushes" -eq 1 ] || continue
      grep -nH -E 'HUB_MIRROR|IMAGE_MIRROR|TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX|excalibase/mirror' "$file" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#'
    done
    true
  )
}

cmd_guard() {
  pushed=$(release_mirror_uses)
  [ -z "$pushed" ] || {
    echo "a workflow that pushes or releases an image uses the mirror; what ships is never built from the mirror:" >&2
    echo "$pushed" | sed 's/^/  /' >&2
    return 1
  }
  missing=$(guard_candidates | while read -r path; do
    in_list "$path" || echo "$path"
  done)
  [ -z "$missing" ] || {
    echo "CI names Docker Hub images that ci/mirror-images.txt does not list:" >&2
    echo "$missing" | sed 's/^/  /' >&2
    echo "Docker Hub rate-limits CI, so CI pulls these from ghcr.io/excalibase/mirror/ instead (EXC-881)." >&2
    echo "Add each line above to ci/mirror-images.txt, then run the 'Mirror images' workflow in excalibase/excalibase" >&2
    echo "with engine_ref=<your branch>, so the mirror holds the image before this PR merges; the daily run keeps it fresh." >&2
    return 1
  }
  echo "every Docker Hub image CI names is in ci/mirror-images.txt"
}

# Run as a command, not when sourced.
case ${0##*/} in
  mirror-images.sh)
    set -eu
    MIRROR_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
    MIRROR_LIST_FILE=${MIRROR_LIST:-$MIRROR_ROOT/ci/mirror-images.txt}
    cmd=${1:-}
    [ "$#" -eq 0 ] || shift
    case $cmd in
      ref) mirror_ref "${1:?usage: ref <ref>}" ;;
      rewrite) cmd_rewrite "$@" ;;
      sync) cmd_sync "$@" ;;
      guard) cmd_guard ;;
      *) echo "usage: $0 ref <ref> | rewrite <file>... | sync [list] | guard" >&2; exit 2 ;;
    esac
    ;;
esac
