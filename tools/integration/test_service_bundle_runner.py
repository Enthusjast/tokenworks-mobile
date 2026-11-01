"""Packaged-service startup gates; never starts PostgreSQL or artifact code."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import run_remote_dsh as runner


class ServiceBundleStartupTests(unittest.TestCase):
    def check_failure(self, fail_extract=False, version_output=b'{}', version_exit=0):
        with tempfile.TemporaryDirectory(prefix='service-startup-guard-') as temporary:
            root = Path(temporary)
            tools = root / 'tools'; tools.mkdir()
            for name in ('initdb', 'pg_ctl', 'psql', 'pg_dump'):
                (tools / name).touch()
            calls = []
            expected = {'format': 'dsh-remote-service-version/v1', 'go': 'go1.26.10', 'platform': 'linux/amd64',
                        'revision': 'a' * 40, 'source_sha256': 'b' * 64}
            def invoke(arguments, **_kwargs):
                if 'go_bundle.py' in str(arguments[1]) and arguments[2] == 'verify':
                    calls.append('verify')
                    failed = fail_extract and '--unpack' in arguments
                    return subprocess.CompletedProcess(arguments, 1 if failed else 0, stdout=json.dumps({'version': expected}).encode(), stderr=b'')
                if arguments[0].endswith('/service/bin/remote') and arguments[1:] == ['version']:
                    calls.append('version')
                    return subprocess.CompletedProcess(arguments, version_exit, stdout=version_output, stderr=b'PRIVATE_UNTRUSTED_FAILURE')
                self.fail('a resource/tool was invoked before the startup gate: ' + str(arguments[0]))
            argv = ['run_remote_dsh.py', '--dsh-only', '--service-bundle-directory', str(root), '--service-bundle-manifest-sha256', 'a' * 64]
            error = io.StringIO()
            with patch.object(sys, 'argv', argv), patch.dict(os.environ, {'REMOTE_TEST_PG_BIN': str(tools), 'REMOTE_TEST_GO': '/never-call-go'}), \
                 patch.object(runner.subprocess, 'run', side_effect=invoke), contextlib.redirect_stderr(error):
                with self.assertRaises(SystemExit) as raised:
                    runner.main()
            self.assertEqual(raised.exception.code, 2)
            self.assertNotIn('PRIVATE_UNTRUSTED_FAILURE', error.getvalue())
            self.assertEqual(calls, ['verify', 'verify'] if fail_extract else ['verify', 'verify', 'version'])
            self.assertIn('before database startup', error.getvalue())

    def test_changed_bundle_stops_before_binary_or_database_startup(self):
        self.check_failure(fail_extract=True)

    def test_wrong_or_malformed_embedded_version_stops_before_database_startup(self):
        for output, code in [(b'{}', 0), (b'not-json', 0), (b'', 1)]:
            with self.subTest(exit=code, output=output):
                self.check_failure(version_output=output, version_exit=code)


if __name__ == '__main__': unittest.main()
