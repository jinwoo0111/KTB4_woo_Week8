#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
java_21_home="$(/usr/libexec/java_home -v 21)"
application_jar="${repository_root}/build/libs/community-0.0.1-SNAPSHOT.jar"

if [[ ! -f "${application_jar}" ]]; then
    echo "Application JAR not found: ${application_jar}" >&2
    exit 1
fi

cd "${repository_root}"

exec "${java_21_home}/bin/java" \
    -Xms1g \
    -Xmx1g \
    -jar "${application_jar}" \
    --spring.profiles.active=benchmark \
    --server.address=0.0.0.0 \
    --server.port=18084 \
    --app.benchmark.generator.enabled=false
