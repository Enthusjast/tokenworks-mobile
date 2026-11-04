#!/usr/bin/env python3
"""Build and verify a local Linux/amd64 Go service bundle. Never deploys."""
import argparse
from contextlib import contextmanager
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import zlib
import host_bundle as common

FORMAT = 'dsh-remote-service-bundle/v1'
ARCHIVE = 'remote-service-linux-amd64.tgz'
MODULE = 'dsh-mobile-ustc/services/remote'
MAX_BINARY = 64 << 20
MAX_ARCHIVE = 32 << 20
MAX_TOTAL = 80 << 20
MAX_TAR = MAX_TOTAL + (1 << 20)
MAX_FILES = 512
REVISION = re.compile(r'^(?:[0-9a-f]{40}|[0-9a-f]{64})$')
SCHEMA = re.compile(r'^contracts/ws/v1/[a-z0-9][a-z0-9.-]*\.schema\.json$')
MIGRATION = re.compile(r'^([0-9]{3})_[a-z0-9_]+\.(?:up|down)\.sql$')
SUM = re.compile(r'^h1:[A-Za-z0-9+/]{43}=$')
require = common.require
digest = common.digest
encoded = common.json_bytes


def runtime_input(name):
    if name in ('services/remote/go.mod', 'services/remote/go.sum', 'contracts/openapi/remote.yaml') or SCHEMA.fullmatch(name):
        return True
    if name in ('services/remote/internal/accounts/web/index.html', 'services/remote/internal/accounts/web/app.js',
                'services/remote/internal/accounts/web/style.css'):
        return True
    return (name.startswith('services/remote/') and name.endswith('.go') and not name.endswith('_test.go')) or (
        name.startswith('deploy/sql/') and MIGRATION.fullmatch(name.removeprefix('deploy/sql/')) is not None)


def run(args, cwd, env=None, log=None):
    result = subprocess.run(args, cwd=cwd, env=env, capture_output=True, timeout=300)
    if log is not None:
        log.write(result.stdout + result.stderr)
    require(result.returncode == 0)
    return result.stdout


def source_snapshot(root):
    commit = run(['git', 'rev-parse', 'HEAD'], root).decode().strip()
    require(REVISION.fullmatch(commit))
    tree = run(['git', 'ls-tree', '-r', '-z', commit, '--', 'services/remote', 'deploy/sql', 'contracts/openapi/remote.yaml', 'contracts/ws/v1'], root)
    files = {}
    for row in tree.split(b'\0'):
        if not row:
            continue
        metadata, raw_name = row.split(b'\t', 1)
        name = raw_name.decode('utf8')
        if not runtime_input(name):
            continue
        mode, kind, _oid = metadata.split()
        require(mode == b'100644' and kind == b'blob' and common.PurePosixPath(name).as_posix() == name and '..' not in common.PurePosixPath(name).parts)
        raw = run(['git', 'show', commit + ':' + name], root)
        actual = root / name
        require(actual.is_file() and not actual.is_symlink() and actual.read_bytes() == raw and len(raw) <= 2 << 20)
        files[name] = raw
    # Uncommitted/ignored source must not be silently omitted from a release.
    for flags in (['--others', '--exclude-standard'], ['--others', '--ignored', '--exclude-standard']):
        names = run(['git', 'ls-files', '-z', *flags, '--', 'services/remote', 'deploy/sql', 'contracts/openapi/remote.yaml', 'contracts/ws/v1'], root).decode().split('\0')
        require(not any(runtime_input(name) for name in names if name))
    require(2 < len(files) <= 4096 and 'services/remote/go.mod' in files and 'services/remote/go.sum' in files)
    entries = [{'path': name, 'size': len(raw), 'sha256': digest(raw)} for name, raw in sorted(files.items())]
    return commit, entries, files


def elf(raw):
    require(64 <= len(raw) <= MAX_BINARY and raw[:7] == b'\x7fELF\x02\x01\x01')
    kind, machine, version = struct.unpack_from('<HHI', raw, 16)
    entry = struct.unpack_from('<Q', raw, 24)[0]
    executable_entry = False
    phoff = struct.unpack_from('<Q', raw, 32)[0]
    ehsize, phsize, phcount = struct.unpack_from('<HHH', raw, 52)
    require(kind == 2 and machine == 62 and version == 1 and ehsize == 64 and phsize == 56 and 1 <= phcount <= 128
            and phoff >= 64 and phoff + phcount * phsize <= len(raw))
    for index in range(phcount):
        offset = phoff + index * phsize
        segment = struct.unpack_from('<I', raw, offset)[0]
        file_offset, file_size = struct.unpack_from('<Q', raw, offset + 8)[0], struct.unpack_from('<Q', raw, offset + 32)[0]
        require(segment not in (2, 3) and file_offset <= len(raw) and file_size <= len(raw) - file_offset)
        if segment == 1:
            flags = struct.unpack_from('<I', raw, offset + 4)[0]
            address, memory_size = struct.unpack_from('<Q', raw, offset + 16)[0], struct.unpack_from('<Q', raw, offset + 40)[0]
            require(memory_size >= file_size and address + memory_size <= 1 << 64)
            executable_entry |= bool(flags & 1) and address <= entry < address + memory_size
    require(executable_entry)


