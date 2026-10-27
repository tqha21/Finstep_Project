#!/usr/bin/env bash
set -euo pipefail
umask 077
if [[ $# != 3 ]]; then echo 'Usage: backup-db.sh SOURCE_CONTAINER OUTPUT_DIRECTORY RECIPIENT_CERTIFICATE' >&2; exit 2; fi
source_container=$1
output_directory=$2
certificate=$3
[[ "$source_container" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || { echo 'Invalid source container name.' >&2; exit 2; }
[[ -f "$certificate" ]] || { echo 'Missing encryption certificate.' >&2; exit 2; }
command -v openssl >/dev/null
command -v python3 >/dev/null
mkdir -p -- "$output_directory"
state=$(mktemp -d /tmp/finstep-backup.XXXXXX)
partial=''
cleanup() { rm -rf -- "$state"; if [[ -n "$partial" ]]; then rm -f -- "$partial"; fi; }
trap cleanup EXIT
# Keep credentials inside the source container; never place them in command arguments.
docker exec "$source_container" sh -ceu 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-acl --exclude-table-data=public.spring_session --exclude-table-data=public.spring_session_attributes --exclude-table-data=public.identity_tokens --exclude-table-data=public.auth_rate_limits' > "$state/database.dump"
schema=$(docker exec "$source_container" sh -ceu 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"')
python3 - "$state/manifest.json" "$schema" <<'PY'
import datetime,json,sys
with open(sys.argv[1],'w') as output:
    json.dump({'format':'finstep-postgres-v1','postgresMajor':17,'schemaVersion':sys.argv[2],
               'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'authenticationStateExcluded':True},output)
PY
partial=$(mktemp "$output_directory/.partial-XXXXXX")
# Authenticated envelope; the private decryption key is not needed on the backup writer.
tar -C "$state" -cf - manifest.json database.dump | openssl cms -encrypt -binary -stream -aes-256-gcm -outform DER -recip "$certificate" -out "$partial"
final="$output_directory/finstep-$(date -u +%Y%m%dT%H%M%SZ)-${partial##*-}.cms"
mv -- "$partial" "$final"
partial=''
sha256sum -- "$final" > "$final.sha256"
printf '%s\n' "$final"
