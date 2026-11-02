# Focused file lifecycle composition

After a coherent file-service or Host file-adapter change, run once from the
repository root:

```sh
REMOTE_TEST_GO=/opt/go1.26.10/bin/go python3 tools/integration/run_remote_dsh.py --file-lifecycle
```

Requires Node 24, installed repository npm dependencies and PostgreSQL 18 tools
(`REMOTE_TEST_PG_BIN` can override their directory). Run as an unprivileged user.
The runner creates a private socket-only PostgreSQL cluster, applies migrations,
builds the Go service, then runs exactly three race-instrumented Go cases in
`./internal/httpapi`:

- Native file handoff, receipt use, revocation, reconciliation and cross-node handoff.
- Workspace and persisted attachment export through TLS, object storage and the normal Host plugin.
- Production Go process configuration, scanner self-test readiness, clean/rejected scans,
  operational metrics and graceful worker shutdown.

Each case owns a separate database. Scanner and S3-compatible storage are local
fixtures. Native Host peers use the real npm DSH 0.2.1-rc.5 Loader with a deterministic
offline model. This lane does not launch the installed `dsh` CLI or verify its profile
composition. It does not build Host `lib`, run other Go packages, the general Node
composition, Android, platform or load tests. Use a separate focused CLI check when
changing installed-CLI compatibility.

For installed-CLI file compatibility on Linux, build the plugin and select just
the file adapter test (set the absolute CLI path for the local installation):

```sh
npm run build
REMOTE_TEST_DSH_BIN=/absolute/path/to/dsh node --import tsx --test host/remote-control/tests/dsh-cli-files.integration.test.ts
```

This check creates a temporary web profile with isolated `HOME` and `DSH_HOME`,
loads the normal compiled plugin, creates a session through its local IPC, and
checks native receipt admission, persisted attachment bytes, checksum rejection,
attachment export, declared workspace export, symlink denial and workspace-change
adapter availability inside that same CLI process. A test-only plugin exercises
the internal file adapters through a private Unix socket. It uses the offline
model and does not start Go/PostgreSQL or repeat the TLS composition. No CLI output
is copied into test reports because the native web launcher prints auth tokens.

The installed CLI can resolve its native filesystem packages separately from the
plugin's npm peers. Host read adapters compare exact native constructor identity,
including the fixed native modules resolved by the CLI's Loader. Class names,
subclasses and arbitrary filesystem providers do not grant Host read access.

The flag cannot be combined with other composition, bundle or load options. The
runner stops its own cluster and removes temporary state even if a selected test
fails. It leaves the local development stack running.

Runner selection and startup guards can be checked without starting external tools:

```sh
python3 -m unittest discover -s tools/integration -p test_file_lifecycle_runner.py
```
