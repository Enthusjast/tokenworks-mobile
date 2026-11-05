package main

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/releases"
	"tokenworks-mobile/services/remote/internal/store"
	"tokenworks-mobile/services/remote/internal/testsupport"
)

func TestAppReleaseDisabledRegistrationDoesNoDatabaseIO(t *testing.T) {
	if catalog, err := loadRegisteredAppReleases(t.Context(), "", nil, "invalid"); err != nil || catalog != nil {
		t.Fatal("disabled release registry performed work", err)
	}
}

func TestAppReleaseProductionStartupRejectsReboundArtifactBeforeListening(t *testing.T) {
	binary := os.Getenv("REMOTE_TEST_SERVICE_BIN")
	if binary == "" {
		t.Skip("explicit disposable production binary required")
	}
	ctx, db := testsupport.PostgresDatabase(t)
	dsn := archiveTestDSN(t, ctx, db)
	raw, err := os.ReadFile("../../../../deploy/android/releases.example.json")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "releases.json")
	write := func(body []byte) {
		t.Helper()
		if err := os.WriteFile(path, body, 0600); err != nil {
			t.Fatal(err)
		}
	}
	write(raw)
	reserved, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := reserved.Addr().String()
	_ = reserved.Close()
	origin := "http://" + address
	env := []string{"PATH=" + os.Getenv("PATH"), "REMOTE_DATABASE_URL=" + dsn, "REMOTE_DATABASE_EPOCH=" + store.OriginalDatabaseEpoch,
		"REMOTE_RELAY_NODE_ID=77777777-7777-4777-8777-777777777777", "REMOTE_LISTEN_ADDR=" + address, "REMOTE_PUBLIC_ORIGIN=" + origin,
		"REMOTE_ISSUER=https://id.example.edu", "REMOTE_AUDIENCE=urn:remote", "REMOTE_JWKS_URL=https://id.example.edu/jwks",
		"REMOTE_CLIENT_IDS=fixture-phone,fixture-host", "REMOTE_HOST_CLIENT_IDS=fixture-host", "REMOTE_REQUIRED_SCOPES=remote.access", "REMOTE_APP_RELEASES_FILE=" + path}
	client := &http.Client{Timeout: 200 * time.Millisecond, Transport: &http.Transport{Proxy: nil}}
	defer client.CloseIdleConnections()
	start := func() func() {
		t.Helper()
		command := exec.CommandContext(ctx, binary)
		command.Env = env
		log, err := os.OpenFile(filepath.Join(t.TempDir(), "service.log"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			t.Fatal(err)
		}
		command.Stdout = log
		command.Stderr = log
		if err = command.Start(); err != nil {
			_ = log.Close()
			t.Fatal(err)
		}
		done := make(chan struct{})
		var result error
		go func() { result = command.Wait(); close(done) }()
		t.Cleanup(func() {
			select {
			case <-done:
			default:
				_ = command.Process.Kill()
				<-done
			}
			_ = log.Close()
		})
		ready := false
		for deadline := time.Now().Add(8 * time.Second); time.Now().Before(deadline); {
			select {
			case <-done:
				t.Fatal("service stopped before readiness", result)
			default:
			}
			response, err := client.Get(origin + "/readyz")
			if err == nil {
				_ = response.Body.Close()
				if response.StatusCode == 204 {
					ready = true
					break
				}
			}
			select {
			case <-ctx.Done():
				t.Fatal("fixture timed out")
			case <-time.After(25 * time.Millisecond):
			}
		}
		if !ready {
			t.Fatal("registered service did not become ready")
		}
		return func() {
			t.Helper()
			_ = command.Process.Signal(syscall.SIGTERM)
			select {
			case <-done:
				if result != nil {
					t.Fatal("normal service shutdown failed", result)
				}
			case <-time.After(8 * time.Second):
				t.Fatal("normal service did not stop")
			}
		}
	}
	stop := start()
	stop()
	var config map[string]any
	if json.Unmarshal(raw, &config) != nil {
		t.Fatal("fixture")
	}
	release := config["publications"].([]any)[0].(map[string]any)["release"].(map[string]any)
	release["sha256"] = strings.Repeat("c", 64)
	release["release_notes"] = "PRIVATE_PUBLICATION_NOTE"
	changed, _ := json.Marshal(config)
	write(changed)
	failedCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	command := exec.CommandContext(failedCtx, binary)
	command.Env = env
	output, err := command.CombinedOutput()
	if err == nil || failedCtx.Err() != nil || !strings.Contains(string(output), errReleaseConfiguration.Error()) || strings.Contains(string(output), "PRIVATE_PUBLICATION_NOTE") || strings.Contains(string(output), "updates.example.invalid") {
		t.Fatal("rebound service did not fail safely before listening", err)
	}
	if response, err := client.Get(origin + "/readyz"); err == nil {
		_ = response.Body.Close()
		t.Fatal("failed service opened listener")
	}
	release["sha256"] = strings.Repeat("a", 64)
	release["rollout_basis_points"] = 0
	changed, _ = json.Marshal(config)
	write(changed)
	originalCatalog, err := releases.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	originalPolicy, _ := originalCatalog.Policy()
	replacement, err := releases.Parse(changed)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = (&store.Postgres{DB: db}).ReplaceAppReleasePolicy(ctx, store.OriginalDatabaseEpoch, "11111111-1111-4111-8111-111111111111", originalPolicy.SHA256, replacement); err != nil {
		t.Fatal(err)
	}
	stop = start()
	stop()
	var artifacts, audits int
	if err = db.QueryRowContext(ctx, `select (select count(*) from app_release_artifacts),
  (select count(*) from audit_log where action='app.release.register')`).Scan(&artifacts, &audits); err != nil || artifacts != 1 || audits != 1 {
		t.Fatal("restart duplicated or changed artifact evidence", artifacts, audits, err)
	}
}
