"""Keep the file lane bounded; subprocesses are recorded, never executed."""
import contextlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import run_remote_dsh as runner


class FileLifecycleRunnerTests(unittest.TestCase):
    def test_other_modes_are_rejected_before_any_resource_is_opened(self):
        conflicts = [
            ['--go-run', 'anything'], ['--dsh-only'], ['--cross-node'],
            ['--kotlin-relay'], ['--android-relay', 'emulator-5554'],
            ['--android-upgrade', 'emulator-5554'], ['--upgrade-receipt', '/fixture'],
            ['--host-renewal'], ['--installation-revoke'], ['--host-account-retire'],
            ['--database-outage'], ['--relay-crash'], ['--relay-certificate-renewal'],
            ['--approval-key-renewal'], ['--relay-load', '0'], ['--load-output', '/fixture'],
            ['--load-workers', '1'], ['--load-hold-seconds', '5'],
            ['--load-active-pairs', '0'], ['--load-events-per-second', '1'],
            ['--load-payload-bytes', '128'], ['--load-race'],
            ['--host-bundle-directory', '/fixture'], ['--host-bundle-manifest-sha256', 'a' * 64],
            ['--service-bundle-directory', '/fixture'], ['--service-bundle-manifest-sha256', 'a' * 64],
        ]
        for flags in conflicts:
            with self.subTest(flags=flags), patch.object(sys, 'argv', ['runner', '--file-lifecycle', *flags]), \
                 patch.object(runner.subprocess, 'run') as invoke, \
                 patch.object(runner.tempfile, 'TemporaryDirectory') as allocate, \
                 contextlib.redirect_stderr(io.StringIO()) as errors:
                with self.assertRaises(SystemExit) as raised:
                    runner.main()
                self.assertEqual(raised.exception.code, 2)
                self.assertIn('--file-lifecycle requires its own invocation', errors.getvalue())
                invoke.assert_not_called()
                allocate.assert_not_called()

    def test_fixed_cases_only_and_cluster_cleanup_even_on_failure(self):
        for result in (0, 17):
            with self.subTest(result=result), tempfile.TemporaryDirectory(prefix='file-lane-guard-') as temporary:
                pg = Path(temporary)
                for name in ('initdb', 'pg_ctl', 'psql', 'pg_dump'):
                    (pg / name).touch()
                calls = []

                def invoke(command, **kwargs):
                    calls.append((command, kwargs))
                    code = result if command[:2] == ['/fixture/go', 'test'] else 0
                    return subprocess.CompletedProcess(command, code)

                inherited = {'REMOTE_TEST_GO': '/fixture/go', 'REMOTE_TEST_PG_BIN': str(pg),
                             'REMOTE_TEST_KOTLIN_CLASSPATH': '/must-not-run-android',
                             'REMOTE_TEST_ANDROID_SERIAL': 'must-not-run',
                             'REMOTE_TEST_CROSS_NODE': '1', 'REMOTE_TEST_HOST_BUNDLE_DIRECTORY': '/must-not-load'}
                with patch.object(sys, 'argv', ['runner', '--file-lifecycle']), \
                     patch.dict(os.environ, inherited), patch.object(os, 'geteuid', return_value=1000), \
                     patch.object(runner.subprocess, 'run', side_effect=invoke), \
                     contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(runner.main(), result)
                tests = [(command, kwargs) for command, kwargs in calls if command[:2] == ['/fixture/go', 'test']]
                self.assertEqual(len(tests), 1)
                command, kwargs = tests[0]
                # Pin the complete selection, so adding unrelated cases is reviewable.
                self.assertEqual(command, ['/fixture/go', 'test', '-race', '-count=1', '-v', '-timeout=5m', '-run',
                    '^(TestProtectedFileHandoffToRealNativeDSHAndRevocation|'
                    'TestExportRelayUsesRealDSHWorkspaceAndPersistedAttachmentSources|'
                    'TestConfiguredFileServiceProcessRunsWorkersAndShutsDownCleanly)$', './internal/httpapi'])
                self.assertTrue(kwargs['env']['REMOTE_TEST_DATABASE_URL'])
                self.assertTrue(kwargs['env']['REMOTE_TEST_SERVICE_BIN'])
                for field in inherited.keys() - {'REMOTE_TEST_GO', 'REMOTE_TEST_PG_BIN'}:
                    self.assertNotIn(field, kwargs['env'])
                self.assertFalse(any(command[0] in ('npm', 'node') or './...' in command for command, _ in calls))
                self.assertEqual(sum(command[:2] == ['/fixture/go', 'build'] for command, _ in calls), 1)
                self.assertEqual(calls[-1][0], [str(pg / 'pg_ctl'), '-D', kwargs['env']['REMOTE_TEST_CLUSTER_ROOT'] + '/pgdata',
                                             '-m', 'immediate', '-w', 'stop'])
                self.assertFalse(Path(kwargs['env']['REMOTE_TEST_CLUSTER_ROOT']).exists())


if __name__ == '__main__':
    unittest.main()