def allowed(name):
    return name in ('service/bin/remote', 'service/module/go.mod', 'service/module/go.sum', 'service/contracts/openapi/remote.yaml') or SCHEMA.fullmatch(name.removeprefix('service/')) is not None or (
        name.startswith('service/migrations/') and MIGRATION.fullmatch(name.removeprefix('service/migrations/')) is not None)


def mode_for(name):
    return 0o755 if name == 'service/bin/remote' else 0o644


def pack(files):
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode='wb', filename='', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as archive:
            for name, raw in sorted(files.items()):
                item = tarfile.TarInfo(name)
                item.size, item.mode = len(raw), mode_for(name)
                item.mtime = item.uid = item.gid = 0
                archive.addfile(item, io.BytesIO(raw))
    return output.getvalue()


def inspect_archive(raw):
    require(0 < len(raw) <= MAX_ARCHIVE)
    files, total, end, order = {}, 0, 0, []
    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as source:
        unpacked = source.read(MAX_TAR + 1)
    require(len(unpacked) <= MAX_TAR)
    with tarfile.open(fileobj=io.BytesIO(unpacked), mode='r:') as archive:
        for item in archive:
            require(item.isfile() and not item.issparse() and allowed(item.name) and item.name not in files and not item.pax_headers
                    and unpacked[item.offset + 156:item.offset + 157] in (b'0', b'\0') and item.mode == mode_for(item.name)
                    and item.uid == item.gid == item.mtime == 0 and not item.uname and not item.gname and len(files) < MAX_FILES)
            limit = MAX_BINARY if item.name == 'service/bin/remote' else 2 << 20
            require(0 <= item.size <= limit)
            total += item.size
            require(total <= MAX_TOTAL)
            member = archive.extractfile(item)
            require(member is not None)
            content = member.read(item.size + 1)
            require(len(content) == item.size)
            files[item.name] = content
            order.append(item.name)
            end = item.offset_data + ((item.size + 511) // 512) * 512
    require(order == sorted(order) and len(unpacked) >= end + 1024 and not any(unpacked[end:]))
    require(all(name in files for name in ('service/bin/remote', 'service/module/go.mod', 'service/module/go.sum')))
    elf(files['service/bin/remote'])
    return files


def records(files):
    return [common.file_record(name, raw, mode_for(name)) for name, raw in sorted(files.items())]


def migrations(names):
    directions = {}
    for name in names:
        match = MIGRATION.fullmatch(name)
        require(match)
        stem, direction, _sql = name.rsplit('.', 2)
        directions.setdefault(stem, set()).add(direction)
    require(directions and all(value == {'up', 'down'} for value in directions.values()))
    numbers = sorted(int(stem.split('_', 1)[0]) for stem in directions)
    require(numbers == list(range(1, len(numbers) + 1)))


def checksums(raw):
    values = {}
    for line in raw.decode().splitlines():
        if not line.strip():
            continue
        parts = line.split()
        require(len(parts) == 3 and SUM.fullmatch(parts[2]))
        key = (parts[0], parts[1])
        require(key not in values or values[key] == parts[2])
        values[key] = parts[2]
    return values


def validate(manifest_raw, archive_raw, expected):
    require(isinstance(expected, str) and common.HEX.fullmatch(expected) and digest(manifest_raw) == expected and 0 < len(manifest_raw) <= common.MAX_MANIFEST)
    value = common.parse_json(manifest_raw)
    require(isinstance(value, dict) and set(value) == {'format', 'source', 'builder', 'toolchain', 'version', 'modules', 'artifact'} and value['format'] == FORMAT)
    source = value['source']
    require(set(source) == {'commit', 'sha256', 'inputs'} and REVISION.fullmatch(source['commit']) and isinstance(source['inputs'], list)
            and 2 < len(source['inputs']) <= 4096 and digest(encoded(source['inputs'])) == source['sha256'])
    inputs = {}
    for entry in source['inputs']:
        require(isinstance(entry, dict) and set(entry) == {'path', 'size', 'sha256'} and isinstance(entry['path'], str) and runtime_input(entry['path'])
                and common.PurePosixPath(entry['path']).as_posix() == entry['path'] and '..' not in common.PurePosixPath(entry['path']).parts
                and '\\' not in entry['path'] and not any(ord(char) < 32 or ord(char) == 127 for char in entry['path'])
                and entry['path'] not in inputs and type(entry['size']) is int and 0 <= entry['size'] <= 2 << 20 and common.HEX.fullmatch(entry['sha256']))
        inputs[entry['path']] = entry
    require(list(inputs) == sorted(inputs) and all(name in inputs for name in ('services/remote/go.mod', 'services/remote/go.sum',
            'services/remote/cmd/remote/main.go', 'services/remote/cmd/remote/version.go', 'contracts/openapi/remote.yaml', 'contracts/ws/v1/relay.schema.json')))
    require(set(value['builder']) == {'go_bundle.py', 'host_bundle.py'} and all(common.HEX.fullmatch(item) for item in value['builder'].values()))
    toolchain = value['toolchain']
    require(set(toolchain) == {'go', 'go_executable_sha256', 'platform', 'cgo', 'goamd64', 'python', 'zlib'} and re.fullmatch(r'go1\.26\.[0-9]+', toolchain['go'])
            and common.HEX.fullmatch(toolchain['go_executable_sha256']) and toolchain['platform'] == 'linux/amd64' and type(toolchain['cgo']) is int and toolchain['cgo'] == 0 and toolchain['goamd64'] == 'v1' and re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', toolchain['python'])
            and re.fullmatch(r'[0-9]+\.[0-9]+(?:\.[0-9]+)?', toolchain['zlib']))
    require(value['version'] == {'format': 'dsh-remote-service-version/v1', 'go': toolchain['go'], 'platform': 'linux/amd64',
                                'revision': source['commit'], 'source_sha256': source['sha256']})
    modules = value['modules']
    require(isinstance(modules, list) and len(modules) <= 512)
    for item in modules:
        require(set(item) == {'path', 'version', 'sum', 'go_mod_sum'} and isinstance(item['path'], str)
                and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._~/-]{0,255}', item['path']) and isinstance(item['version'], str)
                and re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+incompatible)?', item['version'])
                and SUM.fullmatch(item['sum']) and SUM.fullmatch(item['go_mod_sum']))
    require([item['path'] for item in modules] == sorted({item['path'] for item in modules}))
    artifact = value['artifact']
    require(set(artifact) == {'file', 'sha256', 'size', 'files'} and artifact['file'] == ARCHIVE and type(artifact['size']) is int
            and artifact['size'] == len(archive_raw) and artifact['sha256'] == digest(archive_raw))
    files = inspect_archive(archive_raw)
    require(isinstance(artifact['files'], list))
    for entry in artifact['files']:
        require(isinstance(entry, dict) and set(entry) == {'path', 'size', 'sha256', 'mode'}
                and isinstance(entry['path'], str) and type(entry['size']) is int and type(entry['mode']) is int
                and isinstance(entry['sha256'], str) and common.HEX.fullmatch(entry['sha256']))
    require(artifact['files'] == records(files))
    packaged_sources = {'services/remote/go.mod': 'service/module/go.mod', 'services/remote/go.sum': 'service/module/go.sum'}
    for name in inputs:
        if name.startswith('deploy/sql/'):
            packaged_sources[name] = 'service/migrations/' + name.removeprefix('deploy/sql/')
        elif name.startswith('contracts/'):
            packaged_sources[name] = 'service/' + name
    require(set(files) == {'service/bin/remote', *packaged_sources.values()})
    for name, target in packaged_sources.items():
        require(inputs[name]['size'] == len(files[target]) and inputs[name]['sha256'] == digest(files[target]))
    migrations(name.removeprefix('service/migrations/') for name in files if name.startswith('service/migrations/'))
    require(re.search(rb'(?m)^module dsh-mobile-ustc/services/remote$', files['service/module/go.mod']) and
            re.search(rb'(?m)^go 1\.26\.[0-9]+$', files['service/module/go.mod']))
    sums = checksums(files['service/module/go.sum'])
    for item in modules:
        require(sums.get((item['path'], item['version'])) == item['sum'] and sums.get((item['path'], item['version'] + '/go.mod')) == item['go_mod_sum'])
    return value, files


