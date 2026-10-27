import json
import pathlib
import subprocess
import tempfile
import unittest
class AlertConfig(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.root=pathlib.Path(self.directory.name);self.url=self.root/'url';self.token=self.root/'token';self.output=self.root/'generated.json'
        self.url.write_text('https://alerts.provider.org/receiver');self.token.write_text('fictional-very-long-bearer-token-123456');self.url.chmod(0o600);self.token.chmod(0o600)
    def run_config(self,local=False):
        return subprocess.run(['python3','scripts/configure-alerts.py','--url-file',str(self.url),'--token-file',str(self.token),'--output',str(self.output)]+(['--local-test'] if local else []),capture_output=True,text=True)
    def test_secure_authentication_and_redirect_policy(self):
        result=self.run_config();self.assertEqual(result.returncode,0,result.stderr)
        config=json.loads(self.output.read_text());http=config['receivers'][0]['webhook_configs'][0]['http_config']
        self.assertFalse(http['follow_redirects']);self.assertIn('credentials_file',http['authorization'])
        self.assertNotIn(self.token.read_text(),self.output.read_text()+result.stdout+result.stderr)
        self.assertEqual(self.output.stat().st_mode & 0o777,0o600)
    def test_http_and_query_secrets_are_refused(self):
        for url in ['http://alerts.provider.org/receiver','https://alerts.provider.org/receiver?token=private','https://user:password@alerts.provider.org/']:
            self.url.write_text(url);result=self.run_config();self.assertNotEqual(result.returncode,0)
    def test_local_receiver_requires_explicit_fixture_mode(self):
        self.url.write_text('http://receiver:8080/alerts');self.assertNotEqual(self.run_config().returncode,0);self.assertEqual(self.run_config(True).returncode,0)
    def test_private_inputs_and_no_overwrite(self):
        self.token.chmod(0o644);self.assertNotEqual(self.run_config().returncode,0)
        self.token.chmod(0o600);self.assertEqual(self.run_config().returncode,0)
        self.assertNotEqual(self.run_config().returncode,0)

if __name__=='__main__':unittest.main()
