#!/usr/bin/env bash
# EXC-885: the engine's tests run in parallel CI shards. This script decides which test class runs in which shard
# and checks afterwards that every class ran exactly once.
#
#   test-shards.sh all                  every test class, one fully qualified name per line
#   test-shards.sh select N             the classes of shard N (1..SHARDS) as a comma list for -Dtest
#   test-shards.sh check FILE...        FILE is the list of classes one shard ran; fails unless every class ran once
#
# A class named in test-shards.txt (the slow ones, balanced by measured time) runs in the shard written there.
# Any other class, including a new one, goes to a shard picked from a checksum of its name, so a new test always runs.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
manifest="$root/scripts/ci/test-shards.txt"
SHARDS=${SHARDS:-4}

# Surefire's default includes (*Test, Test*, *Tests, *TestCase); abstract classes and helpers without a test method
# are not run by Surefire, so they are not listed.
all_classes() {
  find "$root"/modules -path '*/src/test/java/*' \
    \( -name '*Test.java' -o -name 'Test*.java' -o -name '*Tests.java' -o -name '*TestCase.java' \) -print0 |
    while IFS= read -r -d '' file; do
      # a subclass inherits its test methods, so "extends" counts as much as an @Test of its own
      grep -Eq '^\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b|^[a-z ]*class \w+ extends ' "$file" || continue
      ! grep -Eq '^(public )?abstract class' "$file" || continue
      name=${file#*/src/test/java/}
      name=${name%.java}
      echo "${name//\//.}"
    done | sort
}

shard_of() {
  local pinned
  pinned=$(awk -v c="$1" '$1 == c { print $2 }' "$manifest")
  if [[ -n $pinned ]]; then
    echo "$pinned"
  else
    echo $(( $(printf '%s' "$1" | cksum | cut -d' ' -f1) % SHARDS + 1 ))
  fi
}

case ${1:-} in
  all) all_classes ;;
  select)
    n=${2:?shard number}
    all_classes | while read -r class; do
      if [[ $(shard_of "$class") == "$n" ]]; then echo "$class"; fi
    done | sort -u | paste -sd, -
    ;;
  check)
    shift
    [[ $# -gt 0 ]] || { echo "usage: $0 check FILE..." >&2; exit 2; }
    expected=$(mktemp)
    ran=$(mktemp)
    trap 'rm -f "$expected" "$ran"' EXIT
    all_classes >"$expected"
    cat "$@" | sort >"$ran"
    # one line per class and module: the same class name in two modules is listed twice and has to run twice
    status=0
    missing=$(comm -23 "$expected" "$ran")
    surplus=$(comm -13 "$expected" "$ran")
    [[ -z $missing ]] || { echo "::error::test classes that no shard ran:"; echo "$missing"; status=1; }
    [[ -z $surplus ]] || { echo "::error::classes that ran more than once, or that the source scan does not list:"; echo "$surplus"; status=1; }
    [[ $status -eq 0 ]] && echo "every one of $(wc -l <"$expected") test classes ran exactly once"
    exit $status
    ;;
  *) echo "usage: $0 all|select N|check FILE..." >&2; exit 2 ;;
esac
