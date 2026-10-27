"""Bounded closed-loop regression through real TLS; accepts only our disposable stack.

No arbitrary target URL, no credentials in reports, no changes to production rate limits.
Historical financial rows are SQL fixtures; identity setup and measured writes use real APIs.
"""
import argparse
import collections
import concurrent.futures
import copy
import datetime
import http.cookiejar
import json
import math
import os
import pathlib
import re
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

USERS = 8
HISTORY = 2000
MIXED_ITERATIONS = 20
HOT_ITERATIONS = 12
GATES = {'read': 1500, 'write': 2000}
SAMPLES = []
SAMPLE_LOCK = threading.Lock()


def command(*args, data=None):
    return subprocess.check_output(args, input=data, text=True, stderr=subprocess.PIPE, timeout=30)


def inspect(container):
    return json.loads(command('docker', 'inspect', container))[0]


def verify_stack(args):
    if not re.fullmatch(r'finstep-load-[0-9]+', args.project):
        raise ValueError('Only disposable load projects are allowed')
    for role in ('db', 'api', 'web', 'proxy'):
        container = getattr(args, role)
        if not re.fullmatch(r'[a-f0-9]{64}', container):
            raise ValueError('Expected full container IDs')
        detail = inspect(container)
        labels = detail['Config']['Labels']
        if labels.get('com.docker.compose.project') != args.project or labels.get('com.docker.compose.service') != role:
            raise ValueError('Container does not belong to disposable load stack')
        if not detail['HostConfig']['Memory'] or not detail['HostConfig']['NanoCpus']:
            raise ValueError('Missing resource limits')
        bindings = detail['HostConfig'].get('PortBindings') or {}
        for ports in bindings.values():
            if any(port['HostIp'] != '127.0.0.1' for port in ports or []):
                raise ValueError('Test service must publish only on loopback')
        if role == 'proxy':
            published = detail['NetworkSettings']['Ports']['443/tcp']
            if not any(port['HostIp'] == '127.0.0.1' and int(port['HostPort']) == args.port for port in published):
                raise ValueError('HTTPS port does not belong to test proxy')


class Client:
    def __init__(self, origin, context, cookies=None):
        self.origin = origin
        self.context = context
        self.cookies = http.cookiejar.CookieJar()
        for cookie in cookies or []:
            self.cookies.set_cookie(copy.copy(cookie))
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=context), urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None
        self.deadline = None

    def fork(self):
        client = Client(self.origin, self.context, self.cookies)
        client.csrf = self.csrf
        return client

    def request(self, path, method='GET', body=None, key=None, expected=200, measured=None, json_response=True):
        headers = {'Content-Type': 'application/json'}
        if method != 'GET':
            if self.csrf is None:
                self.csrf = self.request('/api/v1/auth/csrf')
            headers[self.csrf['headerName']] = self.csrf['token']
        if key:
            headers['Idempotency-Key'] = key
        request = urllib.request.Request(self.origin + path, method=method, headers=headers,
            data=None if body is None else json.dumps(body).encode())
        start = time.perf_counter()
        status = 0
        success = False
        try:
            if self.deadline is not None and time.monotonic() >= self.deadline:
                raise RuntimeError('Measured phase exceeded 120 seconds')
            try:
                response = self.opener.open(request, timeout=8)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.status
                raw = response.read()
            if status != expected:
                raise RuntimeError(f'Unexpected status {status}, expected {expected}')
            value = (json.loads(raw) if json_response else raw.decode()) if raw else None
            success = True
            return value
        finally:
            if measured:
                with SAMPLE_LOCK:
                    SAMPLES.append((measured, (time.perf_counter() - start) * 1000, status, success))


