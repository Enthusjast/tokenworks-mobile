package httpapi

import (
	"net/http/httptest"
	"testing"

	"tokenworks-mobile/services/remote/internal/observability"
)

func TestMetricsAreNotExposedOnThePublicAPI(t *testing.T) {
	handler := New(&fakeRepository{}, nil, Config{Metrics: observability.New()})
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("GET", "/metrics", nil))
	if response.Code != 404 {
		t.Fatal("the public API exposed private operational metrics")
	}
}
