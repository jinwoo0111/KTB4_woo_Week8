#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
canonical_dir="${repository_root}/benchmark-data/postgresql/canonical"
canonical_dump="${canonical_dir}/community-benchmark-100k.dump"
canonical_checksum="${canonical_dir}/community-benchmark-100k.dump.sha256"
experiment_database="community_benchmark_fts"
sql_dir="${repository_root}/benchmark/postgresql/fts"
preparation_id="postgresql-fts-preparation-$(date '+%Y%m%d-%H%M%S-kst')"
result_dir="${FTS_PREPARATION_RESULT_DIR:-${repository_root}/benchmark-data/postgresql/fts/${preparation_id}}"
jar_file="${repository_root}/build/libs/community-0.0.1-SNAPSHOT.jar"

if [[ -z "${BENCHMARK_POSTGRES_PASSWORD:-}" && -f "${repository_root}/.env" ]]; then
    BENCHMARK_POSTGRES_PASSWORD="$(
        sed -n 's/^BENCHMARK_POSTGRES_PASSWORD=//p' "${repository_root}/.env" \
            | tail -n 1
    )"
    export BENCHMARK_POSTGRES_PASSWORD
fi

: "${BENCHMARK_POSTGRES_PASSWORD:?BENCHMARK_POSTGRES_PASSWORD must be set}"

if [[ ! -f "${jar_file}" ]]; then
    echo "missing application JAR: ${jar_file}" >&2
    exit 1
fi

mkdir -p "${result_dir}"

cd "${canonical_dir}"
shasum -a 256 -c "$(basename "${canonical_checksum}")"

cd "${repository_root}"
docker compose --profile local stop postgres-local
docker compose --profile benchmark up -d --wait postgres-benchmark

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS ${experiment_database} WITH (FORCE);"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d postgres -v ON_ERROR_STOP=1 \
    -c "CREATE DATABASE ${experiment_database} OWNER community_benchmark;"

BENCHMARK_POSTGRES_DB="${experiment_database}" \
    java -Xms256m -Xmx512m -jar "${jar_file}" \
    --spring.profiles.active=benchmark \
    --spring.main.web-application-type=none \
    --app.benchmark.generator.enabled=false \
    > "${result_dir}/flyway-v1.log" 2>&1

docker compose --profile benchmark exec -T postgres-benchmark \
    pg_restore -U community_benchmark -d "${experiment_database}" \
    --no-owner --no-privileges --exit-on-error \
    < "${canonical_dump}"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < benchmark/postgresql/verify-canonical-dataset.sql \
    > "${result_dir}/canonical-verification.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/verify-fts-absent.sql" \
    > "${result_dir}/before-structure.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/apply-fts.sql" \
    > "${result_dir}/apply-fts.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/verify-fts-ready.sql" \
    > "${result_dir}/after-structure.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/capture-fts-search-characteristics.sql" \
    > "${result_dir}/search-characteristics.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < benchmark/postgresql/verify-canonical-dataset.sql \
    > "${result_dir}/canonical-verification-after.log"

(
    cd "${result_dir}"
    shasum -a 256 \
        flyway-v1.log \
        canonical-verification.log \
        before-structure.log \
        apply-fts.log \
        after-structure.log \
        search-characteristics.log \
        canonical-verification-after.log \
        > SHA256SUMS
)

echo "FTS experiment database is ready: ${experiment_database}"
echo "verification artifacts: ${result_dir}"
