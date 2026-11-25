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
notests="$root/scripts/ci/test-shards-notests.txt"
SHARDS=${SHARDS:-4}

# What Surefire runs by default: a class whose file name matches *Test, Test*, *Tests or *TestCase and that is not
# abstract. The name is the only thing read, never the body, so no way of writing @Test can hide a class from a shard.
# A class that matches but has no test method (a helper such as TestPki) is harmless in a shard, and is listed in
# test-shards-notests.txt so the check can tell it from a class that was skipped.
all_classes() {
  find "$root"/modules -path '*/src/test/java/*' \
    \( -name '*Test.java' -o -name 'Test*.java' -o -name '*Tests.java' -o -name '*TestCase.java' \) -print0 |
    while IFS= read -r -d '' file; do
      simple=$(basename "$file" .java)
      if grep -Eq "^[a-z ]*abstract (class|record) $simple\\b" "$file"; then continue; fi
      name=${file#*/src/test/java/}
      name=${name%.java}
      echo "${name//\//.}"
    done | sort
}

# Classes that Surefire is handed and that have no test method, so no report is written for them.
without_tests() {
  grep -Ev '^\s*(#|$)' "$notests"
}

fail() {
  echo "::error::$*" >&2
  exit 1
}

[[ -r $manifest ]] || fail "cannot read $manifest"
[[ -r $notests ]] || fail "cannot read $notests"
if awk -v max="$SHARDS" '/^[[:space:]]*(#|$)/ { next } NF != 2 || $2 !~ /^[0-9]+$/ || $2 < 1 || $2 > max { bad = 1 } END { exit !bad }' "$manifest"; then
  fail "$manifest has a line that is not '<class> <shard 1..$SHARDS>'"
fi

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
    # what Surefire hands to the tests, minus the classes known to hold no test; one line per class and module
    comm -23 <(all_classes) <(without_tests | sort) >"$expected"
    cat "$@" | sort >"$ran"
    # one line per class and module: the same class name in two modules is listed twice and has to run twice
    status=0
    missing=$(comm -23 "$expected" "$ran")
    surplus=$(comm -13 "$expected" "$ran")
    [[ -z $missing ]] || { echo "::error::test classes that no shard ran (a class with no test method belongs in test-shards-notests.txt):"; echo "$missing"; status=1; }
    [[ -z $surplus ]] || { echo "::error::classes that ran more than once, or that the source scan does not list:"; echo "$surplus"; status=1; }
    [[ $status -eq 0 ]] && echo "every one of $(wc -l <"$expected") test classes ran exactly once"
    exit $status
    ;;
  *) echo "usage: $0 all|select N|check FILE..." >&2; exit 2 ;;
esac
