#!/usr/bin/env python3
"""Small encrypted-base/WAL/PITR fixture using a pinned Go service package."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

ORIGINAL = '00000000-0000-4000-8000-000000000000'
ORG = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
ISSUER = 'https://id.example.edu'


class OwnedWorkspace(tempfile.TemporaryDirectory):
    def cleanup(self):
        root = Path(self.name)
        if (root / '.preserve').exists() or any((root / name / 'postmaster.pid').exists() for name in ('source', 'restored')):
            self._finalizer.detach()
            raise RuntimeError('owned PITR cleanup unconfirmed; private workspace preserved')
        super().cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service-bundle-directory', type=Path, required=True)
    parser.add_argument('--service-bundle-manifest-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    if not sys.platform.startswith('linux') or os.geteuid() == 0 or not options.service_bundle_directory.is_absolute() or not re.fullmatch('[0-9a-f]{64}', options.service_bundle_manifest_sha256):
        parser.error('requires Linux, an unprivileged user and an absolute pinned service bundle')
    options.output = options.output.absolute()
    if options.output.parent != repo / '.local' or options.output.exists() or options.output.is_symlink():
        parser.error('output must be a fresh direct child of repository .local')
    verified = subprocess.run([sys.executable, str(repo / 'tools/release/go_bundle.py'), 'verify', '--directory', str(options.service_bundle_directory),
                               '--expected-manifest-sha256', options.service_bundle_manifest_sha256], capture_output=True)
    if verified.returncode:
        parser.error('service bundle failed offline verification before resource startup')
    package = json.loads(verified.stdout)
    pg = Path(os.environ.get('REMOTE_TEST_PG_BIN', '/usr/lib/postgresql/18/bin'))
    if not pg.is_absolute() or not all((pg / name).is_file() for name in ('initdb', 'pg_ctl', 'psql', 'pg_basebackup', 'pg_verifybackup')) or not Path('/usr/bin/gpg').is_file():
        parser.error('installed PostgreSQL tools and /usr/bin/gpg are required')
    os.umask(0o077)
    options.output.mkdir(mode=0o700)
    stage = 'startup'
    with OwnedWorkspace(prefix='dsh-pitr-fixture-') as directory:
        root = Path(directory)
        for name in ('home', 'archive', 'gnupg', 'sock-source', 'sock-restored'):
            (root / name).mkdir(mode=0o700)
        active = []
        summary = None
        exit_code = 1
        source, restored = root / 'source', root / 'restored'
        env = {'PATH': '/usr/bin:/bin', 'HOME': str(root / 'home'), 'LANG': 'C.UTF-8'}

        def run(command, *, input=None, success=True, timeout=30, environment=None):
            result = subprocess.run([str(part) for part in command], input=input, env=environment or env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
            with (options.output / 'commands.log').open('ab') as log:
                log.write(result.stdout)
                log.write(result.stderr)
            if success != (result.returncode == 0):
                raise RuntimeError('unexpected fixture command result')
            return result

        def dsn(socket):
            return 'host=' + str(root / socket) + ' port=55449 user=dsh_pitr dbname=postgres sslmode=disable'

        def sql(text, socket='sock-source'):
            return run([pg / 'psql', '-X', '--dbname', dsn(socket), '-v', 'ON_ERROR_STOP=1', '-Atq'], input=text.encode()).stdout.decode().strip()

        def configure(path, values):
            with (path / 'postgresql.auto.conf').open('a') as target:
                for key, value in values.items():
                    target.write(key + " = '" + str(value).replace("'", "''") + "'\n")

        def start(path, socket):
            # Record ownership before startup so a timeout cannot orphan a server.
            active.append(path)
            flags = shlex.join(['-c', 'listen_addresses=', '-c', 'unix_socket_directories=' + str(root / socket), '-p', '55449'])
            run([pg / 'pg_ctl', '-D', path, '-l', root / (path.name + '.log'), '-o', flags, '-w', '-t', '30', 'start'], timeout=35)

        def archive(config, mode, path, name, success=True):
            return run(['/usr/bin/python3', repo / 'tools/integration/pitr_archive.py', config, mode, path, name], success=success)

        try:
            unpacked = json.loads(run([sys.executable, repo / 'tools/release/go_bundle.py', 'verify', '--directory', options.service_bundle_directory,
                                      '--expected-manifest-sha256', options.service_bundle_manifest_sha256, '--unpack', root / 'service-package']).stdout)
            binary = root / 'service-package/service/bin/remote'
            if unpacked != package or json.loads(run([binary, 'version']).stdout) != package['version']:
                raise RuntimeError('service package changed before database startup')
            key = root / 'passphrase'
            key.write_text(os.urandom(32).hex() + '\n')
            config = {'purpose': 'dsh-pitr-fixture/v1', 'root': str(root), 'archive': str(root / 'archive'), 'key': str(key), 'gnupg': str(root / 'gnupg'), 'gpg': '/usr/bin/gpg'}
            config_file = root / 'archive.json'
            config_file.write_text(json.dumps(config))
            helper = ['/usr/bin/python3', str(repo / 'tools/integration/pitr_archive.py'), str(config_file)]
            stage = 'source-cluster'
            run([pg / 'initdb', '-D', source, '--auth=trust', '--username=dsh_pitr', '--no-locale', '--encoding=UTF8'])
            configure(source, {'wal_level': 'replica', 'archive_mode': 'on', 'archive_command': shlex.join(helper + ['store', '%p', '%f'])})
            start(source, 'sock-source')
            for migration in sorted((root / 'service-package/service/migrations').glob('*.up.sql')):
                run([pg / 'psql', '-X', '--dbname', dsn('sock-source'), '-v', 'ON_ERROR_STOP=1', '-q', '-f', migration])
            system_id = sql('select system_identifier from pg_control_system()')
            source_timeline = int(sql('select timeline_id from pg_control_checkpoint()'))
            marker = 'PRIVATE_PITR_FIXTURE_' + uuid.uuid4().hex
            sql(f"""insert into orgs(id,slug,name) values('{ORG}','pitr-fixture','PITR fixture');
                insert into users(id,issuer,subject) values('22222222-2222-4222-8222-222222222222','{ISSUER}','pitr-owner');
                insert into org_memberships(org_id,user_id,role) values('{ORG}','22222222-2222-4222-8222-222222222222','member');
                insert into devices(id,user_id,name,approval_public_jwk,approval_key_thumbprint,proof_public_jwk,proof_key_thumbprint)
                  values('44444444-4444-4444-8444-444444444444','22222222-2222-4222-8222-222222222222','fixture phone','{{}}','approval-fixture','{{}}','proof-fixture');
                insert into hosts(id,org_id,alias,state) values('11111111-1111-4111-8111-111111111111','{ORG}','fixture host','active');
                insert into installations(id,host_id,owner_user_id,public_jwk,public_key_thumbprint) values('cccccccc-cccc-4ccc-8ccc-cccccccccccc','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','{{}}','installation-fixture');
                insert into runtimes(id,installation_id,profile_ref) values('55555555-5555-4555-8555-555555555555','cccccccc-cccc-4ccc-8ccc-cccccccccccc','pitr-fixture');
                insert into runtime_grants(runtime_id,user_id,role,scope,granted_by) values('55555555-5555-4555-8555-555555555555','22222222-2222-4222-8222-222222222222','owner','{{"actions":["grant.manage","host.list"],"workspaces":[],"sessions":[]}}','22222222-2222-4222-8222-222222222222');
                create table pitr_fixture_marks(stage text primary key,marker text not null);
                insert into pitr_fixture_marks values('base','{marker}');""")
            stage = 'base-backup'
            base = root / 'base'
            run([pg / 'pg_basebackup', '--dbname', dsn('sock-source'), '--pgdata', base, '--wal-method=stream', '--checkpoint=fast', '--manifest-checksums=SHA256'], timeout=45)
            run([pg / 'pg_verifybackup', '--exit-on-error', base])
            base_tar = root / 'base.tar'
            run(['/usr/bin/tar', '-cf', base_tar, '-C', base, '.'])
            archive(config_file, 'store', base_tar, 'base.tar')
            # Retry reconciles ciphertext already published under this name.
            archive(config_file, 'store', base_tar, 'base.tar')
            shutil.rmtree(base)
            base_tar.unlink()
            stage = 'wal-target'
            sql(f"insert into pitr_fixture_marks values('wal-only','{marker}')")
            point = 'dsh-before-revoke-' + uuid.uuid4().hex
            lsn = sql("select pg_create_restore_point('" + point + "')")
            wal = sql("select pg_walfile_name('" + lsn + "'::pg_lsn)")
            sql("update devices set revoked_at=clock_timestamp();update runtime_grants set revoked_at=clock_timestamp();insert into pitr_fixture_marks values('after-target','later');")
            if sql('select revoked_at is not null from devices') != 't':
                raise RuntimeError('source revocation missing')
            sql('select pg_switch_wal()')
            deadline = time.monotonic() + 20
            while not (root / 'archive' / (wal + '.gpg')).exists() or sql('select last_archived_wal from pg_stat_archiver') != wal:
                if time.monotonic() >= deadline:
                    raise RuntimeError('encrypted target WAL was not archived')
                time.sleep(0.05)
            stage = 'ciphertext-rejection'
            wrong_key = root / 'wrong-passphrase'
            wrong_key.write_text(os.urandom(32).hex() + '\n')
            wrong = root / 'wrong.json'
            wrong.write_text(json.dumps({**config, 'key': str(wrong_key)}))
            absent = root / 'unpublished.tar'
            archive(wrong, 'restore', absent, 'base.tar', success=False)
            if absent.exists():
                raise RuntimeError('wrong key published plaintext')
            corrupt = root / 'corrupt'
            corrupt.mkdir(mode=0o700)
            damaged = bytearray((root / 'archive/base.tar.gpg').read_bytes())
            damaged[-1] ^= 1
            (corrupt / 'base.tar.gpg').write_bytes(damaged)
            broken = root / 'broken.json'
            broken.write_text(json.dumps({**config, 'archive': str(corrupt)}))
            archive(broken, 'restore', absent, 'base.tar', success=False)
            if absent.exists():
                raise RuntimeError('unauthenticated plaintext was published')
            stage = 'pitr'
            archive(config_file, 'restore', base_tar, 'base.tar')
            restored.mkdir(mode=0o700)
            run(['/usr/bin/tar', '-xf', base_tar, '-C', restored])
            run([pg / 'pg_verifybackup', '--exit-on-error', restored])
            base_tar.unlink()
            configure(restored, {'archive_mode': 'off', 'restore_command': shlex.join(helper + ['restore', '%p', '%f']),
                                 'recovery_target_name': point, 'recovery_target_action': 'promote', 'recovery_target_timeline': 'current'})
            (restored / 'recovery.signal').touch(mode=0o600)
            start(restored, 'sock-restored')
            # pg_ctl readiness can mean read-only consistent recovery, before
            # the requested target has promoted to a writable timeline.
            deadline = time.monotonic() + 20
            while sql('select pg_is_in_recovery()', 'sock-restored') != 'f':
                if time.monotonic() >= deadline:
                    raise RuntimeError('target did not promote after recovery')
                time.sleep(0.05)
            if sql("select string_agg(stage,',' order by stage) from pitr_fixture_marks", 'sock-restored') != 'base,wal-only':
                raise RuntimeError('named WAL target was not recovered')
            sql('checkpoint', 'sock-restored')
            if sql('select system_identifier from pg_control_system()', 'sock-restored') != system_id or int(sql('select timeline_id from pg_control_checkpoint()', 'sock-restored')) <= source_timeline:
                raise RuntimeError('physical recovery identity or timeline did not match')
            if sql('select d.revoked_at is null and g.revoked_at is null from devices d,runtime_grants g', 'sock-restored') != 't':
                raise RuntimeError('fixture did not restore old authority rows')
            epoch, operation = str(uuid.uuid4()), str(uuid.uuid4())
            environment = {**env, 'REMOTE_DATABASE_URL': dsn('sock-restored'), 'REMOTE_DATABASE_EPOCH': epoch, 'REMOTE_ISSUER': ISSUER,
                           'REMOTE_AUDIENCE': 'urn:dsh-pitr-fixture', 'REMOTE_PUBLIC_ORIGIN': 'https://relay.example.edu', 'REMOTE_LISTEN_ADDR': 'invalid'}

            def reject_start(value):
                result = run([binary], success=False, timeout=5, environment={**environment, 'REMOTE_DATABASE_EPOCH': value})
                if b'database recovery' not in result.stderr:
                    raise RuntimeError('service did not reject at recovery guard')

            def recovery(*arguments):
                return json.loads(run([binary, 'recovery', *arguments], environment=environment).stdout)

            stage = 'quarantine'
            reject_start(epoch)
            initial = recovery('status')
            if initial['epoch'] != ORIGINAL or initial['phase'] != 'ready':
                raise RuntimeError('unexpected restored epoch')
            prepared = recovery('quarantine', '--recovery-id', operation, '--expected-epoch', ORIGINAL, '--epoch', epoch)
            if prepared != recovery('quarantine', '--recovery-id', operation, '--expected-epoch', ORIGINAL, '--epoch', epoch) or prepared['phase'] != 'quarantined':
                raise RuntimeError('quarantine was not idempotent')
            reject_start(epoch)
            stage = 'release'
            now = datetime.now(timezone.utc)
            stamp = lambda value: value.isoformat(timespec='milliseconds').replace('+00:00', 'Z')
            authority = {'v': 1, 'complete': True, 'recovery_id': operation, 'database_epoch': epoch, 'issuer': ISSUER,
                         'captured_at': stamp(now), 'expires_at': stamp(now + timedelta(minutes=5)),
                         'users': [{'subject': 'pitr-owner', 'status': 'active', 'memberships': [{'organization_id': ORG, 'role': 'member'}]}]}
            authority_file = root / 'authority.json'
            authority_file.write_text(json.dumps(authority))
            released = recovery('release', '--recovery-id', operation, '--authority-file', str(authority_file))
            if released != recovery('release', '--recovery-id', operation, '--authority-file', str(authority_file)) or released['phase'] != 'ready':
                raise RuntimeError('release was not idempotent')
            reject_start(ORIGINAL)
            if sql('select d.revoked_at is not null and g.revoked_at is not null from devices d,runtime_grants g', 'sock-restored') != 't' or recovery('status')['epoch'] != epoch:
                raise RuntimeError('release revived old authority')
            encrypted = sorted((root / 'archive').glob('*.gpg'))
            if not encrypted or any(marker.encode() in item.read_bytes() for item in encrypted):
                raise RuntimeError('encrypted archive exposed fixture marker')
            summary = {'format': 'dsh-pitr-fixture-result/v1', 'service_source': package['source_commit'], 'base_manifest_verified': True,
                       'physical_identity_preserved': True, 'timeline_advanced': True,
                       'wal_only_marker_replayed': True, 'after_target_marker_absent': True, 'old_authority_rows_restored': True,
                       'wrong_passphrase_rejected': True, 'tampered_archive_rejected': True, 'unauthenticated_plaintext_not_published': True,
                       'external_epoch_rejected_startup': True, 'quarantine_reconciled': True, 'release_reconciled': True,
                       'old_devices_and_grants_stay_revoked': True, 'encrypted_files': {item.name: hashlib.sha256(item.read_bytes()).hexdigest() for item in encrypted},
                       'production_rpo_rto_measured': False, 'full_go_suite_run': False}
            exit_code = 0
        except Exception:
            print('owned PITR fixture failed at ' + stage, file=sys.stderr)
        finally:
            cleanup_ok = True
            for path in reversed(active):
                try:
                    subprocess.run([str(pg / 'pg_ctl'), '-D', str(path), '-m', 'immediate', '-w', '-t', '10', 'stop'], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=12)
                    status = subprocess.run([str(pg / 'pg_ctl'), '-D', str(path), 'status'], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3)
                    cleanup_ok = cleanup_ok and status.returncode == 3
                except subprocess.SubprocessError:
                    cleanup_ok = False
            try:
                stopped = subprocess.run(['/usr/bin/gpgconf', '--homedir', str(root / 'gnupg'), '--kill', 'all'], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
                cleanup_ok = cleanup_ok and stopped.returncode == 0
            except subprocess.SubprocessError:
                cleanup_ok = False
            if not cleanup_ok:
                (root / '.preserve').touch(mode=0o600)
                exit_code = 1
            for name in ('source.log', 'restored.log'):
                if (root / name).exists():
                    shutil.copyfile(root / name, options.output / name)
    if exit_code == 0:
        summary['owned_resources_stopped'] = True
        (options.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(summary, sort_keys=True), flush=True)
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
