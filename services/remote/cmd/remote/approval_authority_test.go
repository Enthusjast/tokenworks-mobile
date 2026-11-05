package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/approval"
	"tokenworks-mobile/services/remote/internal/canonical"
	"tokenworks-mobile/services/remote/internal/store"
)

func authorityCommandEnvironment(t *testing.T) {
	t.Helper()
	t.Setenv("REMOTE_DATABASE_URL", "PRIVATE_INVALID_DATABASE")
	t.Setenv("REMOTE_ISSUER", "https://id.example.edu")
	t.Setenv("REMOTE_CLIENT_IDS", "android")
}
func writeAuthorityDeployment(t *testing.T, path string, config approvalDeployment) {
	t.Helper()
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
}

type authorityFailWriter struct{}

func (authorityFailWriter) Write([]byte) (int, error) { return 0, io.ErrClosedPipe }

func TestApprovalAuthorityDescriptionIsOfflinePublicAndDeterministic(t *testing.T) {
	authorityCommandEnvironment(t)
	path, config := deploymentFixture(t)
	writeAuthorityDeployment(t, path, config)
	var first, second bytes.Buffer
	for _, output := range []*bytes.Buffer{&first, &second} {
		if err := runApprovalAuthorityCommand([]string{"describe", "--policy-file", path}, output); err != nil {
			t.Fatal(err)
		}
	}
	if !bytes.Equal(first.Bytes(), second.Bytes()) {
		t.Fatal("unchanged public description is not deterministic")
	}
	for _, private := range []string{"PRIVATE", path, "key.pem", "private_key_file", "mappings", "client_id"} {
		if bytes.Contains(first.Bytes(), []byte(private)) {
			t.Fatal("public description disclosed private deployment inputs")
		}
	}
	var document approvalAuthorityDescription
	if json.Unmarshal(first.Bytes(), &document) != nil || document.Format != approvalAuthorityFormat || document.Policy.Version != config.Policy.Version || document.Authority.KeyID != config.Signing.KeyID {
		t.Fatal("public description did not reflect validated deployment")
	}
	claimed := document.SHA256
	document.SHA256 = ""
	raw, _ := json.Marshal(document)
	body, err := canonical.Object(raw)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(body)
	if claimed != hex.EncodeToString(digest[:]) {
		t.Fatal("public description digest is not JCS SHA-256")
	}
	// Exercise production argument dispatch as well as the read-only function.
	_, source, _, _ := runtime.Caller(0)
	root := filepath.Clean(filepath.Join(filepath.Dir(source), "../../../.."))
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	binary := filepath.Join(t.TempDir(), "remote")
	build := exec.CommandContext(ctx, filepath.Join(runtime.GOROOT(), "bin/go"), "build", "-o", binary, "./cmd/remote")
	build.Dir = filepath.Join(root, "services/remote")
	if output, err := build.CombinedOutput(); err != nil {
		t.Fatalf("production CLI build failed: %v: %s", err, output)
	}
	command := exec.CommandContext(ctx, binary, "approval-authority", "describe", "--policy-file", path)
	if output, err := command.Output(); err != nil || !bytes.Equal(output, first.Bytes()) {
		t.Fatal("production CLI dispatch did not produce the same public-only description", err)
	}
	for _, args := range [][]string{nil, {"rotate"}, {"describe", "extra"}, {"describe", "--unknown"}} {
		var output bytes.Buffer
		if err := runApprovalAuthorityCommand(args, &output); err == nil || output.Len() != 0 {
			t.Fatal("invalid CLI arguments produced a document")
		}
	}
	if err := runApprovalAuthorityCommand([]string{"describe", "--policy-file", path}, authorityFailWriter{}); err == nil {
		t.Fatal("output failure was ignored")
	}
}

func TestApprovalAuthorityDescriptionStagesFutureKeysWithoutEnablingRuntime(t *testing.T) {
	authorityCommandEnvironment(t)
	path, config := deploymentFixture(t)
	for _, future := range []bool{true, false} {
		if future {
			config.Signing.ValidFrom = time.Now().Add(time.Hour).UTC().Truncate(time.Millisecond).Format("2006-01-02T15:04:05.000Z")
			config.Signing.ValidUntil = time.Now().Add(2 * time.Hour).UTC().Truncate(time.Millisecond).Format("2006-01-02T15:04:05.000Z")
		} else {
			config.Signing.ValidFrom = time.Now().Add(-2 * time.Hour).UTC().Truncate(time.Millisecond).Format("2006-01-02T15:04:05.000Z")
			config.Signing.ValidUntil = time.Now().Add(-time.Hour).UTC().Truncate(time.Millisecond).Format("2006-01-02T15:04:05.000Z")
		}
		writeAuthorityDeployment(t, path, config)
		var output bytes.Buffer
		if err := runApprovalAuthorityCommand([]string{"describe", "--policy-file", path}, &output); err != nil {
			t.Fatal("scheduled/historical public key could not be inspected", err)
		}
		if _, err := loadApprovalConfig(path, &store.Postgres{DB: &sql.DB{}}, "https://id.example.edu", []string{"android"}); !errors.Is(err, errApprovalConfiguration) {
			t.Fatal("public inspection enabled a noncurrent runtime signer", err)
		}
	}
	t.Setenv("REMOTE_ISSUER", "https://other.example")
	var output bytes.Buffer
	if err := runApprovalAuthorityCommand([]string{"describe", "--policy-file", path}, &output); err == nil || output.Len() != 0 {
		t.Fatal("unverified identity mapping produced public trust metadata")
	}
}

