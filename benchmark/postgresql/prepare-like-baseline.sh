#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
canonical_dir="${repository_root}/benchmark-data/postgresql/canonical"
checksum_file="community-benchmark-100k.dump.sha256"
canonical_verification_sql="${repository_root}/benchmark/postgresql/verify-canonical-dataset.sql"
verification_sql="${repository_root}/benchmark/postgresql/verify-like-baseline-ready.sql"

cd "${canonical_dir}"
shasum -a 256 -c "${checksum_file}"

cd "${repository_root}"
docker compose --profile local stop postgres-local
docker compose --profile benchmark up -d --wait postgres-benchmark

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d community_benchmark \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${canonical_verification_sql}"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d community_benchmark \
    -v ON_ERROR_STOP=1 -c "ANALYZE;"

docker compose --profile benchmark exec -T postgres-benchmark \
    psql -U community_benchmark -d community_benchmark \
    -v ON_ERROR_STOP=1 -P pager=off \
    < "${verification_sql}"
