"""No network: catch unsafe staging configurations and accidental secret disclosure."""
import pathlib
import json
import os
import subprocess
import tempfile
import unittest

class Preflight(unittest.TestCase):
    def config(self,changes=None,local=False,extras=()):
        values={'STAGING_HOST':'staging.finstep-demo.org','TLS_ISSUER':'ops@finstep-demo.org',
          'POSTGRES_PASSWORD':'fictional-long-generated-secret-123456','SMTP_HOST':'smtp.provider.org',
          'SMTP_USERNAME':'fixture-user','SMTP_PASSWORD':"'fixture$literal-secret'",'MAIL_FROM':'no-reply@finstep-demo.org',
          'SMTP_AUTH':'true','SMTP_STARTTLS':'true'}
        values.update(changes or {})
        with tempfile.TemporaryDirectory() as directory:
            path=pathlib.Path(directory)/'config.env'
            path.write_text(''.join(f'{key}={value}\n' for key,value in values.items()))
            return subprocess.run(['python3','scripts/staging-preflight.py',str(path)]+(['--local-test'] if local else [])+list(extras),capture_output=True,text=True)
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

    def test_enabled_profiles_require_private_matching_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);url=root/'url';token=root/'token';config=root/'alerts.json';state=root/'state'
            url.write_text('https://alerts.provider.org/receiver');token.write_text('fictional-long-bearer-token-123456');url.chmod(0o600);token.chmod(0o600);state.mkdir(mode=0o700)
            subprocess.run(['python3','scripts/configure-alerts.py','--url-file',str(url),'--token-file',str(token),'--output',str(config)],check=True,capture_output=True)
            values={'ALERTMANAGER_UID':str(os.getuid()),'ALERTMANAGER_GID':str(os.getgid()),'ALERTMANAGER_CONFIG_FILE':str(config),'ALERTMANAGER_TOKEN_FILE':str(token),'ALERTMANAGER_STATE_DIR':str(state)}
            self.assertEqual(self.config(values,extras=['--with-alerting']).returncode,0)
            token.chmod(0o644)
            self.assertNotEqual(self.config(values,extras=['--with-alerting']).returncode,0)
    def test_offsite_profile_validates_storage_without_connecting(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);config=root/'rclone.conf';certificate=root/'recipient.crt';metrics=root/'metrics'
            config.write_text('[offsite]\ntype=s3\nprovider=Other\nendpoint=https://storage.provider.org\naccess_key_id=fixture-key\nsecret_access_key=fixture-secret\n');config.chmod(0o600)
            certificate.write_text('public fixture placeholder');metrics.mkdir(mode=0o755);metrics.chmod(0o755)
            values={'BACKUP_STORE_CONFIG':str(config),'BACKUP_REMOTE':'offsite:bucket/finstep','BACKUP_RECIPIENT_CERT':str(certificate),'BACKUP_METRICS_DIR':str(metrics)}
            self.assertEqual(self.config(values,extras=['--with-offsite']).returncode,0)
            config.write_text(config.read_text().replace('https://storage.provider.org','http://public.provider.org'))
            self.assertNotEqual(self.config(values,extras=['--with-offsite']).returncode,0)

if __name__=='__main__': unittest.main()
