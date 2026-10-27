#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
[[ -f backend/target/finstep-api-0.1.0-SNAPSHOT.jar && -f frontend/.next/standalone/server.js ]] || { echo 'Build the backend JAR and Next standalone output before operations tests.' >&2; exit 1; }
state=$(mktemp -d /tmp/finstep-operations.XXXXXX)
project="finstep-ops-$$"
quarantine="finstep-quarantine-$$"
artifact_directory=${FINSTEP_ARTIFACT_DIR:-"$PWD/artifacts/operations"}
cleanup() {
  result=$?
  if [[ $result != 0 ]]; then
    mkdir -p "$artifact_directory" || true
    # Container operational logs only; no access logs or request bodies are configured.
    compose logs --no-color > "$artifact_directory/finstep-operations.log" 2>&1 || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  docker rm -fv "$quarantine" >/dev/null 2>&1 || true
  rm -rf -- "$state"
  return "$result"
}
compose() { docker compose --env-file "$state/staging.env" -p "$project" -f compose.staging.yaml -f ops/staging/compose.test.yaml "$@"; }
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
compose up -d --build > "$state/startup.log" 2>&1
proxy=$(compose ps -q proxy)
for attempt in {1..60}; do
  if docker exec "$proxy" test -f /data/caddy/pki/authorities/local/root.crt 2>/dev/null; then break; fi
  sleep 1
done
docker cp "$proxy:/data/caddy/pki/authorities/local/root.crt" "$state/root.crt" >/dev/null
https_port=$(compose port proxy 443)
http_port=$(compose port proxy 80)
metrics_port=$(compose port prometheus 9090)
mail_port=$(compose port mail 8025)
source_container=$(compose ps -q db)
# Private key belongs only to this synthetic test. A real backup writer needs only the certificate.
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -keyout "$state/recipient.key" -out "$state/recipient.crt" -days 2 -subj /CN=Finstep-Backup-Fixture > "$state/keys.log" 2>&1
docker run -d --name "$quarantine" --network none --label com.finstep.restore-quarantine=true \
  -e POSTGRES_USER=finstep -e POSTGRES_DB=finstep -e POSTGRES_PASSWORD=ephemeral-test-only postgres:17 >/dev/null
for attempt in {1..30}; do if docker exec "$quarantine" pg_isready -U finstep >/dev/null 2>&1; then break; fi; sleep 1; done
python3 scripts/test-operations.py "$state" "${https_port##*:}" "${http_port##*:}" "${metrics_port##*:}" "${mail_port##*:}" "$source_container" "$quarantine"
# Validate both rule syntax and firing/non-firing behavior, using the actual pinned Prometheus tool.
compose exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
prom_image=$(docker inspect -f '{{.Config.Image}}' "$(compose ps -q prometheus)")
docker run --rm --network none --entrypoint /bin/promtool -v "$PWD/ops/monitoring:/rules:ro" -w /rules "$prom_image" test rules alerts.test.yml
printf '%s\n' 'Operations tests passed: trusted TLS, secure sessions, private metrics, encrypted restore and deletion scrub.'