func TestApprovalAuthorityGoDescriptionAndSignersExerciseHostRotation(t *testing.T) {
	authorityCommandEnvironment(t)
	_, source, _, _ := runtime.Caller(0)
	root := filepath.Clean(filepath.Join(filepath.Dir(source), "../../../.."))
	raw, err := os.ReadFile(filepath.Join(root, "contracts/security/approval-leases.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Now        string                      `json:"now"`
		Context    json.RawMessage             `json:"context"`
		Decision   string                      `json:"decision_jws"`
		Connection string                      `json:"connection_id"`
		Lease      approval.AuthorizationLease `json:"lease"`
	}
	if json.Unmarshal(raw, &fixture) != nil {
		t.Fatal("lease fixture unavailable")
	}
	now, err := time.Parse(time.RFC3339Nano, fixture.Now)
	if err != nil {
		t.Fatal(err)
	}
	documents := []json.RawMessage{}
	leases := []string{}
	pins := []string{}
	for _, label := range []string{"rotation-old", "rotation-new"} {
		path, config := deploymentFixture(t)
		config.Policy.Version = fixture.Lease.PolicyVersion
		config.Policy.Profile = fixture.Lease.AssuranceProfile
		var contextPolicy struct {
			Required struct {
				MaxAge int64 `json:"max_auth_age_seconds"`
			} `json:"required_assurance"`
		}
		if json.Unmarshal(fixture.Context, &contextPolicy) != nil {
			t.Fatal("context policy fixture unavailable")
		}
		config.Policy.MaxAge = &contextPolicy.Required.MaxAge
		config.Mappings[0].Profile = config.Policy.Profile
		config.Signing.KeyID = "tokenworks-mobile-approval-authority/" + label
		config.Signing.ValidFrom = now.Add(-time.Hour).Format("2006-01-02T15:04:05.000Z")
		config.Signing.ValidUntil = now.Add(time.Hour).Format("2006-01-02T15:04:05.000Z")
		writeAuthorityDeployment(t, path, config)
		var output bytes.Buffer
		if err := runApprovalAuthorityCommand([]string{"describe", "--policy-file", path}, &output); err != nil {
			t.Fatal(err)
		}
		var document approvalAuthorityDescription
		if json.Unmarshal(output.Bytes(), &document) != nil {
			t.Fatal("invalid public output")
		}
		signer, err := approvalDeploymentSigner(path, config, false)
		if err != nil {
			t.Fatal(err)
		}
		lease, err := signer.Sign(fixture.Lease, now)
		if err != nil {
			t.Fatal("fixture signer failed", err)
		}
		documents = append(documents, bytes.Clone(output.Bytes()))
		pins = append(pins, document.SHA256)
		leases = append(leases, lease)
	}
	input, _ := json.Marshal(map[string]any{"now": fixture.Now, "context": fixture.Context, "decision": fixture.Decision, "connection": fixture.Connection, "documents": documents, "pins": pins, "leases": leases})
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, filepath.Join(root, "node_modules/.bin/tsx"), filepath.Join(root, "tools/integration/approval-authority-rotation.ts"))
	command.Dir = root
	command.Stdin = bytes.NewReader(input)
	output, err := command.Output()
	if err != nil {
		t.Fatal("Host rotation fixture failed; inspect owned test output without logging signed inputs", err)
	}
	var result struct {
		Overlap           bool `json:"overlap"`
		OldRejected       bool `json:"old_rejected"`
		Closed            bool `json:"closed"`
		TamperingRejected bool `json:"tampering_rejected"`
	}
	if json.Unmarshal(output, &result) != nil || !result.Overlap || !result.OldRejected || !result.Closed || !result.TamperingRejected {
		t.Fatal("rotation fixture did not prove every boundary")
	}
	if strings.Contains(string(output), "PRIVATE") {
		t.Fatal("rotation fixture printed private values")
	}
}
