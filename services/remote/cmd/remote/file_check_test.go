package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/files"
)

func fileCheckScanner(t *testing.T, mode string) (string, *atomic.Int32) {
	t.Helper()
	// Keep the Unix socket short even when the checkout or test name is long.
	directory, err := os.MkdirTemp("", "tokenworks-mobile-check-av-")
	if err != nil {
		t.Fatal(err)
	}
	address := filepath.Join(directory, "scan.sock")
	listener, err := net.Listen("unix", address)
	if err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		for {
			connection, err := listener.Accept()
			if err != nil {
				return
			}
			calls.Add(1)
			func() {
				defer connection.Close()
				_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
				command := make([]byte, len("zINSTREAM\x00"))
				if _, err := io.ReadFull(connection, command); err != nil || string(command) != "zINSTREAM\x00" {
					return
				}
				var body bytes.Buffer
				for {
					var size [4]byte
					if _, err := io.ReadFull(connection, size[:]); err != nil {
						return
					}
					length := binary.BigEndian.Uint32(size[:])
					if length == 0 {
						break
					}
					if length > 1024 || body.Len()+int(length) > 1024 {
						return
					}
					if _, err := io.CopyN(&body, connection, int64(length)); err != nil {
						return
					}
				}
				if mode == "silent" {
					_, _ = io.Copy(io.Discard, connection)
					return
				}
				reply := "stream: OK\x00"
				if mode == "always-reject" || mode == "normal" && bytes.Equal(body.Bytes(), files.ScannerTestPattern()) {
					reply = "stream: Fixture-Signature FOUND\x00"
				} else if mode == "malformed" {
					reply = "PRIVATE_SCANNER_DETAIL\x00"
				}
				_, _ = io.WriteString(connection, reply)
			}()
		}
	}()
	t.Cleanup(func() { _ = listener.Close(); <-stopped; _ = os.RemoveAll(directory) })
	return address, &calls
}

func assertFileCheckReport(t *testing.T, raw []byte, policy string) {
	t.Helper()
	var report map[string]any
	if json.Unmarshal(raw, &report) != nil {
		t.Fatal("check did not produce a single JSON result")
	}
	expected := map[string]any{"format": "tokenworks-mobile-remote-file-check/v1", "configuration_valid": true,
		"scanner_self_test": "passed", "policy_sha256": policy, "exports_enabled": false, "storage_check": "configuration_only"}
	if len(report) != len(expected) {
		t.Fatal("check published unexpected fields")
	}
	for key, value := range expected {
		if report[key] != value {
			t.Fatalf("check field %s did not describe the tested configuration", key)
		}
	}
}

func TestFileCheckUsesNativeScannerWithoutDatabaseOrObjectAccess(t *testing.T) {
	path, config := fileDeploymentFixture(t)
	config.Scanner.Address, _ = fileCheckScanner(t, "normal")
	var connections atomic.Int32
	objects := httptest.NewUnstartedServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	objects.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateNew {
			connections.Add(1)
		}
	}
	objects.StartTLS()
	defer objects.Close()
	config.Storage.Endpoint = objects.URL
	writeFileDeployment(t, path, config)
	t.Setenv("REMOTE_DATABASE_URL", "PRIVATE_DATABASE_MUST_NOT_BE_OPENED")
	t.Setenv("REMOTE_FILES_CONFIG_FILE", "/must-not-read-an-implicit-config")
	var output bytes.Buffer
	if err := runFileCommand([]string{"check", "--config", path}, &output); err != nil {
		t.Fatal(err)
	}
	policy := sha256.Sum256([]byte(filePolicyFixture))
	assertFileCheckReport(t, output.Bytes(), hex.EncodeToString(policy[:]))
	if connections.Load() != 0 {
		t.Fatal("dependency check contacted object storage")
	}
	for _, private := range []string{path, config.Storage.Endpoint, config.Scanner.Address, "PRIVATE_STORAGE_SECRET", "PRIVATE_DATABASE"} {
		if strings.Contains(output.String(), private) {
			t.Fatal("dependency check published private configuration")
		}
	}
}

