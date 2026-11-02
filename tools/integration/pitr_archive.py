#!/usr/bin/env python3
"""Encrypted archive/restore command for the owned PITR fixture, never production."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

MAX_BYTES = 256 << 20
NAME = re.compile(r'(?:[0-9A-F]{24}(?:\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history|base\.tar)')


def private_file(path, maximum):
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or stat.S_IMODE(before.st_mode) != 0o600 or before.st_nlink != 1 or not 0 < before.st_size <= maximum:
        raise ValueError('private fixture file required')
    return before


def private_directory(path):
    info = path.lstat()
    if path.resolve() != path or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError('private fixture directory required')


def inside(root, path):
    value = Path(path)
    if value.is_symlink():
        raise ValueError('fixture symlink refused')
    value = value.resolve()
    if value == root or not value.is_relative_to(root):
        raise ValueError('fixture path escaped')
    return value


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(65536), b''):
            value.update(block)
    return value.digest()


def execute(config_file, operation, path, name):
    private_file(config_file, 8192)
    config = json.loads(config_file.read_text())
    if set(config) != {'purpose', 'root', 'archive', 'key', 'gnupg', 'gpg'} or config['purpose'] != 'dsh-pitr-fixture/v1' or not NAME.fullmatch(name):
        raise ValueError('invalid fixture configuration')
    root = Path(config['root'])
    private_directory(root)
    if not root.name.startswith('dsh-pitr-fixture-'):
        raise ValueError('not an owned fixture root')
    archive, key, gnupg = [inside(root, config[field]) for field in ('archive', 'key', 'gnupg')]
    private_directory(archive)
    private_directory(gnupg)
    private_file(key, 256)
    with os.fdopen(os.open(key, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as source:
        secret = source.read(257)
    if not re.fullmatch(b'[0-9a-f]{64}\n', secret):
        raise ValueError('invalid fixture key')
    gpg = Path(config['gpg'])
    program = gpg.lstat()
    if not gpg.is_absolute() or not stat.S_ISREG(program.st_mode) or program.st_uid not in (0, os.getuid()) or program.st_mode & 0o7022 or not program.st_mode & 0o111:
        raise ValueError('untrusted fixture GPG')
    target = archive / (name + '.gpg')
    path = inside(root, path)
    env = {'PATH': '/usr/bin:/bin', 'HOME': str(root / 'home'), 'GNUPGHOME': str(gnupg), 'LANG': 'C.UTF-8'}

    def crypt(source, destination, encrypt):
        private_file(source, MAX_BYTES + (64 << 10))
        command = [str(gpg), '--no-options', '--homedir', str(gnupg), '--batch', '--no-tty', '--pinentry-mode', 'loopback',
                   '--passphrase-fd', '0', '--no-symkey-cache', '--yes', '--output', str(destination)]
        command += ['--cipher-algo', 'AES256', '--s2k-digest-algo', 'SHA256', '--compress-algo', 'none', '--force-mdc', '--symmetric'] if encrypt else ['--decrypt']
        subprocess.run(command + [str(source)], input=secret, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       check=True, timeout=25)
        private_file(destination, MAX_BYTES + (64 << 10))
        with destination.open('rb') as result:
            os.fsync(result.fileno())

    parent = archive if operation == 'store' else path.parent
    private_directory(parent)
    descriptor, temporary = tempfile.mkstemp(prefix='.pitr-', dir=parent)
    os.close(descriptor)
    temporary = Path(temporary)
    try:
        if operation == 'store':
            private_file(path, MAX_BYTES)
            crypt(path, temporary, True)
            try:
                os.link(temporary, target)
            except FileExistsError:
                # Retry must reconcile the existing encrypted bytes, never
                # overwrite a corrupt or different WAL archive with a new one.
                check_fd, check = tempfile.mkstemp(prefix='.pitr-check-', dir=root)
                os.close(check_fd)
                check = Path(check)
                try:
                    crypt(target, check, False)
                    if digest(check) != digest(path):
                        raise ValueError('conflicting fixture archive')
                finally:
                    check.unlink(missing_ok=True)
        elif operation == 'restore':
            # GPG can stream plaintext before final integrity validation. Only
            # publish after GPG's successful integrity check and fsync.
            crypt(target, temporary, False)
            os.replace(temporary, path)
        else:
            raise ValueError('unknown fixture operation')
        directory = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    try:
        if len(sys.argv) != 5:
            raise ValueError('invalid arguments')
        execute(Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4])
        return 0
    except Exception:
        print('owned PITR archive unavailable', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
