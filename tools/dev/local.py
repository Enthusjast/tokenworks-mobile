#!/usr/bin/env python3
"""Persistent loopback development stack: real Go service and installed npm DSH.

Keeps a private PostgreSQL cluster and fixture authority across restarts. No
school login, model request, mobile build, vendor push or load probe is started.
"""
import argparse
import fcntl
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / '.local/dev'
STATUS = STATE / 'status.json'
PG = Path(os.environ.get('REMOTE_DEV_PG_BIN', '/usr/lib/postgresql/18/bin'))
SOCKET = STATE / 'ipc/probe.sock'
PORTS = {'relay': 8080, 'issuer': 8082, 'dsh': 3000}


def save(path, value):
    temporary = path.with_suffix(path.suffix + '.new')
    with open(temporary, 'w', encoding='utf8') as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write('\n')
    os.replace(temporary, path)


def identity(pid):
    try:
        # The process comm may contain spaces or parentheses.
        return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[19]
    except (OSError, IndexError):
        return None


def running(state):
    return isinstance(state.get('pid'), int) and state.get('started') is not None and identity(state['pid']) == state['started']


def read_status():
    try:
        return json.loads(STATUS.read_text())
    except FileNotFoundError:
        return {}


def health(url):
    from urllib.request import ProxyHandler, build_opener
    try:
        with build_opener(ProxyHandler({})).open(url, timeout=2) as response:
            return response.status == 204
    except Exception:
        return False


def probe():
    connection = http.client.HTTPConnection('localhost', timeout=2)
    try:
        transport = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        transport.settimeout(2)
        transport.connect(str(SOCKET))
        connection.sock = transport
        connection.request('GET', '/m0/probe')
        response = connection.getresponse()
        body = response.read(65536)
        if response.status != 200:
            return None
        return json.loads(body)['runtime_epoch']
    except (OSError, ValueError):
        return None
    finally:
        connection.close()


def snapshot():
    paths = [*list((ROOT / 'services/remote').rglob('*.go')),
             ROOT / 'services/remote/go.mod', ROOT / 'services/remote/go.sum',
             *list((ROOT / 'deploy/sql').glob('*.up.sql'))]
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in paths if not path.name.endswith('_test.go')}


def terminate(child):
    if child is None or child.poll() is not None:
        return
    child.send_signal(signal.SIGTERM)
    try:
        child.wait(timeout=10)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait(timeout=5)