func TestFileCheckRejectsBadOrUnresponsiveScannerWithoutSuccessOutput(t *testing.T) {
	for _, mode := range []string{"always-clean", "always-reject", "malformed", "silent"} {
		t.Run(mode, func(t *testing.T) {
			path, config := fileDeploymentFixture(t)
			var calls *atomic.Int32
			config.Scanner.Address, calls = fileCheckScanner(t, mode)
			writeFileDeployment(t, path, config)
			var output bytes.Buffer
			started := time.Now()
			err := runFileCommand([]string{"check", "--config", path, "--timeout", "1s"}, &output)
			if !errors.Is(err, errFileCheckScanner) || output.Len() != 0 || calls.Load() == 0 {
				t.Fatal("scanner failure became a successful check", err)
			}
			if time.Since(started) > 2500*time.Millisecond || strings.Contains(err.Error(), "PRIVATE") {
				t.Fatal("scanner check exceeded its bound or exposed peer details")
			}
		})
	}
}

func TestFileCheckRejectsIncompleteConfigurationBeforeScannerAccess(t *testing.T) {
	for _, scenario := range []string{"public-config", "workers", "quota", "retention", "export-policy"} {
		t.Run(scenario, func(t *testing.T) {
			path, config := fileDeploymentFixture(t)
			var calls *atomic.Int32
			config.Scanner.Address, calls = fileCheckScanner(t, "normal")
			switch scenario {
			case "workers":
				config.Workers = 9
			case "quota":
				config.Limits.UserBytes = 0
			case "retention":
				config.Limits.RetainSeconds = 60
			case "export-policy":
				config.Exports.Enabled = true
			}
			writeFileDeployment(t, path, config)
			if scenario == "public-config" {
				if err := os.Chmod(path, 0644); err != nil {
					t.Fatal(err)
				}
			}
			var output bytes.Buffer
			if err := runFileCommand([]string{"check", "--config", path}, &output); !errors.Is(err, errFileConfiguration) {
				t.Fatal("check ignored runtime configuration validation", err)
			}
			if output.Len() != 0 || calls.Load() != 0 {
				t.Fatal("invalid configuration reached the scanner or produced a result")
			}
		})
	}
}

func TestFileCheckRequiresExplicitBoundedArguments(t *testing.T) {
	for _, arguments := range [][]string{
		nil, {"status"}, {"check"}, {"check", "--config", "relative.json"},
		{"check", "--config", "/fixture", "--timeout", "0s"}, {"check", "--config", "/fixture", "--timeout", "31s"},
		{"check", "--config", "/fixture", "--timeout", "invalid"}, {"check", "--config", "/fixture", "extra"},
		{"check", "--config", "/fixture", "--secret", "PRIVATE_ARGUMENT"},
	} {
		var output bytes.Buffer
		if err := runFileCommand(arguments, &output); !errors.Is(err, errFileCheckArguments) || output.Len() != 0 {
			t.Fatal("invalid check arguments were accepted", err)
		}
	}
}

func TestFileCheckBuiltServiceCommandDoesNotStartTheServer(t *testing.T) {
	binary := os.Getenv("REMOTE_TEST_SERVICE_BIN")
	if binary == "" {
		t.Skip("explicitly built service binary required")
	}
	path, config := fileDeploymentFixture(t)
	var calls *atomic.Int32
	config.Scanner.Address, calls = fileCheckScanner(t, "normal")
	writeFileDeployment(t, path, config)
	command := exec.CommandContext(t.Context(), binary, "files", "check", "--config", path, "--timeout", "2s")
	command.Env = []string{"REMOTE_DATABASE_URL=PRIVATE_MUST_NOT_CONNECT", "REMOTE_ISSUER=PRIVATE_MUST_NOT_VERIFY", "REMOTE_LISTEN_ADDR=invalid"}
	raw, err := command.CombinedOutput()
	if err != nil {
		t.Fatal("built file check command failed without database/server configuration")
	}
	policy := sha256.Sum256([]byte(filePolicyFixture))
	assertFileCheckReport(t, raw, hex.EncodeToString(policy[:]))
	if calls.Load() != 2 {
		t.Fatal("built command did not perform both independent native self-test streams")
	}
}
