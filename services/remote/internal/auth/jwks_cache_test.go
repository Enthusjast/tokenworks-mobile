package auth

import (
	"encoding/json"
	"net/http"
	"os"
	"testing"
	"time"
)

func TestJWKSFreshnessSharedVectors(t *testing.T) {
	raw, err := os.ReadFile("../../../../contracts/security/jwks-cache.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Base  string
		Cases []struct {
			Name                                       string
			Headers                                    map[string][]string
			StartedMs, ReceivedMs, ReadyMs, ExpectedMs int64
		}
	}
	if err = json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	base, err := time.Parse(time.RFC3339Nano, fixture.Base)
	if err != nil {
		t.Fatal(err)
	}
	for _, row := range fixture.Cases {
		t.Run(row.Name, func(t *testing.T) {
			at := func(ms int64) time.Time { return base.Add(time.Duration(ms) * time.Millisecond) }
			got := jwksFreshness(http.Header(row.Headers), at(row.StartedMs), at(row.ReceivedMs), at(row.ReadyMs))
			if got != time.Duration(row.ExpectedMs)*time.Millisecond {
				t.Fatalf("freshness=%v, want %dms", got, row.ExpectedMs)
			}
		})
	}
}