def serve(profile):
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    if STATE.is_symlink() or STATE.stat().st_uid != os.getuid() or STATE.stat().st_mode & 0o777 != 0o700:
        raise RuntimeError('development state directory must be private and owned by this user')
    lock = open(STATE / 'supervisor.lock', 'a')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError('development supervisor is already running')
    os.umask(0o077)
    stopping = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopping.set())
    go = os.environ.get('REMOTE_DEV_GO') or shutil.which('go') or '/opt/go1.26.10/bin/go'
    dsh = shutil.which('dsh')
    node = shutil.which('node')
    if not dsh or not node or not Path(go).is_file() or not (PG / 'pg_ctl').is_file():
        raise RuntimeError('installed dsh, Node 24, Go and PostgreSQL are required')
    dsh_version = subprocess.check_output([dsh, '--version'], text=True).strip()
    if not re.fullmatch(r'0\.2\.1(?:-rc\.[0-9]+)?', dsh_version):
        raise RuntimeError('this development composition requires installed npm dsh 0.2.1')
    for port in PORTS.values():
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            check.bind(('127.0.0.1', port))
    state = {'pid': os.getpid(), 'started': identity(os.getpid()), 'state': 'starting', 'profile': profile, 'dsh_version': dsh_version,
             'relay': 'http://127.0.0.1:8080', 'dsh': 'http://127.0.0.1:3000',
             'socket': str(SOCKET), 'fixture_identity': True, 'relay_plugin_connected': False,
             'go_reloads': 0, 'children': {}}
    save(STATUS, state)
    children = {}
    database_started = False
    issuer = None

    def log(message):
        print(time.strftime('%Y-%m-%d %H:%M:%S'), message, flush=True)

    def command(args, cwd=ROOT, input=None):
        subprocess.run(args, cwd=cwd, input=input, check=True, timeout=180)

    def launch(name, args, env=None):
        with open(STATE / (name + '.log'), 'ab', buffering=0) as output:
            child = subprocess.Popen(args, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
        children[name] = child
        state['children'][name] = child.pid
        save(STATUS, state)
        return child

    dsn = f'host={STATE}/pg-socket port=55440 user=dsh_dev dbname=postgres sslmode=disable'

    def query(text):
        return subprocess.check_output([str(PG / 'psql'), '-X', '--dbname', dsn, '-v', 'ON_ERROR_STOP=1', '-At'], input=text.encode(), timeout=30).decode().strip()

    def migrate():
        query('create table if not exists local_dev_migrations(name text primary key,sha256 text not null)')
        applied = dict(line.split('|') for line in query('select name,sha256 from local_dev_migrations order by name').splitlines())
        for path in sorted((ROOT / 'deploy/sql').glob('*.up.sql')):
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if path.name in applied:
                if applied[path.name] != digest:
                    raise RuntimeError(f'applied migration changed: {path.name}; preserve the database and migrate explicitly')
                continue
            # Migrations can own BEGIN/COMMIT, so record only after successful
            # execution. A crash in between fails visibly on the next startup.
            command([str(PG / 'psql'), '-X', '--dbname', dsn, '-v', 'ON_ERROR_STOP=1', '-q', '-f', str(path)])
            query(f"insert into local_dev_migrations values ('{path.name}','{digest}')")
            log('applied ' + path.name)

    try:
        (STATE / 'pg-socket').mkdir(mode=0o700, exist_ok=True)
        (STATE / 'ipc').mkdir(mode=0o700, exist_ok=True)
        (STATE / 'journal').mkdir(mode=0o700, exist_ok=True)
        data = STATE / 'pgdata'
        if not data.exists():
            command([str(PG / 'initdb'), '-D', str(data), '--auth=trust', '--username=dsh_dev', '--no-locale', '--encoding=UTF8'])
        existing = subprocess.run([str(PG / 'pg_ctl'), '-D', str(data), 'status'], capture_output=True)
        if existing.returncode == 0:
            raise RuntimeError('owned development database is already running; stop it explicitly before starting another supervisor')
        command([str(PG / 'pg_ctl'), '-D', str(data), '-l', str(STATE / 'postgres.log'), '-o', f"-h '' -k {STATE}/pg-socket -p 55440", '-w', 'start'])
        database_started = True
        migrate()
        key = STATE / 'fixture-authority.json'
        if not key.exists():
            script = "const fs=require('node:fs'),c=require('node:crypto');const k=c.generateKeyPairSync('ec',{namedCurve:'prime256v1'});fs.writeFileSync(process.argv[1],JSON.stringify({private:k.privateKey.export({format:'jwk'}),public:{...k.publicKey.export({format:'jwk'}),kid:'local-dev-only',use:'sig',alg:'ES256'}}),{mode:0o600,flag:'wx'})"
            command([node, '-e', script, str(key)])

        class FixtureKeys(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != '/jwks.json':
                    self.send_error(404)
                    return
                body = json.dumps({'keys': [json.loads(key.read_text())['public']]}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_):
                pass

        issuer = ThreadingHTTPServer(('127.0.0.1', PORTS['issuer']), FixtureKeys)
        threading.Thread(target=issuer.serve_forever, daemon=True).start()
        remote_env = {name: value for name, value in os.environ.items() if not name.startswith('REMOTE_')}
        remote_env.update(REMOTE_ISSUER='http://127.0.0.1:8082', REMOTE_AUDIENCE='urn:dsh-remote:local-dev',
                          REMOTE_JWKS_URL='http://127.0.0.1:8082/jwks.json', REMOTE_CLIENT_IDS='local-dev-phone,local-dev-host',
                          REMOTE_HOST_CLIENT_IDS='local-dev-host', REMOTE_REQUIRED_SCOPES='remote.access',
                          REMOTE_PUBLIC_ORIGIN='http://127.0.0.1:8080', REMOTE_LISTEN_ADDR='127.0.0.1:8080',
                          REMOTE_DATABASE_URL=dsn, REMOTE_RELAY_NODE_ID='da96f9e7-c59e-484d-a826-3fca09eb18bd')

        def rebuild():
            log('building Go relay')
            with open(STATE / 'go-build.log', 'ab', buffering=0) as output:
                result = subprocess.run([go, 'build', '-o', str(STATE / 'remote-next'), './cmd/remote'],
                                        cwd=ROOT / 'services/remote', stdout=output, stderr=subprocess.STDOUT, timeout=180)
            if result.returncode:
                state['last_error'] = 'Go build failed; previous relay retained'
                save(STATUS, state)
                log(state['last_error'])
                return False
            migrate()
            terminate(children.get('relay'))
            os.replace(STATE / 'remote-next', STATE / 'remote')
            launch('relay', [str(STATE / 'remote')], remote_env)
            state.pop('last_error', None)
            state['go_reloads'] += 1
            save(STATUS, state)
            log('Go relay started')
            return True

        if not rebuild():
            raise RuntimeError('initial Go build failed')
        command(['npm', 'run', 'build'])
        # Install the linked package in the actual npm DSH profile. Do not
        # replace its user patch, credentials, bundles, settings or sessions.
        command([dsh, 'plugin', '--profile', profile, 'add', 'link:' + str(ROOT / 'host/remote-control')])
        runtime_file = STATE / 'runtime.json'
        if not runtime_file.exists():
            save(runtime_file, {'runtime_id': str(uuid.uuid4())})
        runtime = json.loads(runtime_file.read_text())['runtime_id']
        overlay = [
            {'id': 'hmr', 'config': {'base': ROOT.as_uri() + '/', 'root': ['host/remote-control/lib'],
                                    'ignored': ['**/node_modules/**', '**/*.map', '**/*.d.ts'], 'debounce': 500}},
            {'insert': [{'id': 'ustc-remote-control-dev', 'name': '@ustc/dsh-remote-control',
                         'config': {'enabled': True, 'runtimeId': runtime, 'socketPath': str(SOCKET),
                                    'commandJournalPath': str(STATE / 'journal/commands.sqlite'),
                                    'workspaces': [{'id': 'project', 'label': 'DSH remote development', 'cwd': str(ROOT)}],
                                    'presets': []}}]},
        ]
        # JSON is a YAML subset and preserves exact paths without templating.
        save(STATE / 'dsh.patch.yml', overlay)
        launch('typescript', [node, str(ROOT / 'node_modules/typescript/bin/tsc'), '-p', str(ROOT / 'host/remote-control/tsconfig.build.json'),
                              '--watch', '--preserveWatchOutput', '--noEmitOnError'])
        launch('dsh', [node, '--expose-internals', str(Path(dsh).resolve()), '--profile', profile,
                       '--patch', str(STATE / 'dsh.patch.yml'), '--host', '127.0.0.1', '--port', '3000', '--no-open'])
        observed = snapshot()
        deadline = time.monotonic() + 60
        while not stopping.is_set():
            for name, child in children.items():
                if child.poll() is not None:
                    if name == 'relay' and state['state'] == 'running':
                        message = 'Go relay exited; waiting for source change'
                        if state.get('last_error') != message:
                            state['last_error'] = message
                            save(STATUS, state)
                            log(message)
                        continue
                    raise RuntimeError(name + ' exited; inspect its development log')
            ready = health('http://127.0.0.1:8080/readyz') and probe() is not None
            if ready and state['state'] != 'running':
                state['state'] = 'running'
                save(STATUS, state)
                log('development stack ready; Go source reload and DSH module HMR enabled')
            if state['state'] != 'running' and time.monotonic() > deadline:
                raise RuntimeError('development stack did not become ready; inspect relay.log and dsh.log')
            changed = snapshot()
            if changed != observed:
                # Allow editor atomic saves and related source writes to settle.
                if stopping.wait(0.5):
                    break
                observed = snapshot()
                try:
                    rebuild()
                except (RuntimeError, subprocess.SubprocessError) as error:
                    state['last_error'] = str(error)
                    save(STATUS, state)
                    log('reload failed; inspect development logs')
            stopping.wait(0.5)
    finally:
        for name in ('dsh', 'typescript', 'relay'):
            terminate(children.get(name))
        if issuer:
            issuer.shutdown()
            issuer.server_close()
        if database_started:
            subprocess.run([str(PG / 'pg_ctl'), '-D', str(STATE / 'pgdata'), '-m', 'fast', '-w', 'stop'], timeout=30)
        state['state'] = 'stopped'
        save(STATUS, state)
        lock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'run', 'status', 'stop'])
    parser.add_argument('--profile', default='web')
    args = parser.parse_args()
    if args.action == 'run':
        serve(args.profile)
        return
    state = read_status()
    if args.action == 'status':
        state['supervisor_alive'] = running(state)
        state['relay_ready'] = health('http://127.0.0.1:8080/readyz')
        state['plugin_epoch'] = probe()
        print(json.dumps(state, ensure_ascii=False, indent=2))
        return
    if args.action == 'stop':
        if running(state):
            os.kill(state['pid'], signal.SIGTERM)
            for _ in range(60):
                if not running(state) or read_status().get('state') == 'stopped':
                    print('development stack stopped; database and profile link retained')
                    return
                time.sleep(0.5)
            raise RuntimeError('supervisor is still stopping; inspect its log')
        print('development stack is not running')
        return
    if running(state):
        print('development stack already running; use status for live readiness')
        return
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    with open(STATE / 'supervisor.log', 'ab', buffering=0) as output:
        os.fchmod(output.fileno(), 0o600)
        child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), 'run', '--profile', args.profile], cwd=ROOT,
                                 stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    print(f'development supervisor started pid={child.pid}; logs: {STATE}', flush=True)
    for _ in range(120):
        current = read_status()
        if current.get('pid') == child.pid and current.get('state') == 'running':
            print('ready: relay http://127.0.0.1:8080; dsh http://127.0.0.1:3000; both reloaders enabled')
            return
        if child.poll() is not None:
            raise RuntimeError('development startup failed; inspect .local/dev/supervisor.log')
        time.sleep(0.5)
    raise RuntimeError('development startup is still pending; inspect status and private logs')


if __name__ == '__main__':
    main()