def unpack_files(files, destination):
    require(destination.is_absolute() and not destination.exists() and not destination.is_symlink() and destination.parent.resolve() == destination.parent)
    common.private_directory(destination.parent)
    handles = {}
    parent = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        before = os.fstat(parent)
        require(before.st_uid == os.getuid() and stat.S_IMODE(before.st_mode) == 0o700)
        os.mkdir(destination.name, mode=0o700, dir_fd=parent)
        handles[''] = os.open(destination.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        for name, raw in files.items():
            require(allowed(name))
            parts = name.split('/')
            prefix = ''
            for part in parts[:-1]:
                child = prefix + '/' + part if prefix else part
                if child not in handles:
                    os.mkdir(part, mode=0o700, dir_fd=handles[prefix])
                    handles[child] = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=handles[prefix])
                prefix = child
            descriptor = os.open(parts[-1], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode_for(name), dir_fd=handles[prefix])
            with os.fdopen(descriptor, 'wb') as output:
                os.fchmod(output.fileno(), mode_for(name))
                output.write(raw)
        after = destination.parent.lstat()
        actual = destination.lstat()
        created = os.fstat(handles[''])
        require((before.st_dev, before.st_ino) == (after.st_dev, after.st_ino) and
                (actual.st_dev, actual.st_ino) == (created.st_dev, created.st_ino))
        common.private_directory(destination)
    finally:
        for handle in reversed(list(handles.values())):
            os.close(handle)
        os.close(parent)


