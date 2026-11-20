#!/usr/bin/env bash
# Live check of the Central fallback (EXC-786): the project's "central" points at a dead
# address, so a plain run cannot resolve anything, and mvn_retry must get through on its
# mirror attempt. Needs mvn and network access to the Google mirror only.
set -uo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export M2_REPO="$work/repo" RETRY_UNIT=0
mkdir -p "$work/p/.mvn"
echo '--strict-checksums' >"$work/p/.mvn/maven.config"
cat >"$work/p/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>t</groupId><artifactId>t</artifactId><version>1</version>
  <repositories><repository><id>central</id><url>http://127.0.0.1:9/maven2</url></repository></repositories>
  <pluginRepositories><pluginRepository><id>central</id><url>http://127.0.0.1:9/maven2</url></pluginRepository></pluginRepositories>
  <dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency></dependencies>
</project>
POM
cd "$work/p" || exit 1
# shellcheck source=scripts/ci/mvn-retry.sh
source "$root/scripts/ci/mvn-retry.sh"

mvn --batch-mode -q -Dmaven.repo.local="$M2_REPO" dependency:resolve >/dev/null 2>&1; plain=$?
mvn_retry -q -Dmaven.repo.local="$M2_REPO" dependency:resolve >"$work/out" 2>&1; retried=$?
cat "$work/out"
jar=$(find "$M2_REPO/junit/junit/4.13.2" -name 'junit-4.13.2.jar' 2>/dev/null | head -1)

failures=0
check() { if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected '$2', got '$3'"; failures=$((failures + 1)); fi; }
check "without the mirror a dead Central fails" "1" "$plain"
check "mvn_retry gets through on the mirror" "0" "$retried"
check "the jar was downloaded and its checksum accepted" "1" "$([[ -n $jar ]] && echo 1 || echo 0)"
exit $((failures > 0))
