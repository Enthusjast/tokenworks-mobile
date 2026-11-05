import copy
import gzip
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import go_bundle as bundle


def binary():
    raw = bytearray(120)
    raw[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HHI', raw, 16, 2, 62, 1)
    struct.pack_into('<Q', raw, 24, 0x400040)
    struct.pack_into('<Q', raw, 32, 64)
    struct.pack_into('<HHH', raw, 52, 64, 56, 1)
    struct.pack_into('<IIQQQQQQ', raw, 64, 1, 5, 0, 0x400000, 0x400000, len(raw), len(raw), 4096)
    return bytes(raw)


def fixture():
    files = {'service/bin/remote': binary(), 'service/module/go.mod': b'module tokenworks-mobile/services/remote\n\ngo 1.26.0\n', 'service/module/go.sum': b'',
             'service/migrations/001_init.up.sql': b'create table example(id int);', 'service/migrations/001_init.down.sql': b'drop table example;'}
    files.update({'service/contracts/openapi/remote.yaml': b'openapi: 3.1.0\n', 'service/contracts/ws/v1/relay.schema.json': b'{}'})
    names = {'services/remote/go.mod': 'service/module/go.mod', 'services/remote/go.sum': 'service/module/go.sum',
             'deploy/sql/001_init.up.sql': 'service/migrations/001_init.up.sql', 'deploy/sql/001_init.down.sql': 'service/migrations/001_init.down.sql'}
    names.update({'contracts/openapi/remote.yaml': 'service/contracts/openapi/remote.yaml', 'contracts/ws/v1/relay.schema.json': 'service/contracts/ws/v1/relay.schema.json'})
    entries = [{'path': name, 'sha256': bundle.digest(files[target]), 'size': len(files[target])} for name, target in sorted(names.items())]
    entries += [{'path': 'services/remote/cmd/remote/main.go', 'size': 1, 'sha256': 'e'*64}, {'path': 'services/remote/cmd/remote/version.go', 'size': 1, 'sha256': 'f'*64}]
    entries.sort(key=lambda item: item['path'])
    source = {'commit': 'a' * 40, 'sha256': bundle.digest(bundle.encoded(entries)), 'inputs': entries}
    version = {'format': 'tokenworks-mobile-service-version/v1', 'go': 'go1.26.10', 'platform': 'linux/amd64', 'revision': source['commit'], 'source_sha256': source['sha256']}
    raw = bundle.pack(files)
    value = {'format': bundle.FORMAT, 'source': source, 'builder': {'go_bundle.py': 'b' * 64, 'host_bundle.py': 'c' * 64},
             'toolchain': {'go': 'go1.26.10', 'go_executable_sha256': 'd' * 64, 'platform': 'linux/amd64', 'cgo': 0, 'goamd64': 'v1', 'python': '3.14.0', 'zlib': '1.3.1'},
             'version': version, 'modules': [], 'artifact': {'file': bundle.ARCHIVE, 'sha256': bundle.digest(raw), 'size': len(raw), 'files': bundle.records(files)}}
    return value, files, raw


def validate(value, raw):
    encoded = bundle.encoded(value)
    return bundle.validate(encoded, raw, bundle.digest(encoded))


class GoBundleTests(unittest.TestCase):
    def test_roundtrip_is_deterministic_and_offline_verify_does_not_execute(self):
        value, files, raw = fixture()
        self.assertEqual(raw, bundle.pack(dict(reversed(list(files.items())))))
        self.assertEqual(validate(value, raw)[1], files)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); root.chmod(0o700)
            for name, content in [('manifest.json', bundle.encoded(value)), (bundle.ARCHIVE, raw)]:
                path = root / name; path.write_bytes(content); path.chmod(0o600)
            with patch.object(bundle.subprocess, 'run', side_effect=AssertionError('verifier executed a process')):
                report = bundle.verify(root, bundle.digest(bundle.encoded(value)), root / 'unpacked')
            self.assertEqual(report['file_count'], 7)
            self.assertEqual(report['version'], value['version'])
            self.assertEqual((root / 'unpacked/service/bin/remote').read_bytes(), files['service/bin/remote'])
            self.assertEqual((root / 'unpacked/service/bin/remote').stat().st_mode & 0o777, 0o755)
            with self.assertRaises(bundle.common.BundleError):
                bundle.verify(root, '0' * 64)
            with self.assertRaises(bundle.common.BundleError):
                bundle.verify(root, bundle.digest(bundle.encoded(value)), root / 'unpacked')
            (root / 'alias').symlink_to(root, target_is_directory=True)
            with self.assertRaises(bundle.common.BundleError):
                bundle.verify(root, bundle.digest(bundle.encoded(value)), root / 'alias/escape')
            self.assertFalse((root / 'escape').exists())
            victim = root / 'victim'; victim.mkdir(mode=0o700)
            original_mkdir = os.mkdir
            def redirect(name, mode=0o777, *, dir_fd=None):
                if name == 'service':
                    os.symlink(str(victim), name, dir_fd=dir_fd)
                    return
                return original_mkdir(name, mode, dir_fd=dir_fd)
            with patch.object(bundle.os, 'mkdir', side_effect=redirect):
                with self.assertRaises(OSError): bundle.unpack_files(files, root / 'raced')
            self.assertEqual(list(victim.iterdir()), [])

    def test_build_output_rename_never_redirects_logs_or_verified_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); local = root / '.local'; local.mkdir(mode=0o775)
            output, victim, retained = local / 'release', root / 'victim', local / 'retained'
            victim.mkdir(mode=0o700)
            with self.assertRaises(bundle.common.BundleError):
                with bundle.output_directory(local, output) as (_directory, log):
                    output.rename(retained)
                    output.symlink_to(victim, target_is_directory=True)
                    log.write(b'owned build log')
            self.assertEqual(list(victim.iterdir()), [])
            self.assertEqual((retained / 'build.log').read_bytes(), b'owned build log')

    def test_manifest_pin_duplicate_json_types_and_provenance_are_strict(self):
        original, _files, raw = fixture()
        for mutate in [lambda v: v['source'].__setitem__('sha256', '0' * 64),
                       lambda v: v['version'].__setitem__('revision', '0' * 40),
                       lambda v: v['toolchain'].__setitem__('cgo', False),
                       lambda v: v['artifact']['files'][0].__setitem__('mode', 0o777),
                       lambda v: v['artifact'].__setitem__('file', '../other.tgz'),
                       lambda v: v['modules'].append({'path': 'unrecorded', 'version': 'v1.0.0', 'sum': 'h1:' + 'a'*43 + '=', 'go_mod_sum': 'h1:' + 'b'*43 + '='})]:
            value = copy.deepcopy(original); mutate(value)
            with self.assertRaises(bundle.common.BundleError): validate(value, raw)
        encoded = bundle.encoded(original)
        with self.assertRaises(bundle.common.BundleError): bundle.validate(encoded, raw + b'changed', bundle.digest(encoded))
        duplicate = encoded.replace(b'"format": "tokenworks-mobile-service-bundle/v1"', b'"format": "bad", "format": "tokenworks-mobile-service-bundle/v1"')
        with self.assertRaises(bundle.common.BundleError): bundle.validate(duplicate, raw, bundle.digest(duplicate))

    def test_archives_reject_links_modes_traversal_private_files_and_trailing_data(self):
        for name, kind, mode in [('service/bin/remote', tarfile.SYMTYPE, 0o755), ('../escape', tarfile.REGTYPE, 0o644),
                                 ('service/.env', tarfile.REGTYPE, 0o644), ('service/bin/remote', tarfile.REGTYPE, 0o4755),
                                 ('service/bin/remote', tarfile.LNKTYPE, 0o755)]:
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as archive:
                item = tarfile.TarInfo(name); item.type = kind; item.mode = mode; item.size = 0
                archive.addfile(item, io.BytesIO())
            with self.assertRaises(bundle.common.BundleError): bundle.inspect_archive(gzip.compress(stream.getvalue(), mtime=0))
        oversized = tarfile.TarInfo('service/module/go.sum'); oversized.mode = 0o644; oversized.size = (2 << 20) + 1
        with self.assertRaises(bundle.common.BundleError): bundle.inspect_archive(gzip.compress(oversized.tobuf(format=tarfile.USTAR_FORMAT) + bytes(1024), mtime=0))
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as archive:
            for _ in range(2):
                item = tarfile.TarInfo('service/bin/remote'); item.mode = 0o755; item.size = len(binary())
                archive.addfile(item, io.BytesIO(binary()))
        with self.assertRaises(bundle.common.BundleError): bundle.inspect_archive(gzip.compress(stream.getvalue(), mtime=0))
        _value, _files, raw = fixture()
        with self.assertRaises(bundle.common.BundleError): bundle.inspect_archive(gzip.compress(gzip.decompress(raw) + b'APPENDED', mtime=0))
        with patch.object(bundle, 'MAX_TAR', 64):
            with self.assertRaises(bundle.common.BundleError): bundle.inspect_archive(raw)

    def test_static_elf_and_complete_migration_chain_are_required(self):
        good = binary()
        for offset, value in [(16, 3), (18, 183)]:
            bad = bytearray(good); struct.pack_into('<H', bad, offset, value)
            with self.assertRaises(bundle.common.BundleError): bundle.elf(bad)
        bad = bytearray(good); struct.pack_into('<I', bad, 64, 3)
        with self.assertRaises(bundle.common.BundleError): bundle.elf(bad)
        for names in [[], ['001_init.up.sql'], ['002_init.up.sql', '002_init.down.sql'],
                      ['001_one.up.sql', '001_one.down.sql', '001_two.up.sql', '001_two.down.sql']]:
            with self.assertRaises(bundle.common.BundleError): bundle.migrations(names)
        value, files, _raw = fixture(); files.pop('service/migrations/001_init.down.sql')
        raw = bundle.pack(files); value['artifact'].update(sha256=bundle.digest(raw), size=len(raw), files=bundle.records(files))
        with self.assertRaises(bundle.common.BundleError): validate(value, raw)

    def test_source_snapshot_uses_committed_blobs_and_refuses_dirty_or_ignored_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(['git','init','-q',str(root)], check=True)
            def git(*args): return subprocess.check_output(['git','-C',str(root),*args])
            git('config','user.email','fixture@example.invalid'); git('config','user.name','Fixture')
            (root/'services/remote/cmd/remote').mkdir(parents=True); (root/'deploy/sql').mkdir(parents=True)
            for name, content in [('services/remote/go.mod','module example\n'),('services/remote/go.sum',''),
                                  ('services/remote/cmd/remote/main.go','package main\n'),('deploy/sql/001_init.up.sql','up'),('deploy/sql/001_init.down.sql','down')]:
                (root/name).write_text(content)
            (root/'.gitignore').write_text('hidden.go\n')
            git('add','.'); git('commit','-qm','fixture')
            commit, entries, sources = bundle.source_snapshot(root)
            self.assertEqual(commit,git('rev-parse','HEAD').decode().strip())
            (root/'PLAN.md').write_text('never included')
            self.assertNotIn('PLAN.md',sources)
            path=root/'services/remote/cmd/remote/main.go'; path.write_text('package changed\n')
            with self.assertRaises(bundle.common.BundleError): bundle.source_snapshot(root)
            path.write_bytes(sources['services/remote/cmd/remote/main.go'])
            ignored=root/'services/remote/cmd/remote/hidden.go'; ignored.write_text('package main\n')
            with self.assertRaises(bundle.common.BundleError): bundle.source_snapshot(root)
            ignored.unlink(); self.assertEqual(bundle.source_snapshot(root)[1],entries)


if __name__ == '__main__': unittest.main()
