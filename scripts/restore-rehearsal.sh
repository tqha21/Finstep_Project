#!/usr/bin/env bash
set -euo pipefail
umask 077
if [[ $# != 5 ]]; then echo 'Usage: restore-rehearsal.sh AUTHORITATIVE_SOURCE EMPTY_QUARANTINE_TARGET BACKUP CERTIFICATE PRIVATE_KEY' >&2; exit 2; fi
source_container=$1
target_container=$2
backup=$3
certificate=$4
private_key=$5
for name in "$source_container" "$target_container"; do [[ "$name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || { echo 'Invalid container name.' >&2; exit 2; }; done
[[ "$source_container" != "$target_container" ]] || { echo 'Source and target must differ.' >&2; exit 2; }
[[ -f "$backup" && -f "$certificate" && -f "$private_key" ]] || { echo 'Missing encrypted backup or decryption material.' >&2; exit 2; }
# This tool deliberately cannot restore into an ordinary database container.
[[ $(docker inspect -f '{{index .Config.Labels "com.finstep.restore-quarantine"}}' "$target_container") == true ]] || { echo 'Target lacks the restore-quarantine label.' >&2; exit 2; }
[[ $(docker inspect -f '{{.HostConfig.NetworkMode}}' "$target_container") == none ]] || { echo 'Target must have network=none.' >&2; exit 2; }
state=$(mktemp -d /tmp/finstep-restore.XXXXXX)
cleanup() {
  docker exec "$target_container" rm -f /tmp/finstep-current-accounts.csv /tmp/finstep-restore.dump >/dev/null 2>&1 || true
  rm -rf -- "$state"
}
trap cleanup EXIT
count=$(docker exec "$target_container" sh -ceu 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM pg_tables WHERE schemaname= '\''public'\''"')
[[ "$count" == 0 ]] || { echo 'Target already has tables; refusing to overwrite.' >&2; exit 2; }
# First authenticate the complete encrypted envelope, then unpack/restore its plaintext.
openssl cms -decrypt -binary -inform DER -in "$backup" -recip "$certificate" -inkey "$private_key" -out "$state/archive.tar" 2> "$state/decryption.log" || { echo 'Backup decryption/integrity check failed.' >&2; exit 1; }
python3 - "$state" <<'PY'
import json,pathlib,sys,tarfile
root=pathlib.Path(sys.argv[1])
with tarfile.open(root/'archive.tar') as archive:
    entries=archive.getmembers()
    if len(entries)!=2 or {item.name for item in entries}!={'manifest.json','database.dump'} or not all(item.isfile() for item in entries):
        raise SystemExit('Unexpected backup archive entries')
    archive.extractall(root,filter='data')
manifest=json.loads((root/'manifest.json').read_text())
if manifest.get('format')!='finstep-postgres-v1' or manifest.get('postgresMajor')!=17 or not manifest.get('authenticationStateExcluded'):
    raise SystemExit('Unsupported backup manifest')
(root/'schema-version').write_text(str(manifest['schemaVersion']))
PY
# Fail closed without a readable live source. An old checkpoint is not a deletion journal.
source_schema=$(docker exec "$source_container" sh -ceu 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"')
[[ "$source_schema" == 5 ]] || { echo 'This restore scrub supports schema V5 only; review it for newer schemas.' >&2; exit 2; }
[[ "$source_schema" == "$(cat "$state/schema-version")" ]] || { echo 'Source/backup schema mismatch; migrate and review a quarantine restore separately.' >&2; exit 2; }
docker exec "$source_container" sh -ceu 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "COPY (SELECT id,email,password_hash,full_name,currency,timezone,status,email_verified_at,created_at,auth_version FROM users ORDER BY id) TO STDOUT WITH (FORMAT csv)"' > "$state/current-accounts.csv"
docker cp "$state/database.dump" "$target_container:/tmp/finstep-restore.dump" >/dev/null
docker cp "$state/current-accounts.csv" "$target_container:/tmp/finstep-current-accounts.csv" >/dev/null
docker exec "$target_container" sh -ceu 'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error --no-owner --no-acl /tmp/finstep-restore.dump' > "$state/restore.log" 2>&1 || { echo 'Restore failed; target must remain quarantined and be discarded.' >&2; exit 1; }
# SQL is trusted repository code; source UUIDs are imported as data, never interpolated SQL.
target_schema=$(docker exec "$target_container" sh -ceu 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"')
[[ "$target_schema" == "$source_schema" ]] || { echo 'Restored schema does not match the authoritative source; discard the quarantine target.' >&2; exit 1; }
script_root=$(cd "$(dirname "$0")/.." && pwd)
docker exec -i "$target_container" sh -ceu 'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$script_root/ops/backup/scrub-restored.sql" > "$state/scrub.log"
python3 - "$state/scrub.log" <<'PY'
import json,sys
results=[line for line in open(sys.argv[1]) if line.startswith('{')]
if len(results)!=1: raise SystemExit('Missing restore verification summary')
print(json.dumps(json.loads(results[0])))
PY
