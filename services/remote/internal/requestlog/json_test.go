package requestlog

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type envelopeOuterWriter struct{ http.ResponseWriter }

func (w envelopeOuterWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func TestRESTEnvelopeNegotiationAndBusinessIdentity(t *testing.T) {
	for _, modern := range []bool{false, true} {
		for _, success := range []bool{false, true} {
			r := httptest.NewRequest("GET", "/v1/fixture", nil)
			if modern {
				r.Header.Set(EnvelopeHeader, RequestIDEnvelope)
			}
			r.Header.Set(Header, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
			w := httptest.NewRecorder()
			Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if JSONEnabled(r.Context()) != modern {
					t.Fatal("negotiation not present in request context")
				}
				wrapped := envelopeOuterWriter{w}
				if success {
					Data(wrapped, 201, map[string]string{"request_id": "PRIVATE_BUSINESS_ID"})
				} else {
					Error(wrapped, 403, "access.denied")
				}
			}), "public", nil).ServeHTTP(w, r)
			var envelope map[string]json.RawMessage
			if json.Unmarshal(w.Body.Bytes(), &envelope) != nil {
				t.Fatal("non-JSON response")
			}
			if modern {
				var id string
				if len(envelope) != 3 || json.Unmarshal(envelope["request_id"], &id) != nil || !ValidID(id) || id != w.Header().Get(Header) || id == r.Header.Get(Header) || w.Header().Get(EnvelopeHeader) != RequestIDEnvelope {
					t.Fatal("negotiated envelope lost server identity")
				}
			} else if len(envelope) != 2 || envelope["request_id"] != nil || w.Header().Get(EnvelopeHeader) != "" {
				t.Fatal("legacy envelope changed")
			}
			if success && !strings.Contains(string(envelope["data"]), "PRIVATE_BUSINESS_ID") {
				t.Fatal("business request identity changed")
			}
			if w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("Vary") != EnvelopeHeader {
				t.Fatal("response representation is not cache-isolated")
			}
		}
	}
}

func TestRESTEnvelopeUnsupportedNegotiationDoesNotDispatch(t *testing.T) {
	for _, values := range [][]string{{""}, {"request-id.v2"}, {RequestIDEnvelope, RequestIDEnvelope}, {RequestIDEnvelope + ", " + RequestIDEnvelope}} {
		r := httptest.NewRequest("POST", "/v1/devices", strings.NewReader("PRIVATE_BODY"))
		r.Header[http.CanonicalHeaderKey(EnvelopeHeader)] = values
		w := httptest.NewRecorder()
		Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Fatal("unsupported negotiation reached business handler") }), "public", nil).ServeHTTP(w, r)
		want := 406
		if len(values) > 1 || values[0] == "" {
			want = 400
		}
		if w.Code != want || !ValidID(w.Header().Get(Header)) || w.Header().Get(EnvelopeHeader) != "" || strings.Contains(w.Body.String(), "PRIVATE_BODY") {
			t.Fatal("unsafe negotiation error", w.Code)
		}
	}
}

func TestRESTEnvelopeBinaryAndNoContentAreNotWrapped(t *testing.T) {
	for _, status := range []int{200, 204} {
		r := httptest.NewRequest("GET", "/fixture", nil)
		r.Header.Set(EnvelopeHeader, RequestIDEnvelope)
		w := httptest.NewRecorder()
		Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/octet-stream")
			w.WriteHeader(status)
			if status == 200 {
				_, _ = w.Write([]byte("{\"ok\":true,\"data\":\"PRIVATE_FILE\"}"))
			}
		}), "public", nil).ServeHTTP(w, r)
		if w.Header().Get(EnvelopeHeader) != "" || !ValidID(w.Header().Get(Header)) || (status == 204 && w.Body.Len() != 0) || (status == 200 && !strings.Contains(w.Body.String(), "PRIVATE_FILE")) {
			t.Fatal("non-envelope response was wrapped")
		}
	}
}
