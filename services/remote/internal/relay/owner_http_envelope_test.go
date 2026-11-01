package relay

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"dsh-mobile-ustc/services/remote/internal/requestlog"
	"dsh-mobile-ustc/services/remote/internal/store"
	"dsh-mobile-ustc/services/remote/internal/testsupport"
)

func TestRESTEnvelopeOwnerJSONConversionAndFailureBounds(t *testing.T) {
	const ownerID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
	for _, test := range []struct {
		name, raw, version, contentType string
		status                          int
		valid                           bool
	}{
		{"legacy_data", `{"ok":true,"data":{"request_id":"PRIVATE_BUSINESS_ID","value":9007199254740991}}`, "", "application/json", 200, true},
		{"modern_data", `{"ok":true,"data":null,"request_id":"` + ownerID + `"}`, requestlog.RequestIDEnvelope, "application/json", 201, true},
		{"nonce_challenge", `{"ok":false,"error":{"code":"auth.proof_invalid"}}`, "", "application/json; charset=utf-8", 401, true},
		{"duplicate", `{"ok":true,"ok":false,"data":null}`, "", "application/json", 200, false},
		{"nested_duplicate", `{"ok":true,"data":{"field":1,"field":2}}`, "", "application/json", 200, false},
		{"unknown_member", `{"ok":true,"data":null,"role":"admin"}`, "", "application/json", 200, false},
		{"unacknowledged_id", `{"ok":true,"data":null,"request_id":"` + ownerID + `"}`, "", "application/json", 200, false},
		{"unknown_version", `{"ok":true,"data":null,"request_id":"` + ownerID + `"}`, "request-id.v2", "application/json", 200, false},
		{"mismatched_id", `{"ok":true,"data":null,"request_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}`, requestlog.RequestIDEnvelope, "application/json", 200, false},
		{"missing_id", `{"ok":true,"data":null}`, requestlog.RequestIDEnvelope, "application/json", 200, false},
		{"wrong_success_status", `{"ok":true,"data":null}`, "", "application/json", 403, false},
		{"wrong_error_status", `{"ok":false,"error":{"code":"internal"}}`, "", "application/json", 200, false},
		{"null_ok", `{"ok":null,"error":{"code":"internal"}}`, "", "application/json", 503, false},
		{"missing_error_code", `{"ok":false,"error":null}`, "", "application/json", 503, false},
		{"wrong_media_type", `{"ok":true,"data":null}`, "", "text/html", 200, false},
		{"unknown_media_parameter", `{"ok":true,"data":null}`, "", "application/json; PRIVATE_SELECTOR=value", 200, false},
		{"over_limit", `{"ok":true,"data":"` + strings.Repeat("x", maxOwnerJSONBytes) + `"}`, "", "application/json", 200, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			r := httptest.NewRequest("GET", "/v1/files/88888888-8888-4888-8888-888888888888", nil)
			r.Header.Set(requestlog.EnvelopeHeader, requestlog.RequestIDEnvelope)
			w := httptest.NewRecorder()
			requestlog.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				reply := &http.Response{StatusCode: test.status, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(test.raw)), ContentLength: int64(len(test.raw))}
				reply.Header.Set("Content-Type", test.contentType)
				reply.Header.Set("Content-Length", strconv.Itoa(len(test.raw)))
				reply.Header.Set(requestlog.Header, ownerID)
				if test.version != "" {
					reply.Header.Set(requestlog.EnvelopeHeader, test.version)
				}
				reply.Header.Set("DPoP-Nonce", "PRIVATE_NONCE")
				reply.Header.Set("WWW-Authenticate", `DPoP error="use_dpop_nonce"`)
				if !New(nil).forwardJSONEnvelope(w, r, reply) {
					t.Fatal("JSON metadata took raw streaming path")
				}
			}), "public", nil).ServeHTTP(w, r)
			var body struct {
				OK        bool            `json:"ok"`
				RequestID string          `json:"request_id"`
				Data      json.RawMessage `json:"data"`
				Error     struct {
					Code string `json:"code"`
				} `json:"error"`
			}
			if json.Unmarshal(w.Body.Bytes(), &body) != nil || !requestlog.ValidID(body.RequestID) || body.RequestID != w.Header().Get(requestlog.Header) || body.RequestID == ownerID || w.Header().Get(requestlog.EnvelopeHeader) != requestlog.RequestIDEnvelope {
				t.Fatal("gateway response lost ingress identity")
			}
			if test.valid {
				if w.Code != test.status || w.Header().Get("DPoP-Nonce") != "PRIVATE_NONCE" || w.Header().Get("Content-Length") != "" {
					t.Fatal("valid owner response changed status or proof challenge")
				}
				if test.name == "legacy_data" && (!strings.Contains(string(body.Data), "PRIVATE_BUSINESS_ID") || !strings.Contains(string(body.Data), "9007199254740991")) {
					t.Fatal("gateway changed business identity or numeric precision")
				}
			} else if w.Code != 503 || body.Error.Code != "internal" || body.OK || w.Header().Get("DPoP-Nonce") != "" {
				t.Fatal("malformed owner envelope did not fail closed")
			}
		})
	}
}

