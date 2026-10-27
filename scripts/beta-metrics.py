"""Read-only weekly beta metrics; output contains suppressed aggregates, no account rows."""
import argparse
import datetime
import json
import pathlib
import re
import subprocess
import sys
from zoneinfo import ZoneInfo


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('container', help='Explicit PostgreSQL Docker container name or ID')
    parser.add_argument('--from-date', required=True, type=datetime.date.fromisoformat)
    parser.add_argument('--to-date', required=True, type=datetime.date.fromisoformat, help='Exclusive signup date in Asia/Bangkok')
    parser.add_argument('--as-of', type=datetime.datetime.fromisoformat, default=datetime.datetime.now(datetime.timezone.utc))
    parser.add_argument('--min-cohort', type=int, default=5)
    args = parser.parse_args(argv)
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', args.container):
        parser.error('Invalid container name or ID')
    if args.as_of.tzinfo is None:
        parser.error('--as-of requires a timezone offset')
    if args.as_of > datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=5):
        parser.error('--as-of must not be in the future')
    if not 1 <= (args.to_date - args.from_date).days <= 366:
        parser.error('Signup date range must be 1..366 days')
    if args.to_date > args.as_of.astimezone(ZoneInfo('Asia/Bangkok')).date() + datetime.timedelta(days=1):
        parser.error('Signup range must not extend beyond the observation day')
    if not 5 <= args.min_cohort <= 1000:
        parser.error('--min-cohort must be 5..1000')
    return args


def report(args):
    query = (pathlib.Path(__file__).resolve().parent.parent / 'ops/analytics/beta-cohorts.sql').read_text()
    # Role and query are fixed. CLI supplies only validated dates/integers as quoted psql values.
    result = subprocess.run(['docker', 'exec', '-i', args.container, 'sh', '-ceu',
        'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" '
        '-d "${POSTGRES_DB:-${POSTGRES_USER:-postgres}}" "$@"', 'beta-metrics',
        '-v', 'from_date=' + args.from_date.isoformat(), '-v', 'to_date=' + args.to_date.isoformat(),
        '-v', 'as_of=' + args.as_of.isoformat(), '-v', 'min_cohort=' + str(args.min_cohort)],
        input=query, text=True, capture_output=True, timeout=45)
    if result.returncode:
        raise RuntimeError('Metrics query failed; check container, schema and reader-role grants. Database output omitted.')
    value = json.loads(result.stdout)
    if value.get('schemaVersion') != 1 or not isinstance(value.get('cohorts'), list):
        raise RuntimeError('Unexpected metrics output')
    return value


def main():
    args = arguments()
    try:
        print(json.dumps(report(args), ensure_ascii=False, indent=2))
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        message = str(error) if type(error) is RuntimeError else 'Metrics execution failed; database output omitted.'
        print(message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
