package store

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"dsh-mobile-ustc/services/remote/internal/requestlog"
)

func TestRESTEnvelopeRecoveryRejectionPrecedesBusiness(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	guard, err := db.NewRecoveryGuard(ctx, OriginalDatabaseEpoch)
	if err != nil {
		t.Fatal(err)
	}
	// Model a running deployment whose recorded epoch no longer matches DB.
	guard.state.Epoch = approvalTestUUID(t)
	for _, modern := range []bool{false, true} {
		r := httptest.NewRequest("POST", "/v1/devices", nil)
		if modern {
			r.Header.Set(requestlog.EnvelopeHeader, requestlog.RequestIDEnvelope)
		}
		w := httptest.NewRecorder()
		requestlog.Middleware(guard.Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Fatal("recovery rejection dispatched business handler") })), "public", nil).ServeHTTP(w, r)
		var body map[string]json.RawMessage
		if w.Code != 503 || json.Unmarshal(w.Body.Bytes(), &body) != nil {
			t.Fatal("recovery rejection lost standard envelope")
		}
		if modern {
			var id string
			if json.Unmarshal(body["request_id"], &id) != nil || id != w.Header().Get(requestlog.Header) || !requestlog.ValidID(id) || len(body) != 3 {
				t.Fatal("recovery envelope lost correlation")
			}
		} else if len(body) != 2 || w.Header().Get(requestlog.EnvelopeHeader) != "" {
			t.Fatal("legacy recovery envelope changed")
		}
	}
}
