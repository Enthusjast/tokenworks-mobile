package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"runtime"
	"strings"
	"testing"
)

type failingVersionWriter struct{}

func (failingVersionWriter) Write([]byte) (int, error) { return 0, errors.New("write unavailable") }

func TestVersionIsOfflineAndReportsOnlyEmbeddedBuildMetadata(t *testing.T) {
	originalRevision, originalSource := buildRevision, buildSourceSHA256
	t.Cleanup(func() { buildRevision, buildSourceSHA256 = originalRevision, originalSource })
	t.Setenv("REMOTE_DATABASE_URL", "PRIVATE_INVALID_DATABASE")
	t.Setenv("REMOTE_ISSUER", "PRIVATE_INVALID_ISSUER")
	for _, release := range []bool{false, true} {
		buildRevision, buildSourceSHA256 = "", ""
		if release {
			buildRevision, buildSourceSHA256 = strings.Repeat("a", 40), strings.Repeat("b", 64)
		}
		var output bytes.Buffer
		if err := runVersionCommand(nil, &output); err != nil {
			t.Fatal(err)
		}
		var value serviceVersion
		if err := json.Unmarshal(output.Bytes(), &value); err != nil {
			t.Fatal(err)
		}
		if value.Format != "tokenworks-mobile-service-version/v1" || value.Go != runtime.Version() ||
			value.Platform != runtime.GOOS+"/"+runtime.GOARCH || value.Revision != buildRevision || value.SourceSHA256 != buildSourceSHA256 ||
			bytes.Contains(output.Bytes(), []byte("PRIVATE")) {
			t.Fatal("version output did not match embedded metadata")
		}
	}
	if err := runVersionCommand([]string{"unexpected"}, &bytes.Buffer{}); err == nil {
		t.Fatal("version accepted arguments")
	}
	if err := runVersionCommand(nil, failingVersionWriter{}); err == nil {
		t.Fatal("version hid an output failure")
	}
	for _, pair := range [][2]string{{"", strings.Repeat("b", 64)}, {strings.Repeat("a", 40), ""}, {"PRIVATE", strings.Repeat("b", 64)}} {
		buildRevision, buildSourceSHA256 = pair[0], pair[1]
		var output bytes.Buffer
		if err := runVersionCommand(nil, &output); err == nil || output.Len() != 0 {
			t.Fatal("invalid embedded metadata produced version output")
		}
	}
}