def verify(directory, expected, unpack=None):
    common.private_directory(directory)
    manifest = common.private_read(directory / 'manifest.json', common.MAX_MANIFEST)
    require(common.HEX.fullmatch(expected) and digest(manifest) == expected)
    raw = common.private_read(directory / ARCHIVE, MAX_ARCHIVE)
    value, files = validate(manifest, raw, expected)
    if unpack is not None:
        unpack_files(files, unpack)
    return {'manifest_sha256': expected, 'artifact_sha256': value['artifact']['sha256'], 'source_commit': value['source']['commit'], 'file_count': len(files), 'version': value['version']}


def json_stream(raw):
    text = raw.decode()
    decoder, offset, values = json.JSONDecoder(), 0, []
    while offset < len(text):
        while offset < len(text) and text[offset].isspace():
            offset += 1
        if offset == len(text):
            break
        value, offset = decoder.raw_decode(text, offset)
        values.append(value)
    return values


@contextmanager
def output_directory(local, destination):
    """Create and write only through held directory handles, even if .local
    permits group writes and a caller-visible output path is renamed."""
    handles = []
    try:
        current = os.open(local, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        handles.append(current)
        require(os.fstat(current).st_uid == os.getuid())
        parts = destination.relative_to(local).parts
        require(parts and all(part not in ('', '.', '..') for part in parts))
        for index, part in enumerate(parts):
            try:
                os.mkdir(part, mode=0o700, dir_fd=current)
            except FileExistsError:
                if index == len(parts) - 1:
                    raise
            current = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=current)
            handles.append(current)
            require(os.fstat(current).st_uid == os.getuid())
        created = os.fstat(current)
        require(stat.S_IMODE(created.st_mode) == 0o700)
        descriptor = os.open('build.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=current)
        with os.fdopen(descriptor, 'ab', buffering=0) as log:
            yield current, log
        common.private_directory(destination)
        observed = destination.lstat()
        require((observed.st_dev, observed.st_ino) == (created.st_dev, created.st_ino))
    finally:
        for handle in reversed(handles):
            os.close(handle)


def build(root, output, go):
    require(sys.platform == 'linux' and platform.machine() == 'x86_64' and go.is_absolute() and go.is_file())
    go = go.resolve()
    local = root / '.local'
    local.mkdir(exist_ok=True)
    require(output.is_absolute() and output.is_relative_to(local) and output != local and output.parent.resolve() == output.parent
            and not output.exists() and not output.is_symlink())
    commit, entries, sources = source_snapshot(root)
    source_hash = digest(encoded(entries))
    builders = {name: digest((Path(__file__).parent / name).read_bytes()) for name in ('go_bundle.py', 'host_bundle.py')}
    env = {'PATH': str(go.parent) + ':/usr/bin:/bin', 'HOME': str(Path.home()), 'LANG': 'C.UTF-8',
           'GOTOOLCHAIN': 'local', 'GOENV': 'off', 'GOWORK': 'off', 'GOPROXY': 'off', 'GOSUMDB': 'off',
           'GOMODCACHE': str(Path.home() / 'go/pkg/mod'), 'GOCACHE': str(Path.home() / '.cache/go-build'),
           'CGO_ENABLED': '0', 'GOOS': 'linux', 'GOARCH': 'amd64', 'GOAMD64': 'v1'}
    compiler_hash = digest(go.read_bytes())
    compiler = run([str(go), 'version'], root, env).decode().strip()
    require(re.fullmatch(r'go version go1\.26\.[0-9]+ linux/amd64', compiler))
    with output_directory(local, output) as (directory, log):
        with tempfile.TemporaryDirectory(prefix='remote-release-') as temporary:
            staged = Path(temporary)
            for name, raw in sources.items():
                path = staged / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
            module = staged / 'services/remote'
            run([str(go), 'mod', 'verify'], module, env, log)
            packages = json_stream(run([str(go), 'list', '-mod=readonly', '-deps', '-json', './cmd/remote'], module, env, log))
            used = set()
            for package in packages:
                record = package.get('Module')
                if record and record.get('Main'):
                    require(record['Path'] == MODULE and not package.get('EmbedFiles') and not package.get('CgoFiles'))
                elif record:
                    require(not record.get('Replace'))
                    used.add(record['Path'])
            module_records = json_stream(run([str(go), 'list', '-mod=readonly', '-m', '-json', 'all'], module, env, log))
            dependencies = []
            sums = checksums(sources['services/remote/go.sum'])
            for item in module_records:
                if item['Path'] not in used:
                    continue
                require(not item.get('Replace'))
                path, version = item['Path'], item['Version']
                dependencies.append({'path': path, 'version': version, 'sum': sums[(path, version)], 'go_mod_sum': sums[(path, version + '/go.mod')]})
            require({item['path'] for item in dependencies} == used)
            binary = staged / 'remote'
            flags = '-s -w -buildid= -X main.buildRevision=' + commit + ' -X main.buildSourceSHA256=' + source_hash
            run([str(go), 'build', '-mod=readonly', '-trimpath', '-buildvcs=false', '-buildmode=exe', '-ldflags=' + flags, '-o', str(binary), './cmd/remote'], module, env, log)
            content = binary.read_bytes()
            elf(content)
            version = common.parse_json(run([str(binary), 'version'], module, env, log))
            require(version == {'format': 'dsh-remote-service-version/v1', 'go': compiler.split()[2], 'platform': 'linux/amd64', 'revision': commit, 'source_sha256': source_hash})
            files = {'service/bin/remote': content, 'service/module/go.mod': sources['services/remote/go.mod'], 'service/module/go.sum': sources['services/remote/go.sum']}
            files.update({'service/migrations/' + name.removeprefix('deploy/sql/'): raw for name, raw in sources.items() if name.startswith('deploy/sql/')})
            files.update({'service/' + name: raw for name, raw in sources.items() if name.startswith('contracts/')})
            raw = pack(files)
        require(digest(go.read_bytes()) == compiler_hash and source_snapshot(root)[:2] == (commit, entries) and builders == {name: digest((Path(__file__).parent / name).read_bytes()) for name in builders})
        manifest = {'format': FORMAT, 'source': {'commit': commit, 'sha256': source_hash, 'inputs': entries}, 'builder': builders,
                    'toolchain': {'go': compiler.split()[2], 'go_executable_sha256': compiler_hash, 'platform': 'linux/amd64', 'cgo': 0, 'goamd64': 'v1', 'python': platform.python_version(), 'zlib': zlib.ZLIB_RUNTIME_VERSION},
                    'version': version, 'modules': sorted(dependencies, key=lambda item: item['path']),
                    'artifact': {'file': ARCHIVE, 'sha256': digest(raw), 'size': len(raw), 'files': records(files)}}
        manifest_raw = encoded(manifest)
        validate(manifest_raw, raw, digest(manifest_raw))
        for name, content in ((ARCHIVE, raw), ('manifest.json', manifest_raw)):
            descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
            with os.fdopen(descriptor, 'wb') as target:
                target.write(content)
    return verify(output, digest(manifest_raw))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    builder = commands.add_parser('build')
    builder.add_argument('--output', type=Path, required=True)
    builder.add_argument('--go-bin', type=Path, required=True)
    checker = commands.add_parser('verify')
    checker.add_argument('--directory', type=Path, required=True)
    checker.add_argument('--expected-manifest-sha256', required=True)
    checker.add_argument('--unpack', type=Path)
    args = parser.parse_args()
    try:
        result = build(Path(__file__).resolve().parents[2], args.output.absolute(), args.go_bin) if args.command == 'build' else verify(args.directory, args.expected_manifest_sha256, args.unpack)
        print(json.dumps(result, sort_keys=True))
    except (common.BundleError, OSError, ValueError, TypeError, KeyError, AttributeError, RecursionError, struct.error, tarfile.TarError, subprocess.SubprocessError):
        print('Go bundle build/verification failed; no verified result was produced', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
