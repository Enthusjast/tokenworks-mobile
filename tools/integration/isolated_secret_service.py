#!/usr/bin/env python3
"""Own a temporary Secret Service on an already isolated session bus."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

from gi.repository import Gio, GLib


def main():
    root, node, client, config = sys.argv[1:]
    root = Path(root)
    # The caller provides an allowlisted environment, then dbus-run-session
    # creates this bus. Never fall back to an existing desktop session.
    if not os.environ.get('DBUS_SESSION_BUS_ADDRESS') or Path(os.environ['HOME']) != root / 'home':
        return 2
    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    password = base64.b64encode(os.urandom(32))
    control = root / 'control'
    daemon = None
    stage = 'startup'

    def call(destination, path, interface, method, parameters=None):
        return bus.call_sync(destination, path, interface, method, parameters, None,
                             Gio.DBusCallFlags.NO_AUTO_START, 2000, None).unpack()

    def has_owner():
        return call('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                    'NameHasOwner', GLib.Variant('(s)', ('org.freedesktop.secrets',)))[0]

    def stop():
        nonlocal daemon
        if daemon is not None:
            if daemon.poll() is None:
                daemon.terminate()
            try:
                daemon.wait(timeout=3)
            except subprocess.TimeoutExpired:
                daemon.kill()
                daemon.wait(timeout=3)
            daemon = None
            deadline = time.monotonic() + 2
            while has_owner():
                if time.monotonic() >= deadline:
                    raise RuntimeError('private service ownership survived shutdown')
                time.sleep(0.025)

    def start():
        nonlocal daemon
        if has_owner():
            raise RuntimeError('unexpected service owner on private bus')
        if control.exists():
            shutil.rmtree(control)
        control.mkdir(mode=0o700)
        daemon = subprocess.Popen(['/usr/bin/gnome-keyring-daemon', '--foreground', '--unlock',
                                   '--components=secrets', '--control-directory', str(control)],
                                  stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        daemon.stdin.write(password)
        daemon.stdin.close()
        deadline = time.monotonic() + 5
        while not has_owner():
            if daemon.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('private keyring unavailable')
            time.sleep(0.025)

    def phase(name):
        result = subprocess.run([node, client, config, name], stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25)
        # Never forward native paths, private assertion details or command stderr.
        if result.returncode != 0 or result.stdout.strip() != (name + '-ok').encode():
            raise RuntimeError('client phase failed')

    def locked(collection):
        value = bus.call_sync('org.freedesktop.secrets', collection, 'org.freedesktop.DBus.Properties',
                              'Get', GLib.Variant('(ss)', ('org.freedesktop.Secret.Collection', 'Locked')),
                              None, Gio.DBusCallFlags.NO_AUTO_START, 2000, None)
        return value.get_child_value(0).get_variant().get_boolean()

    def unlock(collection, value):
        # GNOME-specific test coordination, not an application unlock adapter.
        # A plain Secret session is confined to this private bus and uses only
        # the synthetic keyring password generated above.
        _, session = call('org.freedesktop.secrets', '/org/freedesktop/secrets',
                          'org.freedesktop.Secret.Service', 'OpenSession',
                          GLib.Variant('(sv)', ('plain', GLib.Variant('s', ''))))
        try:
            call('org.freedesktop.secrets', '/org/freedesktop/secrets',
                 'org.gnome.keyring.InternalUnsupportedGuiltRiddenInterface', 'UnlockWithMasterPassword',
                 GLib.Variant('(o(oayays))', (collection, (session, [], list(value), 'text/plain'))))
        finally:
            call('org.freedesktop.secrets', session, 'org.freedesktop.Secret.Session', 'Close')

    try:
        start()
        stage = 'provision'
        master = base64.b64encode(os.urandom(32))
        settings = json.loads(Path(config).read_text())
        subprocess.run([settings['executable'], 'store', '--label=DSH isolated journal test',
                        'service', 'dsh-isolated-journal', 'account', 'fixture'], input=master,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=True)
        del master
        stage = 'create'
        phase('create')
        collection = call('org.freedesktop.secrets', '/org/freedesktop/secrets',
                          'org.freedesktop.Secret.Service', 'ReadAlias', GLib.Variant('(s)', ('default',)))[0]
        if collection == '/':
            raise RuntimeError('missing private default collection')
        stage = 'lock'
        objects, prompt = call('org.freedesktop.secrets', '/org/freedesktop/secrets',
                               'org.freedesktop.Secret.Service', 'Lock', GLib.Variant('(ao)', ([collection],)))
        if collection not in objects or prompt != '/' or not locked(collection):
            raise RuntimeError('private collection did not lock')
        phase('locked')
        stage = 'wrong-password'
        try:
            unlock(collection, b'incorrect-isolated-test-password')
        except GLib.Error:
            pass
        if not locked(collection):
            raise RuntimeError('incorrect password unlocked collection')
        stage = 'unlock'
        unlock(collection, password)
        deadline = time.monotonic() + 2
        while locked(collection):
            if time.monotonic() >= deadline:
                stage = 'unlock-state'
                raise RuntimeError('private collection did not unlock')
            time.sleep(0.025)
        phase('reopened')
        stage = 'restart'
        stop()
        start()
        phase('restarted')
        print(json.dumps({'installation_signing': True, 'protected_journal': True, 'locked_rejected': True,
                          'wrong_password_rejected': True, 'unlock_reopened': True, 'daemon_restart_reopened': True}))
        return 0
    except Exception:
        print('isolated Secret Service failed at ' + stage, file=sys.stderr)
        return 1
    finally:
        stop()


if __name__ == '__main__':
    raise SystemExit(main())
