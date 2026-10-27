"""Regression checks for false-green HTTP bodies and refusing an unrelated target."""
import http.server
import importlib.util
import pathlib
import ssl
import threading
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('load_scenario', pathlib.Path(__file__).with_name('load-scenario.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class BrokenResponse(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', '100' if self.path == '/truncated' else '1')
        self.end_headers()
        self.wfile.write(b'{')


class RunnerFailureChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), BrokenResponse)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        runner.SAMPLES.clear()

    def check_failed_body(self, path):
        # HTTP is confined to this loopback failure fixture; full TLS is exercised by test-load.sh.
        client = runner.Client(f'http://127.0.0.1:{self.server.server_port}', ssl.create_default_context())
        with self.assertRaises(Exception):
            client.request(path, measured='mixed:read')
        stats = runner.stats()['mixed:read']
        self.assertEqual(stats['statuses'], {'200': 1})
        self.assertEqual(stats['failures'], 1)
        self.assertFalse(stats['passed'])

    def test_truncated_success_body_is_a_failure(self):
        self.check_failed_body('/truncated')

    def test_malformed_success_json_is_a_failure(self):
        self.check_failed_body('/invalid-json')

    def test_unrelated_project_is_rejected_before_docker_or_mutations(self):
        with patch.object(runner, 'command') as command:
            with self.assertRaises(ValueError):
                runner.verify_stack(types.SimpleNamespace(project='finstep-staging'))
            command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
