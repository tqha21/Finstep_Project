"""Real PostgreSQL checks: maturity, local day boundaries, deletion, suppression and least privilege."""
import contextlib
import importlib.util
import io
import json
import os
import pathlib
import subprocess
import sys
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('beta_metrics', ROOT / 'scripts/beta-metrics.py')
metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metrics)


class BetaMetricsTest(unittest.TestCase):
    @classmethod
    def sql(cls, query, check=True):
        return subprocess.run(['docker','exec','-i',cls.container,'psql','-X','-qAt','-U','finstep','-d','finstep','-v','ON_ERROR_STOP=1'],
            input=query,text=True,capture_output=True,check=check,timeout=30)

    @classmethod
    def setUpClass(cls):
        cls.container = 'finstep-beta-metrics-' + str(os.getpid())
        subprocess.run(['docker','run','-d','--name',cls.container,'--network','none',
            '-e','POSTGRES_USER=finstep','-e','POSTGRES_DB=finstep','-e','POSTGRES_PASSWORD=synthetic-test-only','postgres:17'],
            check=True,capture_output=True,timeout=60)
        cls.addClassCleanup(lambda: subprocess.run(['docker','rm','-f',cls.container],capture_output=True,timeout=30))
        for _ in range(30):
            ready = subprocess.run(['docker','exec',cls.container,'pg_isready','-U','finstep'],capture_output=True,timeout=5)
            if ready.returncode == 0:
                break
            time.sleep(1)
        migrations = sorted((ROOT/'backend/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda path:int(path.name.split('__')[0][1:]))
        for migration in migrations:
            cls.sql(migration.read_text())
        cls.sql((ROOT/'database/tests/beta_metrics.sql').read_text())
        cls.base = [cls.container,'--from-date','2026-10-05','--to-date','2026-10-27','--as-of','2026-10-27T00:00:00Z']
        # Without an explicitly installed restricted role, reporting must fail closed.
        try:
            metrics.report(metrics.arguments(cls.base))
        except RuntimeError:
            pass
        else:
            raise AssertionError('Missing reader role unexpectedly accepted')
        cls.sql((ROOT/'ops/analytics/reader-role.sql').read_text())

    def result(self, extra=None):
        return metrics.report(metrics.arguments(self.base + (extra or [])))

    def test_activation_d7_boundaries_backdating_soft_deletes_and_disabled_accounts(self):
        cohort = self.result()['cohorts'][0]
        self.assertEqual(cohort['registeredAccounts'],8)
        self.assertEqual(cohort['verifiedAccounts'],8)
        self.assertEqual(cohort['activation7d'],{'suppressed':False,'eligibleAccounts':8,'activatedAccounts':5,
            'percent':62.50,'medianFirstTransactionSeconds':7200})
        self.assertEqual(cohort['d7FinancialCreation'],{'suppressed':False,'eligibleAccounts':8,'returnedAccounts':5,'percent':62.50})

    def test_small_and_immature_cohorts_are_suppressed(self):
        self.assertEqual(self.sql("SELECT count(*) FROM users WHERE created_at>='2026-10-26T00:00:00Z';").stdout.strip(),'4')
        weeks = {item['weekStarting']:item for item in self.result()['cohorts']}
        self.assertEqual(weeks['2026-10-26'],{'weekStarting':'2026-10-26','suppressed':True})
        self.assertEqual(weeks['2026-10-12'],{'weekStarting':'2026-10-12','suppressed':True})
        immature = weeks['2026-10-19']
        self.assertEqual(immature['registeredAccounts'],5)
        self.assertEqual(immature['activation7d'],{'suppressed':True})
        self.assertEqual(immature['d7FinancialCreation'],{'suppressed':True})

    def test_activation_and_d7_mature_at_different_boundaries(self):
        first = self.result(['--to-date','2026-10-14','--as-of','2026-10-12T17:30:00Z'])['cohorts'][0]
        self.assertEqual(first['activation7d']['eligibleAccounts'],8)
        self.assertEqual(first['activation7d']['activatedAccounts'],5)
        self.assertEqual(first['d7FinancialCreation'],{'suppressed':True})
        end = self.result(['--to-date','2026-10-14','--as-of','2026-10-13T17:00:00Z'])['cohorts'][0]
        self.assertEqual(end['d7FinancialCreation']['eligibleAccounts'],8)
        self.assertEqual(end['d7FinancialCreation']['returnedAccounts'],5)

    def test_higher_threshold_never_exposes_small_counts(self):
        self.assertTrue(all(item['suppressed'] for item in self.result(['--min-cohort','9'])['cohorts']))

    def test_report_contains_no_identity_financial_payload_or_secret(self):
        output = json.dumps(self.result())
        for private in ['example.test','00000000-0000','PRIVATE-METRICS-SENTINEL','123456789','987654321','not-a-login-hash']:
            self.assertNotIn(private,output)

    def test_cli_emits_json_and_reports_missing_container_without_database_output(self):
        command = [sys.executable,str(ROOT/'scripts/beta-metrics.py')]
        result = subprocess.run(command+self.base,capture_output=True,text=True,timeout=50)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads(result.stdout)['cohorts'][0]['registeredAccounts'],8)
        missing = [self.container+'-does-not-exist'] + self.base[1:]
        result = subprocess.run(command+missing,capture_output=True,text=True,timeout=50)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(result.stdout,'')
        self.assertNotIn('No such container',result.stderr)
        self.assertNotIn('SELECT',result.stderr)
        self.assertNotIn('Traceback',result.stderr)

    def test_reader_cannot_read_payload_or_mutate(self):
        for query in ['SELECT email FROM users','SELECT password_hash FROM users','SELECT amount FROM transactions',
                      'SELECT note FROM transactions','SELECT * FROM spring_session','DELETE FROM users']:
            result = self.sql('SET ROLE finstep_metrics_reader; '+query+';',check=False)
            self.assertNotEqual(result.returncode,0,query)

    def test_account_deletion_changes_next_report_without_keeping_analytics_identity(self):
        # Roll back the fixture deletion after taking a report on that same snapshot.
        query = (ROOT/'ops/analytics/beta-cohorts.sql').read_text()
        target = "'00000000-0000-0000-0000-000000000001'"
        delete = 'BEGIN; '+''.join(f'DELETE FROM {table} WHERE user_id={target};' for table in ['transactions','transfers','wallets','categories'])
        delete += f'DELETE FROM users WHERE id={target};'
        # Substitute only the fixed transaction preamble; the SELECT and role are unchanged.
        query = query.replace('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;',delete)
        query = query.replace(":'from_date'","'2026-10-05'").replace(":'to_date'","'2026-10-27'")
        query = query.replace(":'as_of'","'2026-10-27T00:00:00Z'").replace(":'min_cohort'",'5')
        cohort = json.loads(self.sql(query).stdout)['cohorts'][0]
        self.assertEqual(cohort['registeredAccounts'],7)
        self.assertEqual(cohort['activation7d']['activatedAccounts'],4)
        self.assertIsNone(cohort['activation7d']['medianFirstTransactionSeconds'])
        self.assertEqual(cohort['d7FinancialCreation']['returnedAccounts'],4)
        self.assertEqual(self.sql('SELECT count(*) FROM users;').stdout.strip(),'17')

    def test_invalid_cli_values_are_rejected_before_query(self):
        for extra in [['--min-cohort','1'],['--as-of','2026-10-27T00:00:00'],['--to-date','2026-10-05'],['--to-date','2028-01-01']]:
            with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):
                metrics.arguments(self.base+extra)


if __name__ == '__main__':
    unittest.main()
