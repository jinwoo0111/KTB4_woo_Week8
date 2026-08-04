#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
k6_image="grafana/k6@sha256:e7eeddf1ce2361df6920d925297f487c0ba549c44be242c6a9c22f28d9b08efa"
base_url="http://host.docker.internal:18084"
run_timestamp="$(date '+%Y%m%d-%H%M%S')"
result_dir="${RESULT_DIR:-${repository_root}/benchmark-data/postgresql/like-baseline/results/${run_timestamp}}"

mkdir -p "${result_dir}"

echo "LIKE baseline results: ${result_dir}"

run_k6() {
    local script_path="$1"
    local result_name="$2"
    shift 2

    docker run --rm -i \
        -e BASE_URL="${base_url}" \
        -e K6_NO_COLOR=true \
        -v "${result_dir}:/results" \
        "$@" \
        "${k6_image}" run \
        --summary-export="/results/${result_name}.json" - \
        < "${repository_root}/${script_path}" \
        | tee "${result_dir}/${result_name}.log"
}

run_k6 benchmark/k6/post-search-smoke.js smoke

run_k6 benchmark/k6/post-search-baseline.js warmup \
    -e PHASE=warmup \
    -e DURATION=30s

for run in 1 2 3; do
    echo "1 VU measurement run ${run}/3"
    run_k6 benchmark/k6/post-search-baseline.js "single-user-${run}" \
        -e PHASE=measurement \
        -e DURATION=60s
done

for run in 1 2 3; do
    echo "50 RPS measurement run ${run}/3"
    run_k6 benchmark/k6/post-search-arrival-rate.js "arrival-rate-${run}" \
        -e RATE=50 \
        -e DURATION=60s \
        -e PREALLOCATED_VUS=50
done
