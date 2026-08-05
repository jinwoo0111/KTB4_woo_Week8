#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <common|rare> <keyword>" >&2
    exit 1
fi

search_case="$1"
keyword="$2"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
sql_dir="${repository_root}/benchmark/postgresql/fts"
k6_image="grafana/k6@sha256:e7eeddf1ce2361df6920d925297f487c0ba549c44be242c6a9c22f28d9b08efa"
base_url="http://host.docker.internal:18084"
experiment_database="community_benchmark_fts"
measurement_id="postgresql-fts-${search_case}-single-user-supplement-$(date '+%Y%m%d-%H%M%S-kst')"
result_dir="${repository_root}/benchmark-data/postgresql/fts/performance/${measurement_id}"

mkdir -p "${result_dir}"
cd "${repository_root}"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/verify-fts-ready.sql" \
    > "${result_dir}/before-state.log"

set +e
docker run --rm -i \
    -e BASE_URL="${base_url}" \
    -e KEYWORD="${keyword}" \
    -e SEARCH_CASE="fts_${search_case}_all_supplement" \
    -e PHASE=measurement \
    -e DURATION=60s \
    -e K6_NO_COLOR=true \
    -v "${result_dir}:/results" \
    "${k6_image}" run \
    --summary-export="/results/single-user-supplement.json" - \
    < benchmark/k6/post-search-baseline.js \
    | tee "${result_dir}/single-user-supplement.log"
k6_exit_code="${PIPESTATUS[0]}"
set -e

echo "single-user-supplement=${k6_exit_code}" \
    > "${result_dir}/k6-exit-code.log"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d "${experiment_database}" \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${sql_dir}/verify-fts-ready.sql" \
    > "${result_dir}/after-state.log"

{
    echo "measurement_id=${measurement_id}"
    echo "purpose=replace-invalid-single-user-run"
    echo "database=${experiment_database}"
    echo "search_mode=fts"
    echo "keyword=${keyword}"
    echo "scope=all"
    echo "size=10"
    echo "conditions=single-user-1vu-60s-1run"
    echo "jar_sha256=$(shasum -a 256 build/libs/community-0.0.1-SNAPSHOT.jar | awk '{print $1}')"
    echo "k6_image=${k6_image}"
} > "${result_dir}/manifest.txt"

(
    cd "${result_dir}"
    shasum -a 256 \
        before-state.log \
        single-user-supplement.json \
        single-user-supplement.log \
        k6-exit-code.log \
        after-state.log \
        manifest.txt \
        > SHA256SUMS
)

if [[ "${k6_exit_code}" -ne 0 ]]; then
    echo "supplemental measurement failed with exit code ${k6_exit_code}" >&2
    exit "${k6_exit_code}"
fi

echo "FTS ${search_case} supplemental single-user measurement completed"
echo "measurement artifacts: ${result_dir}"
