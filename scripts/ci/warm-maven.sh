#!/usr/bin/env bash
# The goals that fill ~/.m2/repository for every Maven job (EXC-783, EXC-786). The cache
# action runs this under a timeout, so a hung Central fails the step instead of the job.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/ci/mvn-retry.sh

# one source of truth for the Sonar plugin version: the workflow that runs it
sonar_plugin=$(grep -o 'sonar-maven-plugin:[0-9.]*' .github/workflows/sonar.yml | head -1)
# the reachability metadata is versioned with the native plugin, so it follows the pom
graalvm_buildtools=$(sed -n 's|.*<native-maven-plugin.version>\(.*\)</native-maven-plugin.version>.*|\1|p' pom.xml)
[[ -n $graalvm_buildtools ]] || { echo "native-maven-plugin.version not found in pom.xml"; exit 1; }

mvn_retry -Pnative dependency:go-offline
# clean is what `make build` runs; the first build also fetches the .meta/prefixes-*.txt files
mvn_retry clean verify -DskipTests
# the surefire provider is only resolved when a test actually runs
mvn_retry verify -pl modules/excalibase-rls-core
# native: the micronaut-graal annotation processor and the reachability metadata
mvn_retry -Pnative -pl modules/excalibase-graphql-api -am compile
mvn_retry dependency:get -Dartifact=org.graalvm.buildtools:graalvm-reachability-metadata:"${graalvm_buildtools}":zip:repository
mvn_retry dependency:get -Dartifact=org.sonarsource.scanner.maven:"${sonar_plugin}"
# a half-downloaded file must never be cached
find ~/.m2/repository \( -name '*.lastUpdated' -o -name '*.part' -o -name '*.part.lock' \) -delete
