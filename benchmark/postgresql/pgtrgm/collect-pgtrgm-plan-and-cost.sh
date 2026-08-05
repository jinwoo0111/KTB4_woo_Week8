#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
sql_dir="${repository_root}/benchmark/postgresql/pgtrgm"
measurement_id="postgresql-pgtrgm-plan-cost-$(date '+%Y%m%d-%H%M%S-kst')"
result_dir="${PGTRGM_PLAN_RESULT_DIR:-${repository_root}/benchmark-data/postgresql/pgtrgm/explain/${measurement_id}}"
baseline_database="community_benchmark"
experiment_database="community_benchmark_pgtrgm"

mkdir -p "${result_dir}"
cd "${repository_root}"

docker compose --profile benchmark up -d --wait postgres-benchmark

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${baseline_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < benchmark/postgresql/verify-like-baseline-ready.sql \
    > "${result_dir}/baseline-state.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/verify-pgtrgm-ready.sql" \
    > "${result_dir}/pgtrgm-state-before.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${baseline_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/capture-index-cost.sql" \
    > "${result_dir}/baseline-index-cost.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/capture-index-cost.sql" \
    > "${result_dir}/pgtrgm-index-cost-before.log"

for run in 1 2 3; do
    docker compose --profile benchmark exec -T postgres-benchmark \
        psql -U community_benchmark -d "${experiment_database}" \
        -v ON_ERROR_STOP=1 -P pager=off \
        < "${sql_dir}/explain-pgtrgm-common.sql" \
        > "${result_dir}/common-explain-run-${run}.log"
done

for run in 1 2 3; do
    docker compose --profile benchmark exec -T postgres-benchmark \
        psql -U community_benchmark -d "${experiment_database}" \
        -v ON_ERROR_STOP=1 -P pager=off \
        < "${sql_dir}/explain-pgtrgm-rare.sql" \
        > "${result_dir}/rare-explain-run-${run}.log"
done

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/capture-index-cost.sql" \
    > "${result_dir}/pgtrgm-index-cost-after.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < benchmark/postgresql/verify-canonical-dataset.sql \
    > "${result_dir}/pgtrgm-state-after.log"

(
    cd "${result_dir}"
    shasum -a 256 \
        baseline-state.log \
        pgtrgm-state-before.log \
        baseline-index-cost.log \
        pgtrgm-index-cost-before.log \
        common-explain-run-1.log \
        common-explain-run-2.log \
        common-explain-run-3.log \
        rare-explain-run-1.log \
        rare-explain-run-2.log \
        rare-explain-run-3.log \
        pgtrgm-index-cost-after.log \
        pgtrgm-state-after.log \
        > SHA256SUMS
)

echo "pg_trgm plan and cost verification completed"
echo "verification artifacts: ${result_dir}"
