"""PITR startup gates do not execute PostgreSQL or artifact code."""
import contextlib
import io
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
import uuid

import run_pitr_recovery as runner


class PITRStartupTests(unittest.TestCase):
    def test_unconfirmed_postmaster_preserves_private_workspace(self):
        workspace = runner.OwnedWorkspace(prefix='dsh-pitr-fixture-unit-')
        root = Path(workspace.name)
        (root / 'source').mkdir(mode=0o700)
        marker = root / 'source/postmaster.pid'
        marker.write_text('unconfirmed fixture identity\n')
        try:
            with self.assertRaisesRegex(RuntimeError, 'private workspace preserved'):
                workspace.cleanup()
            self.assertTrue(root.exists())
        finally:
            marker.unlink(missing_ok=True)
            workspace.cleanup()

    def test_invalid_pin_stops_before_resource_startup(self):
        argv = ['runner', '--service-bundle-directory', '/fixture', '--service-bundle-manifest-sha256', 'invalid', '--output', '/fixture']
        with patch.object(sys, 'argv', argv), patch.object(os, 'geteuid', return_value=1000), \
             patch.object(runner.subprocess, 'run') as invoke, patch.object(runner, 'OwnedWorkspace') as allocate, \
             contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit): runner.main()
            invoke.assert_not_called()
            allocate.assert_not_called()

    def test_failed_bundle_verification_stops_before_database_and_hides_child_detail(self):
        output = Path(runner.__file__).resolve().parents[2] / '.local' / ('pitr-guard-' + uuid.uuid4().hex)
        argv = ['runner', '--service-bundle-directory', '/fixture', '--service-bundle-manifest-sha256', 'a' * 64, '--output', str(output)]
        rejected = subprocess.CompletedProcess([], 1, stdout=b'', stderr=b'PRIVATE_ARTIFACT_DETAIL')
        with patch.object(sys, 'argv', argv), patch.object(os, 'geteuid', return_value=1000), \
             patch.object(runner.subprocess, 'run', return_value=rejected) as invoke, patch.object(runner, 'OwnedWorkspace') as allocate, \
             contextlib.redirect_stderr(io.StringIO()) as errors:
            with self.assertRaises(SystemExit): runner.main()
            self.assertEqual(invoke.call_count, 1)
            self.assertEqual(invoke.call_args.args[0][2], 'verify')
            self.assertNotIn('PRIVATE_ARTIFACT_DETAIL', errors.getvalue())
            self.assertFalse(output.exists())
            allocate.assert_not_called()


if __name__ == '__main__': unittest.main()
