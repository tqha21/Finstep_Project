"""No network: catch unsafe staging configurations and accidental secret disclosure."""
import pathlib
import subprocess
import tempfile
import unittest

class Preflight(unittest.TestCase):
    def config(self,changes=None,local=False):
        values={'STAGING_HOST':'staging.finstep-demo.org','TLS_ISSUER':'ops@finstep-demo.org',
          'POSTGRES_PASSWORD':'fictional-long-generated-secret-123456','SMTP_HOST':'smtp.provider.org',
          'SMTP_USERNAME':'fixture-user','SMTP_PASSWORD':"'fixture$literal-secret'",'MAIL_FROM':'no-reply@finstep-demo.org',
          'SMTP_AUTH':'true','SMTP_STARTTLS':'true'}
        values.update(changes or {})
        with tempfile.TemporaryDirectory() as directory:
            path=pathlib.Path(directory)/'config.env'
            path.write_text(''.join(f'{key}={value}\n' for key,value in values.items()))
            return subprocess.run(['python3','scripts/staging-preflight.py',str(path)]+(['--local-test'] if local else []),capture_output=True,text=True)
    def test_valid_public_config_accepts_quoted_secret_without_disclosing_it(self):
        result=self.config()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertNotIn('fixture$literal-secret',result.stdout+result.stderr)
    def test_public_smtp_cannot_disable_tls(self):
        result=self.config({'SMTP_STARTTLS':'false'})
        self.assertNotEqual(result.returncode,0)
        self.assertIn('STARTTLS',result.stderr)
    def test_local_mode_cannot_publish_to_all_interfaces(self):
        result=self.config({'STAGING_HOST':'localhost','TLS_ISSUER':'internal','STAGING_BIND_ADDRESS':'0.0.0.0','SMTP_HOST':'mail'},local=True)
        self.assertNotEqual(result.returncode,0)
    def test_trusted_proxy_cannot_be_widened(self):
        result=self.config({'TRUSTED_PROXY_REGEX':'.*'})
        self.assertNotEqual(result.returncode,0)
        self.assertIn('exactly CADDY_IP',result.stderr)
    def test_template_cannot_be_used_as_public_config(self):
        result=subprocess.run(['python3','scripts/staging-preflight.py','ops/staging/.env.example'],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)

if __name__=='__main__': unittest.main()
