package main

import (
	"bytes"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"dsh-mobile-ustc/services/remote/internal/releases"
	"dsh-mobile-ustc/services/remote/internal/store"
	"dsh-mobile-ustc/services/remote/internal/testsupport"
)

func TestAppReleasePolicyCLIValidatesBeforeDatabaseAndDescribesOffline(t *testing.T) {
	t.Setenv("REMOTE_DATABASE_URL", "PRIVATE_INVALID_DATABASE")
	t.Setenv("REMOTE_DATABASE_EPOCH", store.OriginalDatabaseEpoch)
	for _, args := range [][]string{nil, {"unknown"}, {"status"}, {"status", "--package-name", "bad"}, {"replace"}, {"confirm"}, {"describe", "--catalog-file", ""}, {"status", "--package-name", "cn.edu.valid", "extra"}} {
		var output bytes.Buffer
		if err := runAppReleaseCommand(args, &output); err == nil || strings.Contains(err.Error(), "PRIVATE_INVALID_DATABASE") || output.Len() != 0 {
			t.Fatal("invalid command reached database or returned metadata", err)
		}
	}
	raw, err := os.ReadFile("../../../../deploy/android/releases.example.json")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "catalog.json")
	if err = os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err = runAppReleaseCommand([]string{"describe", "--catalog-file", path}, &output); err != nil {
		t.Fatal(err)
	}
	var description struct {
		Format string `json:"format"`
		releases.PolicyRef
	}
	if json.Unmarshal(output.Bytes(), &description) != nil || description.Format != "dsh-app-release-policy/v1" || !description.Valid() || bytes.Contains(output.Bytes(), []byte("download_url")) || bytes.Contains(output.Bytes(), []byte("organization_ids")) {
		t.Fatal("offline description disclosed policy content")
	}
}
func TestAppReleasePolicyCLIReconcilesLostOutputWithProductionBinary(t *testing.T) {
	binary := os.Getenv("REMOTE_TEST_SERVICE_BIN")
	if binary == "" {
		t.Skip("explicit disposable production binary required")
	}
	ctx, db := testsupport.PostgresDatabase(t)
	dsn := archiveTestDSN(t, ctx, db)
	t.Setenv("REMOTE_DATABASE_URL", dsn)
	t.Setenv("REMOTE_DATABASE_EPOCH", store.OriginalDatabaseEpoch)
	raw, err := os.ReadFile("../../../../deploy/android/releases.example.json")
	if err != nil {
		t.Fatal(err)
	}
	original, err := releases.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	ref, _ := original.Policy()
	if err = (&store.Postgres{DB: db}).RegisterAppReleaseCatalog(ctx, store.OriginalDatabaseEpoch, original); err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	_ = json.Unmarshal(raw, &config)
	config["publications"] = []any{}
	withdrawal, _ := json.Marshal(config)
	path := filepath.Join(t.TempDir(), "withdrawn.json")
	if err = os.WriteFile(path, withdrawal, 0600); err != nil {
		t.Fatal(err)
	}
	args := []string{"replace", "--catalog-file", path, "--operation-id", "11111111-1111-4111-8111-111111111111", "--expected-sha256", ref.SHA256}
	if err = runAppReleaseCommand(args, archiveFailedOutput{}); err == nil || !strings.Contains(err.Error(), "same operation UUID") {
		t.Fatal("lost output not reconcilable", err)
	}
	call := func(args ...string) []byte {
		t.Helper()
		command := exec.CommandContext(ctx, binary, append([]string{"app-releases"}, args...)...)
		command.Env = []string{"PATH=" + os.Getenv("PATH"), "REMOTE_DATABASE_URL=" + dsn, "REMOTE_DATABASE_EPOCH=" + store.OriginalDatabaseEpoch}
		output, err := command.Output()
		if err != nil {
			t.Fatal("production release policy command failed", err)
		}
		return output
	}
	var receipt store.AppReleasePolicyChange
	output := call(args...)
	if json.Unmarshal(output, &receipt) != nil || receipt.Revision != 2 || receipt.PreviousSHA256 != ref.SHA256 {
		t.Fatal("production retry lost receipt")
	}
	var status store.AppReleasePolicyStatus
	if json.Unmarshal(call("status", "--package-name", ref.PackageName), &status) != nil || status.Revision != 2 || status.PolicySHA256 != receipt.PolicySHA256 {
		t.Fatal("status lost current policy")
	}
	if bytes.Contains(output, []byte(dsn)) || bytes.Contains(output, []byte("updates.example.invalid")) {
		t.Fatal("CLI exposed source data")
	}
}
