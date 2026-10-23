#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
container="finstep-test-$(date +%s)-$$"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
# No host port, volume, or connection to the developer/production database.
docker run -d --name "$container" -e POSTGRES_PASSWORD=ephemeral-test-only postgres:17 >/dev/null
for attempt in {1..30}; do
  if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -U postgres >/dev/null
while IFS= read -r migration; do
  docker exec -i "$container" psql -X -U postgres -v ON_ERROR_STOP=1 < "$migration"
done < <(find backend/src/main/resources/db/migration -name 'V*__*.sql' -print | sort -V)
docker exec -i "$container" psql -X -U postgres -v ON_ERROR_STOP=1 < database/tests/financial_core.sql
