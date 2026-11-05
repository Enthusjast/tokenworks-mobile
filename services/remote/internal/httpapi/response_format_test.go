package httpapi

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"

	"tokenworks-mobile/services/remote/internal/requestlog"
	"tokenworks-mobile/services/remote/internal/store"
)

func TestRESTEnvelopeAPIErrorsAndLegacyRouteSemantics(t *testing.T) {
	for _, modern := range []bool{false, true} {
		for _, test := range []struct {
			method, path string
			status       int
			code         string
		}{
			{"GET", "/v1/me", 401, "auth.expired"},
			{"GET", "/v1/realtime", 401, "auth.proof_invalid"},
			{"GET", "/readyz", 503, "internal"},
			{"GET", "/PRIVATE_MISSING_PATH", 404, "route.not_found"},
			{"PATCH", "/healthz", 405, "route.method_not_allowed"},
		} {
			w := httptest.NewRecorder()
			r := httptest.NewRequest(test.method, test.path, nil)
			if modern {
				r.Header.Set(requestlog.EnvelopeHeader, requestlog.RequestIDEnvelope)
			}
			handler := requestlog.Middleware(New(&fakeRepository{readyError: store.ErrDatabaseUnavailable}, nil), "public", nil)
			handler.ServeHTTP(w, r)
			if w.Code != test.status {
				t.Fatal("route or authentication status changed", test.path, w.Code)
			}
			if test.status == 405 && w.Header().Get("Allow") != "GET, HEAD" {
				t.Fatal("method Allow semantics changed")
			}
			if modern || test.status < 404 || test.status == 503 {
				var body struct {
					OK        bool   `json:"ok"`
					RequestID string `json:"request_id"`
					Error     struct {
						Code string `json:"code"`
					} `json:"error"`
				}
				if json.Unmarshal(w.Body.Bytes(), &body) != nil || body.OK || body.Error.Code != test.code {
					t.Fatal("error code/envelope changed")
				}
				if modern && (body.RequestID != w.Header().Get(requestlog.Header) || !requestlog.ValidID(body.RequestID)) {
					t.Fatal("API error lacks a matching correlation ID")
				}
				if !modern && (body.RequestID != "" || w.Header().Get(requestlog.EnvelopeHeader) != "") {
					t.Fatal("legacy API error gained metadata")
				}
			} else if w.Header().Get(requestlog.EnvelopeHeader) != "" || !strings.Contains(w.Header().Get("Content-Type"), "text/plain") {
				t.Fatal("legacy router error changed")
			}
		}
	}
}
