import importlib.util
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('backup_store',pathlib.Path(__file__).with_name('backup_store.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class BackupStore(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.root=pathlib.Path(self.directory.name);self.config=self.root/'rclone.conf'
        self.config.write_text('[offsite]\ntype=s3\nprovider=Other\nendpoint=https://storage.provider.org\naccess_key_id=fixture-key\nsecret_access_key=fixture-secret\n')
        self.config.chmod(0o600)
    def test_private_config_and_https_required(self):
        module.S3Store(self.config,'offsite:bucket/finstep')
        self.config.chmod(0o644)
        with self.assertRaises(module.StoreError):module.S3Store(self.config,'offsite:bucket/finstep')
    def test_plain_http_is_fixture_only(self):
        self.config.write_text(self.config.read_text().replace('https://storage.provider.org','http://127.0.0.1:9000'))
        with self.assertRaises(module.StoreError):module.S3Store(self.config,'offsite:bucket/finstep')
        module.S3Store(self.config,'offsite:bucket/finstep',True)
        self.config.write_text(self.config.read_text().replace('127.0.0.1','public.provider.org'))
        with self.assertRaises(module.StoreError):module.S3Store(self.config,'offsite:bucket/finstep',True)
    def test_untrusted_remote_and_malformed_config_are_refused_without_secret_text(self):
        for remote in ['local:/tmp/private','offsite:bucket','offsite:bucket/../other','--bad:bucket/path']:
            with self.assertRaises(module.StoreError):module.S3Store(self.config,remote)
        self.config.write_text('secret_access_key=fixture-secret-without-a-section')
        with self.assertRaises(module.StoreError) as error:module.S3Store(self.config,'offsite:bucket/finstep')
        self.assertNotIn('fixture-secret',str(error.exception))
    def test_plaintext_is_rejected_before_upload(self):
        file=self.root/'accidental.cms';file.write_text('A plaintext database dump')
        store=module.S3Store(self.config,'offsite:bucket/finstep')
        with patch.object(store,'run') as network:
            with self.assertRaises(module.StoreError):store.upload(file)
            network.assert_not_called()
    def test_inherited_rclone_and_aws_options_cannot_override_validation(self):
        with patch.dict(os.environ,{'RCLONE_NO_CHECK_CERTIFICATE':'true','AWS_ENDPOINT_URL':'http://other','RCLONE_CONFIG_OFFSITE_ENDPOINT':'http://other'}):
            store=module.S3Store(self.config,'offsite:bucket/finstep')
        self.assertFalse(any(key.startswith(('RCLONE_','AWS_')) for key in store.environment))
    def test_failed_attempt_retains_the_last_verified_success(self):
        path=self.root/'metrics'/'backup.prom'
        with patch.object(module.time,'time',return_value=1000):module.metrics(path,True)
        with patch.object(module.time,'time',return_value=2000):module.metrics(path,False)
        text=path.read_text()
        self.assertIn('last_success_timestamp_seconds 1000',text)
        self.assertIn('last_attempt_timestamp_seconds 2000',text)
        self.assertIn('last_attempt_success 0',text)
        self.assertEqual(path.stat().st_mode & 0o777,0o644)
    def test_untrusted_metrics_directory_is_refused(self):
        directory=self.root/'unsafe';directory.mkdir();directory.chmod(0o777)
        with self.assertRaises(module.StoreError):module.metrics(directory/'backup.prom',False)

if __name__=='__main__':unittest.main()
