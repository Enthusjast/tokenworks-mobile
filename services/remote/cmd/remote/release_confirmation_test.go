package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"dsh-mobile-ustc/services/remote/internal/releases"
	"dsh-mobile-ustc/services/remote/internal/store"
	"dsh-mobile-ustc/services/remote/internal/testsupport"
)

func TestAppReleaseConfirmationCLIUsesCurrentEpochAndReconcilesOutput(t *testing.T) {
	binary := os.Getenv("REMOTE_TEST_SERVICE_BIN")
	if binary == "" {
		t.Skip("explicit disposable production binary required")
	}
	ctx, db := testsupport.PostgresDatabase(t)
	postgres := &store.Postgres{DB: db}
	dsn := archiveTestDSN(t, ctx, db)
	raw, err := os.ReadFile("../../../../deploy/android/releases.example.json")
	if err != nil {
		t.Fatal(err)
	}
	catalog, err := releases.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	ref, _ := catalog.Policy()
	if err = postgres.RegisterAppReleaseCatalog(ctx, store.OriginalDatabaseEpoch, catalog); err != nil {
		t.Fatal(err)
	}
	const epoch = "88888888-8888-4888-8888-888888888888"
	const recovery = "77777777-7777-4777-8777-777777777777"
	if _, err = postgres.QuarantineRecovery(ctx, recovery, store.OriginalDatabaseEpoch, epoch); err != nil {
		t.Fatal(err)
	}
	authority := store.RecoveryAuthority{Version: 1, Complete: true, RecoveryID: recovery, DatabaseEpoch: epoch, Issuer: "https://id.example.edu", CapturedAt: time.Now(), ExpiresAt: time.Now().Add(time.Minute), Users: []store.RecoveryUser{}}
	if _, err = postgres.ReleaseRecovery(ctx, epoch, authority.Issuer, authority); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "catalog.json")
	if err = os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	_ = listener.Close()
	failedCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	failed := exec.CommandContext(failedCtx, binary)
	failed.Env = []string{"PATH=" + os.Getenv("PATH"), "REMOTE_DATABASE_URL=" + dsn, "REMOTE_DATABASE_EPOCH=" + epoch,
		"REMOTE_LISTEN_ADDR=" + address, "REMOTE_PUBLIC_ORIGIN=http://" + address, "REMOTE_RELAY_NODE_ID=55555555-5555-4555-8555-555555555555",
		"REMOTE_ISSUER=https://id.example.edu", "REMOTE_AUDIENCE=urn:release-test", "REMOTE_JWKS_URL=https://id.example.edu/jwks",
		"REMOTE_CLIENT_IDS=fixture-phone", "REMOTE_REQUIRED_SCOPES=remote.access", "REMOTE_APP_RELEASES_FILE=" + path}
	output, err := failed.CombinedOutput()
	if err == nil || failedCtx.Err() != nil || !bytes.Contains(output, []byte(errReleaseConfiguration.Error())) {
		t.Fatal("restored policy allowed ordinary production startup", err)
	}
	if connection, err := net.DialTimeout("tcp", address, 100*time.Millisecond); err == nil {
		_ = connection.Close()
		t.Fatal("unconfirmed startup opened listener")
	}
	t.Setenv("REMOTE_DATABASE_URL", dsn)
	t.Setenv("REMOTE_DATABASE_EPOCH", epoch)
	args := []string{"confirm", "--catalog-file", path, "--operation-id", "11111111-1111-4111-8111-111111111111", "--expected-sha256", ref.SHA256}
	if err = runAppReleaseCommand(args, archiveFailedOutput{}); err == nil || !strings.Contains(err.Error(), "same operation UUID") {
		t.Fatal("lost confirmation output was not reconcilable", err)
	}
	call := func(args ...string) []byte {
		t.Helper()
		command := exec.CommandContext(ctx, binary, append([]string{"app-releases"}, args...)...)
		command.Env = []string{"PATH=" + os.Getenv("PATH"), "REMOTE_DATABASE_URL=" + dsn, "REMOTE_DATABASE_EPOCH=" + epoch}
		output, err := command.Output()
		if err != nil {
			t.Fatal("production confirmation command failed", err)
		}
		return output
	}
	var receipt store.AppReleasePolicyConfirmation
	if json.Unmarshal(call(args...), &receipt) != nil || receipt.DatabaseEpoch != epoch || receipt.Revision != 1 || receipt.PolicySHA256 != ref.SHA256 {
		t.Fatal("confirmation retry lost receipt")
	}
	var status store.AppReleasePolicyStatus
	if json.Unmarshal(call("status", "--package-name", ref.PackageName), &status) != nil || !status.Current || status.ValidatedEpoch != epoch || status.Revision != 1 {
		t.Fatal("confirmation did not establish current epoch")
	}
	if _, err = loadRegisteredAppReleases(ctx, path, postgres, epoch); err != nil {
		t.Fatal("confirmed catalog could not load", err)
	}
}
