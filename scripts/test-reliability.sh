#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
command -v rclone >/dev/null
state=$(mktemp -d /tmp/finstep-reliability.XXXXXX)
project="finstep-reliability-$$"
artifact_directory=${FINSTEP_ARTIFACT_DIR:-"$PWD/artifacts/reliability"}
compose() { docker compose --env-file "$state/test.env" -p "$project" -f compose.reliability-test.yaml --profile alerting --profile offsite "$@"; }
cleanup() {
  result=$?
  if [[ $result != 0 ]]; then
    mkdir -p "$artifact_directory" || true
    cp "$state/startup.log" "$artifact_directory/startup.log" 2>/dev/null || true
    compose logs --no-color alertmanager prometheus receiver > "$artifact_directory/delivery.log" 2>&1 || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf -- "$state"
  return "$result"
}
trap cleanup EXIT
python3 - "$state" <<'PY'
import os,pathlib,secrets,sys
root=pathlib.Path(sys.argv[1])
(root/'alertmanager-state').mkdir()
(root/'metrics').mkdir();(root/'metrics').chmod(0o755)
(root/'alert-webhook-token').write_text(secrets.token_urlsafe(32))
(root/'alert-webhook-url').write_text('http://receiver:8080/alerts')
values={'ALERTMANAGER_CONFIG_FILE':str(root/'alertmanager.generated.json'),'ALERTMANAGER_TOKEN_FILE':str(root/'alert-webhook-token'),
 'ALERTMANAGER_STATE_DIR':str(root/'alertmanager-state'),'ALERTMANAGER_UID':str(os.getuid()),'ALERTMANAGER_GID':str(os.getgid()),
 'STAGING_HOST':'localhost','TLS_ISSUER':'internal','POSTGRES_PASSWORD':secrets.token_urlsafe(32),
 'SMTP_HOST':'mail','SMTP_USERNAME':'fixture','SMTP_PASSWORD':'fixture','MAIL_FROM':'fixture@finstep.local',
 'BACKUP_METRICS_DIR':str(root/'metrics'),'PROMETHEUS_PORT':'0','GARAGE_CONFIG_FILE':str(root/'garage.toml')}
(root/'garage.toml').write_text('metadata_dir="/var/lib/garage/meta"\ndata_dir="/var/lib/garage/data"\ndb_engine="lmdb"\nreplication_factor=1\nrpc_bind_addr="[::]:3901"\nrpc_public_addr="127.0.0.1:3901"\nrpc_secret="'+secrets.token_hex(32)+'"\n[s3_api]\ns3_region="garage"\napi_bind_addr="[::]:3900"\nroot_domain=".s3.garage.localhost"\n')
(root/'test.env').write_text(''.join(f'{key}={value}\n' for key,value in values.items()))
sys.path.insert(0,'scripts')
from backup_store import metrics
metrics(root/'metrics/backup.prom',True)
PY
python3 scripts/configure-alerts.py --url-file "$state/alert-webhook-url" --token-file "$state/alert-webhook-token" --output "$state/alertmanager.generated.json" --local-test
compose up -d > "$state/startup.log" 2>&1
receiver=$(compose port receiver 8080)
s3=$(compose port objectstore 3900)
metrics_port=$(compose port prometheus 9090)
python3 scripts/setup-s3-fixture.py "$state" "$(compose ps -q objectstore)"
python3 scripts/test-reliability.py "$state" "${receiver##*:}" "${s3##*:}" "${metrics_port##*:}"
compose exec -T alertmanager amtool check-config /etc/alertmanager/config.yml
# Use the same pinned Prometheus binary as staging to verify all production rules.
image=$(docker inspect -f '{{.Config.Image}}' "$(compose ps -q prometheus)")
docker run --rm --network none --entrypoint /bin/promtool -v "$PWD/ops/monitoring:/rules:ro" -w /rules "$image" test rules alerts.test.yml
printf '%s\n' 'Reliability tests passed: encrypted S3 roundtrip, corruption refusal and authenticated alert retry/resolution.'
