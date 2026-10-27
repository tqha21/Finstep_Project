#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
[[ -f backend/target/finstep-api-0.1.0-SNAPSHOT.jar && -f frontend/.next/standalone/server.js ]] || { echo 'Build backend JAR and Next standalone output first.' >&2; exit 1; }
state=$(mktemp -d /tmp/finstep-load.XXXXXX)
project="finstep-load-$$"
artifact_directory=${FINSTEP_LOAD_ARTIFACT_DIR:-"$PWD/artifacts/load"}
mkdir -p "$artifact_directory"
# Never leave a previous successful report looking like this run's result.
rm -f -- "$artifact_directory/report.json"
compose() { docker compose --env-file "$state/staging.env" -p "$project" -f compose.staging.yaml -f ops/staging/compose.test.yaml -f ops/testing/compose.load.yaml "$@"; }
cleanup() {
  result=$?
  if [[ $result != 0 ]]; then
    mkdir -p "$artifact_directory" || true
    compose logs --no-color db api web proxy > "$artifact_directory/containers.log" 2>&1 || true
    cp "$state/startup.log" "$artifact_directory/startup.log" 2>/dev/null || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf -- "$state"
  return "$result"
}
trap cleanup EXIT
python3 - "$state/staging.env" <<'PY'
import secrets,sys
values={'STAGING_HOST':'localhost','TLS_ISSUER':'internal','STAGING_BIND_ADDRESS':'127.0.0.1',
 'STAGING_HTTP_PORT':'0','STAGING_HTTPS_PORT':'0','PROMETHEUS_PORT':'0','POSTGRES_PASSWORD':secrets.token_urlsafe(32),
 'SMTP_HOST':'mail','SMTP_PORT':'1025','SMTP_USERNAME':'local-fixture','SMTP_PASSWORD':'local-fixture',
 'SMTP_AUTH':'false','SMTP_STARTTLS':'false','MAIL_FROM':'fixture@finstep.local'}
with open(sys.argv[1],'w') as output:
    output.write(''.join(f'{key}={value}\n' for key,value in values.items()))
PY
python3 scripts/staging-preflight.py --local-test "$state/staging.env"
compose up -d --build db api web proxy mail > "$state/startup.log" 2>&1
proxy=$(compose ps -q proxy)
for attempt in {1..60}; do
  if docker exec "$proxy" test -f /data/caddy/pki/authorities/local/root.crt 2>/dev/null; then break; fi
  sleep 1
done
docker cp "$proxy:/data/caddy/pki/authorities/local/root.crt" "$state/root.crt" >/dev/null
https_port=$(compose port proxy 443)
mail_port=$(compose port mail 8025)
python3 scripts/load-scenario.py --state "$state" --port "${https_port##*:}" --mail-port "${mail_port##*:}" \
  --project "$project" --db "$(compose ps -q db)" --api "$(compose ps -q api)" \
  --web "$(compose ps -q web)" --proxy "$proxy" --output "$artifact_directory/report.json"
printf '%s\n' 'Load gates passed; synthetic identities and private test volumes removed on exit.'
