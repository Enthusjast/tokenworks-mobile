package relay

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/requestlog"
	"tokenworks-mobile/services/remote/internal/store"
	"tokenworks-mobile/services/remote/internal/testsupport"
)

type correlationDirectory struct {
	identity store.RelayNode
	peer     store.RelayNode
	route    store.RuntimeRoute
}

func (d *correlationDirectory) RelayIdentity() store.RelayNode { return d.identity }
func (d *correlationDirectory) LookupRuntimeRoute(context.Context, string) (store.RuntimeRoute, error) {
	return d.route, nil
}
func (d *correlationDirectory) ValidateRelayNode(_ context.Context, node, generation string) error {
	if node != d.peer.NodeID || generation != d.peer.Generation {
		return errors.New("fixture: stale node")
	}
	return nil
}

func TestCorrelationAcrossAuthenticatedOwnerHTTPAndPrivateAdmission(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	const spoof = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	entryNode := store.RelayNode{NodeID: "11111111-1111-4111-8111-111111111111", Generation: "22222222-2222-4222-8222-222222222222", Origin: "https://127.0.0.1:1"}
	ownerNode := store.RelayNode{NodeID: "33333333-3333-4333-8333-333333333333", Generation: "44444444-4444-4444-8444-444444444444"}
	ca := testsupport.NewRelayCA(t)
	_, _, entryPair := ca.Issue(t, entryNode.NodeID)
	_, _, ownerPair := ca.Issue(t, ownerNode.NodeID)
	var owner *Server
	ownerBusiness := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Echo only the ID for this transport fixture, never business data.
		_, _ = io.WriteString(w, requestlog.ID(r.Context()))
	})
	privateHandler := requestlog.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		owner.PrivateRelayHandler(ownerBusiness).ServeHTTP(w, r)
	}), "relay", nil)
	private := httptest.NewUnstartedServer(privateHandler)
	private.TLS = &tls.Config{MinVersion: tls.VersionTLS13, ClientAuth: tls.RequireAndVerifyClientCert, ClientCAs: ca.Roots, Certificates: []tls.Certificate{ownerPair}, NextProtos: []string{"http/1.1"}}
	private.StartTLS()
	defer private.Close()
	ownerNode.Origin = private.URL
	route := store.RuntimeRoute{RelayNode: ownerNode,
		RuntimeID: "55555555-5555-4555-8555-555555555555", RuntimeEpoch: "66666666-6666-4666-8666-666666666666", ConnectionID: "77777777-7777-4777-8777-777777777777"}
	owner = New(nil)
	owner.hosts[route.RuntimeID] = &liveConnection{runtimeEpoch: route.RuntimeEpoch, connectionID: route.ConnectionID}
	ownerDirectory := &correlationDirectory{identity: ownerNode, peer: entryNode, route: route}
	if err := owner.ConfigureRouting(RoutingConfig{Directory: ownerDirectory, ClientTLS: &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: ca.Roots, Certificates: []tls.Certificate{ownerPair}}}); err != nil {
		t.Fatal(err)
	}
	entry := New(nil)
	if err := entry.ConfigureRouting(RoutingConfig{Directory: &correlationDirectory{identity: entryNode, peer: ownerNode, route: route}, ClientTLS: &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: ca.Roots, Certificates: []tls.Certificate{entryPair}}}); err != nil {
		t.Fatal(err)
	}
	public := httptest.NewServer(requestlog.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !entry.ForwardOwnerHTTP(w, r, route.RuntimeID) {
			t.Error("fixture did not forward to the owner")
		}
	}), "public", nil))
	defer public.Close()
	request, _ := http.NewRequestWithContext(ctx, "GET", public.URL+"/v1/files/88888888-8888-4888-8888-888888888888", nil)
	request.Header.Set(requestlog.Header, spoof)
	response, err := public.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	_ = response.Body.Close()
	id := response.Header.Get(requestlog.Header)
	if err != nil || response.StatusCode != 200 || id == "" || id == spoof || string(body) != id {
		t.Fatal("entry and owner failed to share a server-generated correlation ID", err)
	}

	// Exercise the real mTLS listener. Only a verified current node and an
	// owner-bound route may adopt a forwarded ID, including business rejects.
	transport := &http.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: ca.Roots, Certificates: []tls.Certificate{entryPair}}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}
	for _, path := range []string{privateOwnerHTTPPath, privateRelayPath} {
		for _, test := range []struct {
			name, node, generation string
			badRoute               bool
			status                 int
			adopted                bool
		}{
			{"current", entryNode.NodeID, entryNode.Generation, false, 200, true},
			{"stale", entryNode.NodeID, ownerNode.Generation, false, 503, false},
			{"mismatched_certificate", ownerNode.NodeID, entryNode.Generation, false, 403, false},
			{"wrong_owner_route", entryNode.NodeID, entryNode.Generation, true, 409, false},
		} {
			t.Run(path+"/"+test.name, func(t *testing.T) {
				r, _ := http.NewRequestWithContext(ctx, "GET", private.URL+path, nil)
				r.Header.Set(requestlog.Header, spoof)
				r.Header.Set(relayNodeHeader, test.node)
				r.Header.Set(relayGenerationHeader, test.generation)
				selected := route
				if test.badRoute {
					selected.Generation = entryNode.Generation
				}
				raw, _ := json.Marshal(selected)
				r.Header.Set(relayRouteHeader, base64.RawURLEncoding.EncodeToString(raw))
				r.Header.Set(relayOriginalPathHeader, "/v1/files/88888888-8888-4888-8888-888888888888")
				reply, err := client.Do(r)
				if err != nil {
					t.Fatal(err)
				}
				_, _ = io.Copy(io.Discard, reply.Body)
				_ = reply.Body.Close()
				expectedStatus := test.status
				if path == privateRelayPath && test.name == "current" {
					// No business ticket: ID adoption must not grant access.
					expectedStatus = 401
				}
				id := reply.Header.Get(requestlog.Header)
				if reply.StatusCode != expectedStatus || id == "" || (id == spoof) != test.adopted {
					t.Fatal("internal correlation crossed its trust boundary or changed business authorization")
				}
			})
		}
	}
	// A request that never passed mTLS must not adopt the claimed ID.
	r := httptest.NewRequest("GET", privateOwnerHTTPPath, nil)
	r.Header.Set(requestlog.Header, spoof)
	r.Header.Set(relayOriginalPathHeader, "/v1/files/88888888-8888-4888-8888-888888888888")
	w := httptest.NewRecorder()
	privateHandler.ServeHTTP(w, r)
	if w.Code != 403 || w.Header().Get(requestlog.Header) == spoof || strings.Contains(w.Body.String(), spoof) {
		t.Fatal("unverified internal request selected its correlation ID")
	}
}