def wait(check, description, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (OSError, ValueError, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError('Timed out waiting for ' + description)


def mail_token(port, email):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = f'http://127.0.0.1:{port}'
    def lookup():
        with opener.open(base + '/api/v1/messages', timeout=5) as response:
            messages = json.load(response)['messages']
        for message in messages:
            if any(recipient['Address'] == email for recipient in message['To']):
                with opener.open(base + '/api/v1/message/' + message['ID'], timeout=5) as response:
                    return re.search(r'[A-Za-z0-9_-]{43}', json.load(response)['Text']).group()
    return wait(lookup, 'synthetic verification email', 30)


def sql(container, query):
    return command('docker', 'exec', '-i', container, 'sh', '-ceu',
        'exec psql -X -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"', data=query)


def setup(args, context):
    origin = f'https://localhost:{args.port}'
    client = Client(origin, context)
    # Readiness is intentionally checked on the public authentication boundary.
    wait(lambda: client.request('/api/v1/me', expected=401) is not False, 'API')
    if sql(args.db, 'SELECT count(*) FROM users;').strip() != '0':
        raise RuntimeError('Refusing to seed a nonempty database')
    fixtures = []
    for _ in range(USERS):
        client = Client(origin, context)
        email = f'load-{uuid.uuid4()}@example.test'
        password = 'Disposable-Load-Password-123!'
        client.request('/api/v1/auth/register', 'POST', {'email': email, 'password': password, 'fullName': 'Load Fixture'}, expected=202)
        client.request('/api/v1/auth/verify-email', 'POST', {'token': mail_token(args.mail_port, email)}, expected=204)
        client.request('/api/v1/auth/login', 'POST', {'email': email, 'password': password}, expected=204)
        client.csrf = client.request('/api/v1/auth/csrf')
        owner = str(uuid.UUID(client.request('/api/v1/me')['id']))
        wallets = [client.request('/api/v1/wallets', 'POST', {'name': name, 'type': 'CASH',
            'openingBalance': amount, 'effectiveAt': '2026-01-01T00:00:00Z'}, expected=201)
            for name, amount in [('Cash fixture', '10000000'), ('Transfer fixture', '0')]]
        wallet_ids = [str(uuid.UUID(wallet['id'])) for wallet in wallets]
        category = str(uuid.UUID(next(item['id'] for item in client.request('/api/v1/categories') if item['type'] == 'EXPENSE')))
        # Only UUIDs parsed above and a fixed bounded integer enter SQL. Identity is never seeded/bypassed.
        sql(args.db, f"""INSERT INTO transactions(user_id,wallet_id,category_id,type,amount,note,occurred_at)
            SELECT '{owner}','{wallet_ids[0]}','{category}','EXPENSE',1000,'Synthetic load history',
                '2026-01-02T00:00:00Z'::timestamptz + n * interval '1 second'
            FROM generate_series(1,{HISTORY}) n;""")
        client.request('/api/v1/budgets/2026-01', 'PUT', {'items': [{'categoryId': category, 'limitAmount': '5000000'}]})
        fixtures.append({'client': client, 'owner': owner, 'wallets': wallet_ids, 'category': category})
    sql(args.db, 'ANALYZE transactions; ANALYZE wallets;')
    return fixtures


def write_pair(fixture, transfer, group):
    client = fixture['client']
    if transfer:
        path = '/api/v1/transfers'
        body = {'fromWalletId': fixture['wallets'][0], 'toWalletId': fixture['wallets'][1], 'amount': '17', 'occurredAt': '2026-01-03T00:00:00Z'}
    else:
        path = '/api/v1/transactions'
        body = {'walletId': fixture['wallets'][0], 'categoryId': fixture['category'], 'type': 'EXPENSE',
            'amount': '13', 'occurredAt': '2026-01-03T00:00:00Z', 'note': 'Synthetic measured write'}
    key = str(uuid.uuid4())
    first = client.request(path, 'POST', body, key, expected=201, measured=group + ':write')
    replay = client.request(path, 'POST', body, key, expected=200, measured=group + ':write')
    if replay != first:
        raise RuntimeError('Idempotent replay changed response')


def mixed(fixture):
    client = fixture['client']
    for iteration in range(MIXED_ITERATIONS):
        client.request('/api/v1/wallets', measured='mixed:read')
        page = client.request('/api/v1/transactions?size=20', measured='mixed:read')
        if len(page['items']) != 20 or not page['nextCursor']:
            raise RuntimeError('History unexpectedly empty')
        next_page = client.request('/api/v1/transactions?size=20&cursor=' + urllib.parse.quote(page['nextCursor']), measured='mixed:read')
        if set(item['id'] for item in page['items']) & set(item['id'] for item in next_page['items']):
            raise RuntimeError('Cursor repeated a transaction')
        client.request('/api/v1/reports/summary?month=2026-01', measured='mixed:read')
        client.request('/api/v1/budgets/2026-01', measured='mixed:read')
        write_pair(fixture, iteration % 2 == 1, 'mixed')


def hotspot(fixture):
    for iteration in range(HOT_ITERATIONS):
        write_pair(fixture, iteration % 2 == 1, 'hotspot')


def public_web(fixture):
    for _ in range(10):
        html = fixture['client'].request('/', measured='web:read', json_response=False)
        if '<html' not in html:
            raise RuntimeError('Landing page did not return HTML')


def phase(name, fixtures, workload):
    barrier = threading.Barrier(USERS)
    deadline = time.monotonic() + 120
    def worker(fixture):
        barrier.wait(timeout=10)
        fixture['client'].deadline = deadline
        try:
            workload(fixture)
        finally:
            fixture['client'].deadline = None
    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=USERS) as executor:
        futures = [executor.submit(worker, fixture) for fixture in fixtures]
        for future in futures:
            future.result()
    return {'name': name, 'concurrency': USERS, 'seconds': round(time.perf_counter() - start, 3)}


def percentile(values, percent):
    return sorted(values)[max(0, math.ceil(len(values) * percent / 100) - 1)]


def stats():
    result = {}
    groups = collections.defaultdict(list)
    for group, latency, status, success in SAMPLES:
        groups[group].append((latency, status, success))
    for group, samples in sorted(groups.items()):
        times = [sample[0] for sample in samples]
        p95 = percentile(times, 95)
        p99 = percentile(times, 99)
        failures = sum(not sample[2] for sample in samples)
        result[group] = {'requests': len(samples), 'failures': failures,
            'statuses': dict(collections.Counter(str(sample[1]) for sample in samples)),
            'p50Ms': round(percentile(times, 50), 2), 'p95Ms': round(p95, 2), 'p99Ms': round(p99, 2),
            'maxMs': round(max(times), 2), 'passed': not failures and p95 <= GATES[group.split(':')[1]] and p99 <= 5000}
    return result


def reconcile(args, fixtures):
    for index, fixture in enumerate(fixtures):
        expense_count = MIXED_ITERATIONS // 2 + (USERS * HOT_ITERATIONS // 2 if index == 0 else 0)
        transfer_count = expense_count
        expenses = HISTORY * 1000 + expense_count * 13
        wallets = {item['id']: int(item['balance']) for item in fixture['client'].request('/api/v1/wallets')}
        expected = {fixture['wallets'][0]: 10000000 - expenses - transfer_count * 17,
                    fixture['wallets'][1]: transfer_count * 17}
        if wallets != expected:
            raise RuntimeError('Exact wallet reconciliation failed')
        summary = fixture['client'].request('/api/v1/reports/summary?month=2026-01')
        if (int(summary['totalIncome']), int(summary['totalExpense']), int(summary['currentTotalBalance'])) != (0, expenses, 10000000 - expenses):
            raise RuntimeError('Report reconciliation failed')
        counts = sql(args.db, f"""SELECT (SELECT count(*) FROM transactions WHERE user_id='{fixture['owner']}'),
            (SELECT count(*) FROM transfers WHERE user_id='{fixture['owner']}'),
            (SELECT count(*) FROM idempotency_requests WHERE user_id='{fixture['owner']}');""").strip()
        if counts != f'{HISTORY + expense_count}|{transfer_count}|{expense_count + transfer_count}':
            raise RuntimeError('Replay created duplicate or missing ledger entries')
    # Tenant boundaries still hold after concurrency; this request is deliberately outside latency gates.
    fixtures[0]['client'].request('/api/v1/wallets/' + fixtures[1]['wallets'][0], expected=404)


def run(args):
    verify_stack(args)  # Always before any mutation or SQL fixture.
    context = ssl.create_default_context(cafile=str(pathlib.Path(args.state) / 'root.crt'))
    report = {'schemaVersion': 1, 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'passed': False, 'model': 'closed-loop, fresh HTTPS connection per request, no think time',
        'fixtures': {'accounts': USERS, 'historyPerAccount': HISTORY, 'mixedIterationsPerWorker': MIXED_ITERATIONS, 'hotspotIterationsPerWorker': HOT_ITERATIONS},
        'gates': {'readP95Ms': GATES['read'], 'writeP95Ms': GATES['write'], 'allP99Ms': 5000, 'failures': 0, 'restarts': 0},
        'phases': [], 'environment': {'loadGeneratorCpuCount': os.cpu_count(), 'containers': {}}, 'reconciled': False}
    try:
        report['stage'] = 'setup'
        fixtures = setup(args, context)
        for fixture in fixtures:
            fixture['client'].request('/api/v1/reports/summary?month=2026-01')
            fixture['client'].request('/', json_response=False)
        print('Synthetic setup ready: 8 verified accounts, 16,000 historical transactions.', flush=True)
        report['stage'] = 'web'
        report['phases'].append(phase('web', fixtures, public_web))
        report['stage'] = 'mixed'
        report['phases'].append(phase('mixed', fixtures, mixed))
        print('Mixed phase finished; testing writes contending for one account.', flush=True)
        hot = [{**fixtures[0], 'client': fixtures[0]['client'].fork()} for _ in range(USERS)]
        report['stage'] = 'hotspot'
        report['phases'].append(phase('hotspot', hot, hotspot))
        report['stage'] = 'reconciliation'
        reconcile(args, fixtures)
        report['reconciled'] = True
        healthy = True
        report['stage'] = 'resource-check'
        for role in ('db', 'api', 'web', 'proxy'):
            detail = inspect(getattr(args, role))
            report['environment']['containers'][role] = {'imageId': detail['Image'], 'memoryLimitBytes': detail['HostConfig']['Memory'],
                'cpuLimit': detail['HostConfig']['NanoCpus'] / 1e9, 'restarts': detail['RestartCount'], 'oomKilled': detail['State']['OOMKilled']}
            healthy = healthy and detail['State']['Running'] and not detail['State']['OOMKilled'] and detail['RestartCount'] == 0
        report['metrics'] = stats()
        expected_counts = {'web:read': USERS * 10, 'mixed:read': USERS * MIXED_ITERATIONS * 5, 'mixed:write': USERS * MIXED_ITERATIONS * 2, 'hotspot:write': USERS * HOT_ITERATIONS * 2}
        report['passed'] = healthy and all(report['metrics'].get(group, {}).get('requests') == count for group, count in expected_counts.items()) and all(item['passed'] for item in report['metrics'].values())
        report['stage'] = 'complete'
    except Exception as error:
        # Never persist traceback, HTTP response bodies, emails, cookies or tokens.
        report['failureType'] = type(error).__name__
        if type(error) is RuntimeError:
            report['failureMessage'] = str(error)
        report['metrics'] = stats()
        raise RuntimeError('Load scenario failed; see sanitized report') from None
    finally:
        output = pathlib.Path(args.output)
        output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': report['passed'], 'reconciled': report['reconciled'], 'metrics': report['metrics']}), flush=True)
    if not report['passed']:
        raise SystemExit('Load performance or resource gate failed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('state', 'project', 'db', 'api', 'web', 'proxy', 'output'):
        parser.add_argument('--' + field, required=True)
    for field in ('port', 'mail-port'):
        parser.add_argument('--' + field, required=True, type=int, choices=range(1, 65536), metavar='PORT')
    run(parser.parse_args())
