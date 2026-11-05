package observability

import (
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestScannerCollectorDistinguishesDisabledUnprobedAndCurrentSnapshots(t *testing.T) {
	metrics := New()
	scrape := func() string {
		t.Helper()
		response := httptest.NewRecorder()
		metrics.Handler().ServeHTTP(response, httptest.NewRequest("GET", "/metrics", nil))
		if response.Code != 200 {
			t.Fatal(response.Code)
		}
		return response.Body.String()
	}
	if initial := scrape(); !strings.Contains(initial, "tokenworks_mobile_file_scanner_enabled 0") || strings.Contains(initial, "tokenworks_mobile_file_scanner_ready ") {
		t.Fatal("disabled scanner masqueraded as configured")
	}
	var mu sync.Mutex
	state := ScannerSnapshot{}
	if err := metrics.BindFileScanner(func() ScannerSnapshot { mu.Lock(); defer mu.Unlock(); return state }); err != nil {
		t.Fatal(err)
	}
	if err := metrics.BindFileScanner(func() ScannerSnapshot { return ScannerSnapshot{} }); err == nil {
		t.Fatal("live source replaced")
	}
	if initial := scrape(); !strings.Contains(initial, "tokenworks_mobile_file_scanner_ready 0") || strings.Contains(initial, "tokenworks_mobile_file_scanner_probe_age_seconds ") {
		t.Fatal("unprobed scanner invented an age")
	}
	mu.Lock()
	state = ScannerSnapshot{Ready: true, Probed: true, Age: 1500 * time.Millisecond}
	mu.Unlock()
	if ready := scrape(); !strings.Contains(ready, "tokenworks_mobile_file_scanner_ready 1") || !strings.Contains(ready, "tokenworks_mobile_file_scanner_probe_age_seconds 1.5") {
		t.Fatal("memory snapshot lost health")
	}
	mu.Lock()
	state = ScannerSnapshot{Probed: true, Age: 3 * time.Minute}
	mu.Unlock()
	if expired := scrape(); !strings.Contains(expired, "tokenworks_mobile_file_scanner_ready 0") || !strings.Contains(expired, "tokenworks_mobile_file_scanner_probe_age_seconds 180") {
		t.Fatal("expired proof presented as healthy")
	}
}
func TestScannerProbeMetricsNeverUseCallerTextAsLabels(t *testing.T) {
	metrics := New()
	metrics.FileScannerProbe("PRIVATE_SCANNER_PATH", time.Millisecond)
	response := httptest.NewRecorder()
	metrics.Handler().ServeHTTP(response, httptest.NewRequest("GET", "/metrics", nil))
	if strings.Contains(response.Body.String(), "PRIVATE_SCANNER_PATH") || !strings.Contains(response.Body.String(), `tokenworks_mobile_file_scanner_probe_duration_seconds_count{result="other"} 1`) {
		t.Fatal("unbounded probe result label")
	}
}
