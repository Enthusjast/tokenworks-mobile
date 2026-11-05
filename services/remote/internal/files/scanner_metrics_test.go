package files

import (
	"context"
	"errors"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/observability"
)

type countedHealthScanner struct {
	Scanner
	begins atomic.Int32
}

func (scanner *countedHealthScanner) Begin(ctx context.Context) (Scan, error) {
	scanner.begins.Add(1)
	return scanner.Scanner.Begin(ctx)
}
func TestScannerMetricsScrapeReadsMemoryWithoutProbeOrFileWork(t *testing.T) {
	base := &serviceScanner{}
	scanner := &countedHealthScanner{Scanner: base}
	service, err := NewService(&serviceRepository{}, &exportDownloadStorage{}, scanner, ServiceConfig{Workers: 1, PolicySHA256: digest(nil), TaskTimeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	metrics := observability.New()
	if err = service.ConfigureMetrics(metrics); err != nil {
		t.Fatal(err)
	}
	scrape := func() string {
		t.Helper()
		response := httptest.NewRecorder()
		metrics.Handler().ServeHTTP(response, httptest.NewRequest("GET", "/metrics", nil))
		if response.Code != 200 {
			t.Fatal(response.Code)
		}
		return response.Body.String()
	}
	if initial := scrape(); !strings.Contains(initial, "tokenworks_mobile_file_scanner_enabled 1") || !strings.Contains(initial, "tokenworks_mobile_file_scanner_ready 0") {
		t.Fatal("configured service not visible before probe")
	}
	if scanner.begins.Load() != 0 {
		t.Fatal("scrape ran a self-test")
	}
	if err = service.ProbeScanner(t.Context()); err != nil {
		t.Fatal(err)
	}
	if ready := scrape(); !strings.Contains(ready, "tokenworks_mobile_file_scanner_ready 1") || !strings.Contains(ready, `tokenworks_mobile_file_scanner_probe_duration_seconds_count{result="ok"} 1`) || strings.Contains(ready, "tokenworks_mobile_file_scan_attempt_duration_seconds_count") {
		t.Fatal("probe was confused with file task")
	}
	base.alwaysClean = true
	if err = service.ProbeScanner(t.Context()); !errors.Is(err, ErrScanUnavailable) {
		t.Fatal(err)
	}
	before := scanner.begins.Load()
	for range 3 {
		if failed := scrape(); !strings.Contains(failed, "tokenworks_mobile_file_scanner_ready 0") || !strings.Contains(failed, `tokenworks_mobile_file_scanner_probe_duration_seconds_count{result="unavailable"} 1`) {
			t.Fatal("failed probe missing")
		}
	}
	if scanner.begins.Load() != before {
		t.Fatal("health collection performed scanner I/O")
	}
}
func TestScannerMetricsBindingIsOptionalAndCannotBeSharedByTwoServices(t *testing.T) {
	makeService := func() *Service {
		t.Helper()
		service, err := NewService(&serviceRepository{}, &exportDownloadStorage{}, &serviceScanner{}, ServiceConfig{Workers: 1, PolicySHA256: digest(nil), TaskTimeout: time.Second})
		if err != nil {
			t.Fatal(err)
		}
		return service
	}
	if err := makeService().ConfigureMetrics(nil); err != nil {
		t.Fatal("disabled metrics changed admission", err)
	}
	metrics := observability.New()
	if err := makeService().ConfigureMetrics(metrics); err != nil {
		t.Fatal(err)
	}
	if err := makeService().ConfigureMetrics(metrics); !errors.Is(err, ErrInvalid) {
		t.Fatal("two service health states mixed", err)
	}
}
