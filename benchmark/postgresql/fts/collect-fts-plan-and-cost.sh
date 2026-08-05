#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
sql_dir="${repository_root}/benchmark/postgresql/fts"
measurement_id="postgresql-fts-plan-cost-$(date '+%Y%m%d-%H%M%S-kst')"
result_dir="${FTS_PLAN_RESULT_DIR:-${repository_root}/benchmark-data/postgresql/fts/explain/${measurement_id}}"
experiment_database="community_benchmark_fts"

mkdir -p "${result_dir}"
cd "${repository_root}"

docker compose --profile benchmark up -d --wait postgres-benchmark

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/verify-fts-ready.sql" \
    > "${result_dir}/fts-state-before.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/capture-storage-cost.sql" \
    > "${result_dir}/storage-cost-before.log"

for run in 1 2 3; do
    docker compose --profile benchmark exec -T postgres-benchmark \
        psql -U community_benchmark -d "${experiment_database}" \
        -v ON_ERROR_STOP=1 -P pager=off \
        < "${sql_dir}/explain-fts-common.sql" \
        > "${result_dir}/common-explain-run-${run}.log"
done

for run in 1 2 3; do
    docker compose --profile benchmark exec -T postgres-benchmark \
        psql -U community_benchmark -d "${experiment_database}" \
        -v ON_ERROR_STOP=1 -P pager=off \
        < "${sql_dir}/explain-fts-rare.sql" \
        > "${result_dir}/rare-explain-run-${run}.log"
done

for run in 1 2 3; do
    docker compose --profile benchmark exec -T postgres-benchmark \
        psql -U community_benchmark -d "${experiment_database}" \
        -v ON_ERROR_STOP=1 -P pager=off \
        < "${sql_dir}/explain-fts-ranked-common.sql" \
        > "${result_dir}/ranked-common-explain-run-${run}.log"
done

for run in 1 2 3; do
    docker compose --profile benchmark exec -T postgres-benchmark \
        psql -U community_benchmark -d "${experiment_database}" \
        -v ON_ERROR_STOP=1 -P pager=off \
        < "${sql_dir}/explain-fts-ranked-rare.sql" \
        > "${result_dir}/ranked-rare-explain-run-${run}.log"
done

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/capture-storage-cost.sql" \
    > "${result_dir}/storage-cost-after.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < benchmark/postgresql/verify-canonical-dataset.sql \
    > "${result_dir}/fts-state-after.log"

(
    cd "${result_dir}"
    shasum -a 256 \
        fts-state-before.log \
        storage-cost-before.log \
        common-explain-run-1.log \
        common-explain-run-2.log \
        common-explain-run-3.log \
        rare-explain-run-1.log \
        rare-explain-run-2.log \
        rare-explain-run-3.log \
        ranked-common-explain-run-1.log \
        ranked-common-explain-run-2.log \
        ranked-common-explain-run-3.log \
        ranked-rare-explain-run-1.log \
        ranked-rare-explain-run-2.log \
        ranked-rare-explain-run-3.log \
        storage-cost-after.log \
        fts-state-after.log \
        > SHA256SUMS
)

echo "FTS plan and cost verification completed"
echo "verification artifacts: ${result_dir}"