func TestRESTEnvelopeAcrossModernAndLegacyMTLSOwners(t *testing.T) {
	for _, modern := range []bool{false, true} {
		t.Run(strconv.FormatBool(modern), func(t *testing.T) {
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			defer cancel()
			entryNode := store.RelayNode{NodeID: "11111111-1111-4111-8111-111111111111", Generation: "22222222-2222-4222-8222-222222222222", Origin: "https://127.0.0.1:1"}
			ownerNode := store.RelayNode{NodeID: "33333333-3333-4333-8333-333333333333", Generation: "44444444-4444-4444-8444-444444444444"}
			ca := testsupport.NewRelayCA(t)
			_, _, entryPair := ca.Issue(t, entryNode.NodeID)
			_, _, ownerPair := ca.Issue(t, ownerNode.NodeID)
			var owner *Server
			const fileBytes = `{"ok":true,"data":"PRIVATE_FILE_BYTES"}`
			business := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get(requestlog.EnvelopeHeader) != requestlog.RequestIDEnvelope {
					t.Error("ingress did not forward negotiated format")
				}
				if strings.HasSuffix(r.URL.Path, "/content") {
					w.Header().Set("Content-Type", "application/octet-stream")
					w.Header().Set("Content-Length", strconv.Itoa(len(fileBytes)))
					_, _ = io.WriteString(w, fileBytes)
					return
				}
				requestlog.Data(w, 200, map[string]string{"request_id": "PRIVATE_BUSINESS_ID"})
			})
			var privateHandler http.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { owner.PrivateRelayHandler(business).ServeHTTP(w, r) })
			if modern {
				privateHandler = requestlog.Middleware(privateHandler, "relay", nil)
			}
			private := httptest.NewUnstartedServer(privateHandler)
			private.TLS = &tls.Config{MinVersion: tls.VersionTLS13, ClientAuth: tls.RequireAndVerifyClientCert, ClientCAs: ca.Roots, Certificates: []tls.Certificate{ownerPair}, NextProtos: []string{"http/1.1"}}
			private.StartTLS()
			defer private.Close()
			ownerNode.Origin = private.URL
			route := store.RuntimeRoute{RelayNode: ownerNode, RuntimeID: "55555555-5555-4555-8555-555555555555", RuntimeEpoch: "66666666-6666-4666-8666-666666666666", ConnectionID: "77777777-7777-4777-8777-777777777777"}
			owner = New(nil)
			owner.hosts[route.RuntimeID] = &liveConnection{runtimeEpoch: route.RuntimeEpoch, connectionID: route.ConnectionID}
			if err := owner.ConfigureRouting(RoutingConfig{Directory: &correlationDirectory{identity: ownerNode, peer: entryNode, route: route}, ClientTLS: &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: ca.Roots, Certificates: []tls.Certificate{ownerPair}}}); err != nil {
				t.Fatal(err)
			}
			entry := New(nil)
			if err := entry.ConfigureRouting(RoutingConfig{Directory: &correlationDirectory{identity: entryNode, peer: ownerNode, route: route}, ClientTLS: &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: ca.Roots, Certificates: []tls.Certificate{entryPair}}}); err != nil {
				t.Fatal(err)
			}
			public := httptest.NewServer(requestlog.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if !entry.ForwardOwnerHTTP(w, r, route.RuntimeID) {
					t.Error("request was not forwarded")
				}
			}), "public", nil))
			defer public.Close()
			for _, path := range []string{"/v1/files/88888888-8888-4888-8888-888888888888", "/v1/host-files/88888888-8888-4888-8888-888888888888/content"} {
				r, _ := http.NewRequestWithContext(ctx, "GET", public.URL+path, nil)
				r.Header.Set(requestlog.EnvelopeHeader, requestlog.RequestIDEnvelope)
				reply, err := public.Client().Do(r)
				if err != nil {
					t.Fatal(err)
				}
				raw, err := io.ReadAll(reply.Body)
				_ = reply.Body.Close()
				if err != nil {
					t.Fatal(err)
				}
				if strings.HasSuffix(path, "/content") {
					if string(raw) != fileBytes || reply.Header.Get(requestlog.EnvelopeHeader) != "" || reply.Header.Get("Content-Length") != strconv.Itoa(len(fileBytes)) {
						t.Fatal("negotiation altered file bytes/headers")
					}
				} else {
					var body struct {
						RequestID string `json:"request_id"`
						Data      struct {
							RequestID string `json:"request_id"`
						} `json:"data"`
					}
					if json.Unmarshal(raw, &body) != nil || body.RequestID != reply.Header.Get(requestlog.Header) || !requestlog.ValidID(body.RequestID) || body.Data.RequestID != "PRIVATE_BUSINESS_ID" || reply.Header.Get(requestlog.EnvelopeHeader) != requestlog.RequestIDEnvelope {
						t.Fatal("mixed-version owner broke negotiated envelope")
					}
				}
			}
		})
	}
}

func TestRESTEnvelopeStalledOwnerJSONIsBounded(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	owner := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(503)
		_, _ = io.WriteString(w, `{"ok":false,"error":`)
		_ = http.NewResponseController(w).Flush()
		<-r.Context().Done()
	}))
	defer owner.Close()
	upstream, _ := http.NewRequestWithContext(ctx, "GET", owner.URL, nil)
	reply, err := owner.Client().Do(upstream)
	if err != nil {
		t.Fatal(err)
	}
	defer reply.Body.Close()
	r := httptest.NewRequest("GET", "/v1/files/88888888-8888-4888-8888-888888888888", nil)
	r.Header.Set(requestlog.EnvelopeHeader, requestlog.RequestIDEnvelope)
	w := httptest.NewRecorder()
	started := time.Now()
	requestlog.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !New(nil).forwardJSONEnvelope(w, r, reply) {
			t.Fatal("stalled metadata took streaming path")
		}
	}), "public", nil).ServeHTTP(w, r)
	if elapsed := time.Since(started); elapsed >= 4*time.Second || w.Code != 503 || !strings.Contains(w.Body.String(), `"code":"internal"`) {
		t.Fatal("stalled JSON escaped its body deadline", elapsed)
	}
}
