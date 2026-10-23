#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jar="$PWD/backend/target/finstep-api-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$jar" ]]; then
  echo 'Build backend with ./mvnw verify before running web E2E tests.' >&2
  exit 1
fi
state=$(mktemp -d /tmp/finstep-web-e2e.XXXXXX)
db="finstep-web-db-$$"
mail="finstep-web-mail-$$"
backend_pid=''
cleanup() {
  result=$?
  if [[ -n "$backend_pid" ]]; then kill "$backend_pid" 2>/dev/null || true; wait "$backend_pid" 2>/dev/null || true; fi
  if [[ "$result" -ne 0 ]]; then
    mkdir -p frontend/test-results
    cp "$state/backend.log" frontend/test-results/backend.log 2>/dev/null || true
  fi
  docker rm -fv "$db" "$mail" >/dev/null 2>&1 || true
  rm -rf -- "$state"
  return "$result"
}
trap cleanup EXIT
# Each run owns its isolated database/mailbox. No development/production data is used.
docker run -d --name "$db" -e POSTGRES_PASSWORD=ephemeral-test-only -p 127.0.0.1::5432 postgres:17 >/dev/null
docker run -d --name "$mail" -p 127.0.0.1::1025 -p 127.0.0.1::8025 axllent/mailpit:v1.27.10 >/dev/null
db_port=$(docker inspect -f '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "$db")
smtp_port=$(docker inspect -f '{{(index (index .NetworkSettings.Ports "1025/tcp") 0).HostPort}}' "$mail")
mail_port=$(docker inspect -f '{{(index (index .NetworkSettings.Ports "8025/tcp") 0).HostPort}}' "$mail")
backend_port=$(node -e 'const net=require("net");const server=net.createServer();server.listen(0,"127.0.0.1",()=>{process.stdout.write(String(server.address().port));server.close();});')
for attempt in {1..30}; do
  if docker exec "$db" pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$db" pg_isready -U postgres >/dev/null
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
DB_URL="jdbc:postgresql://127.0.0.1:$db_port/postgres" DB_USERNAME=postgres DB_PASSWORD=ephemeral-test-only \
SMTP_HOST=127.0.0.1 SMTP_PORT="$smtp_port" PORT="$backend_port" SESSION_COOKIE_SECURE=false SERVER_ADDRESS=127.0.0.1 \
"$java_bin" -jar "$jar" > "$state/backend.log" 2>&1 &
backend_pid=$!
export API_ORIGIN="http://127.0.0.1:$backend_port"
export MAILPIT_API="http://127.0.0.1:$mail_port"
ready=false
for attempt in {1..60}; do
  if ! kill -0 "$backend_pid" 2>/dev/null; then break; fi
  if curl -fsS --max-time 2 "$API_ORIGIN/actuator/health/readiness" >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then echo 'Isolated backend failed readiness; see frontend/test-results/backend.log.' >&2; exit 1; fi
npm --prefix frontend run test:e2e
