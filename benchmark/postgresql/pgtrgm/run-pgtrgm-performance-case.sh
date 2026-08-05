#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <common|rare> <keyword>" >&2
    exit 1
fi

search_case="$1"
keyword="$2"

if [[ "${search_case}" != "common" && "${search_case}" != "rare" ]]; then
    echo "search case must be common or rare: ${search_case}" >&2
    exit 1
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
sql_dir="${repository_root}/benchmark/postgresql/pgtrgm"
k6_image="grafana/k6@sha256:e7eeddf1ce2361df6920d925297f487c0ba549c44be242c6a9c22f28d9b08efa"
base_url="http://host.docker.internal:18084"
experiment_database="community_benchmark_pgtrgm"
measurement_id="postgresql-pgtrgm-${search_case}-$(date '+%Y%m%d-%H%M%S-kst')"
result_dir="${PGTRGM_PERFORMANCE_RESULT_DIR:-${repository_root}/benchmark-data/postgresql/pgtrgm/performance/${measurement_id}}"

mkdir -p "${result_dir}"
cd "${repository_root}"

capture_state() {
    local output_file="$1"

    docker compose --profile benchmark exec -T postgres-benchmark \
        psql -U community_benchmark -d "${experiment_database}" \
        -v ON_ERROR_STOP=1 -P pager=off \
        < "${sql_dir}/verify-pgtrgm-ready.sql" \
        > "${output_file}"

    docker compose --profile benchmark exec -T postgres-benchmark \
        psql -U community_benchmark -d "${experiment_database}" \
        -v ON_ERROR_STOP=1 -P pager=off \
        < benchmark/postgresql/verify-canonical-dataset.sql \
        >> "${output_file}"
}

run_k6() {
    local script_path="$1"
    local result_name="$2"
    shift 2

    set +e
    docker run --rm -i \
        -e BASE_URL="${base_url}" \
        -e KEYWORD="${keyword}" \
        -e SEARCH_CASE="pgtrgm_${search_case}_all" \
        -e K6_NO_COLOR=true \
        -v "${result_dir}:/results" \
        "$@" \
        "${k6_image}" run \
        --summary-export="/results/${result_name}.json" - \
        < "${repository_root}/${script_path}" \
        | tee "${result_dir}/${result_name}.log"
    local k6_exit_code="${PIPESTATUS[0]}"
    set -e

    echo "${result_name}=${k6_exit_code}" \
        >> "${result_dir}/k6-exit-codes.log"
}

capture_state "${result_dir}/before-state.log"
: > "${result_dir}/k6-exit-codes.log"

run_k6 benchmark/k6/post-search-smoke.js smoke

run_k6 benchmark/k6/post-search-baseline.js warmup \
    -e PHASE=warmup \
    -e DURATION=30s

for run in 1 2 3; do
    echo "${search_case} 1 VU measurement run ${run}/3"
    run_k6 benchmark/k6/post-search-baseline.js "single-user-${run}" \
        -e PHASE=measurement \
        -e DURATION=60s
done

for run in 1 2 3; do
    echo "${search_case} 50 RPS measurement run ${run}/3"
    run_k6 benchmark/k6/post-search-arrival-rate.js "arrival-rate-${run}" \
        -e RATE=50 \
        -e DURATION=60s \
        -e PREALLOCATED_VUS=50
done

capture_state "${result_dir}/after-state.log"

{
    echo "measurement_id=${measurement_id}"
    echo "database=${experiment_database}"
    echo "keyword=${keyword}"
    echo "scope=all"
    echo "size=10"
    echo "jar_sha256=$(shasum -a 256 build/libs/community-0.0.1-SNAPSHOT.jar | awk '{print $1}')"
    echo "k6_image=${k6_image}"
    echo "canonical_dump_sha256=e2dbcf795e0b124ac93f210541d49e9e8da93064cc804a779a155e6325c374c6"
    echo "git_branch=$(git rev-parse --abbrev-ref HEAD)"
    echo "git_head=$(git rev-parse HEAD)"
    echo "git_tree=$(git rev-parse 'HEAD^{tree}')"
    echo "conditions=smoke;warmup-1vu-30s;single-user-1vu-60s-3runs;arrival-rate-50rps-60s-50vus-3runs"
} > "${result_dir}/manifest.txt"

(
    cd "${result_dir}"
    shasum -a 256 \
        before-state.log \
        k6-exit-codes.log \
        smoke.json smoke.log \
        warmup.json warmup.log \
        single-user-1.json single-user-1.log \
        single-user-2.json single-user-2.log \
        single-user-3.json single-user-3.log \
        arrival-rate-1.json arrival-rate-1.log \
        arrival-rate-2.json arrival-rate-2.log \
        arrival-rate-3.json arrival-rate-3.log \
        after-state.log manifest.txt \
        > SHA256SUMS
)

echo "pg_trgm ${search_case} performance measurement completed"
echo "measurement artifacts: ${result_dir}"
