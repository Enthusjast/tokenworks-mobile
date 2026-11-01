package httpapi

import (
	"encoding/json"
	"errors"
	"net/http/httptest"
	"testing"

	"dsh-mobile-ustc/services/remote/internal/requestlog"
)

func TestCorrelationKeepsExistingAPIAndAuthenticationErrors(t *testing.T) {
	for _, test := range []struct {
		path       string
		readyError error
		status     int
		body       string
	}{
		{"/healthz", nil, 204, ""},
		{"/readyz", nil, 204, ""},
		{"/readyz", errors.New("PRIVATE_DATABASE_FAILURE"), 503, `{"ok":false,"error":{"code":"internal"}}`},
		{"/v1/me", nil, 401, `{"ok":false,"error":{"code":"auth.expired"}}`},
		{"/PRIVATE_MISSING_PATH", nil, 404, ""},
	} {
		t.Run(test.path, func(t *testing.T) {
			handler := requestlog.Middleware(New(&fakeRepository{readyError: test.readyError}, nil), "public", nil)
			request := httptest.NewRequest("GET", test.path, nil)
			request.Header.Set(requestlog.Header, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			id := response.Header().Get(requestlog.Header)
			if id == "" || id == request.Header.Get(requestlog.Header) || response.Code != test.status {
				t.Fatal("API response lost server correlation or changed status")
			}
			if test.body != "" {
				var actual, expected any
				if json.Unmarshal(response.Body.Bytes(), &actual) != nil || json.Unmarshal([]byte(test.body), &expected) != nil {
					t.Fatal("API error is not JSON")
				}
				raw, _ := json.Marshal(actual)
				want, _ := json.Marshal(expected)
				if string(raw) != string(want) {
					t.Fatal("correlation broke existing strict error envelopes")
				}
			}
		})
	}
}
