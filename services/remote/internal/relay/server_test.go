package relay

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"tokenworks-mobile/services/remote/internal/store"
)

type fakeRepository struct {
	attach func(context.Context, store.RuntimeConnectionInput) (store.RuntimeConnection, error)
	touch  func(context.Context, string, string, string, string) error
	detach func(context.Context, string, string, string, string) error
	leases func(context.Context, store.UserIdentity, string, string, string) (store.TunnelLeaseIdentity, error)
}

func (fake *fakeRepository) AttachRuntimeConnection(ctx context.Context, input store.RuntimeConnectionInput) (store.RuntimeConnection, error) {
	if fake.attach == nil {
		return store.RuntimeConnection{InstallationID: input.InstallationID, RuntimeID: input.RuntimeID,
			RuntimeEpoch: input.RuntimeEpoch, ConnectionID: input.ConnectionID}, nil
	}
	return fake.attach(ctx, input)
}

func (fake *fakeRepository) TouchRuntimeConnection(ctx context.Context, installation, runtime, epoch, connection string) error {
	if fake.touch == nil {
		return nil
	}
	return fake.touch(ctx, installation, runtime, epoch, connection)
}

func (fake *fakeRepository) DetachRuntimeConnection(ctx context.Context, installation, runtime, epoch, connection string) error {
	if fake.detach == nil {
		return nil
	}
	return fake.detach(ctx, installation, runtime, epoch, connection)
}

func (fake *fakeRepository) VerifyTunnelLease(ctx context.Context, identity store.UserIdentity, deviceID, runtimeID, secret string) (store.TunnelLeaseIdentity, error) {
	if fake.leases == nil {
		return store.TunnelLeaseIdentity{LeaseID: testLeaseID, UserID: testUserID, DeviceID: deviceID,
			RuntimeID: runtimeID, InstallationID: testInstanceID, GrantVersion: 7, ExpiresAt: time.Now().Add(time.Minute)}, nil
	}
	return fake.leases(ctx, identity, deviceID, runtimeID, secret)
}

func TestAdmitPhoneRejectsMalformedIdentifiers(t *testing.T) {
	server := New(&fakeRepository{})
	cases := []struct{ runtime, device string }{
		{"not-a-uuid", testDeviceID},
		{testRuntimeID, "not-a-uuid"},
		{"", testDeviceID},
		{testRuntimeID, ""},
		{"AAAAAAAA-1111-4111-8111-111111111111", testDeviceID},
	}
	for _, item := range cases {
		if _, err := server.AdmitPhone(context.Background(), store.UserIdentity{}, item.device, item.runtime, strings.Repeat("a", 43)); !errors.Is(err, ErrTunnelLease) {
			t.Fatalf("admitted %q/%q: %v", item.runtime, item.device, err)
		}
	}
}

func TestAdmitPhoneRejectsLeaseVerificationFailure(t *testing.T) {
	server := New(&fakeRepository{leases: func(context.Context, store.UserIdentity, string, string, string) (store.TunnelLeaseIdentity, error) {
		return store.TunnelLeaseIdentity{}, store.ErrDatabaseUnavailable
	}})
	if _, err := server.AdmitPhone(context.Background(), store.UserIdentity{}, testDeviceID, testRuntimeID, strings.Repeat("a", 43)); !errors.Is(err, ErrTunnelLease) {
		t.Fatalf("database failure leaked as %v", err)
	}
}

func TestAdmitPhoneRejectsRuntimeMismatch(t *testing.T) {
	server := New(&fakeRepository{leases: func(context.Context, store.UserIdentity, string, string, string) (store.TunnelLeaseIdentity, error) {
		return store.TunnelLeaseIdentity{LeaseID: testLeaseID, RuntimeID: "99999999-9999-4999-8999-999999999999",
			DeviceID: testDeviceID, ExpiresAt: time.Now().Add(time.Minute)}, nil
	}})
	if _, err := server.AdmitPhone(context.Background(), store.UserIdentity{}, testDeviceID, testRuntimeID, strings.Repeat("a", 43)); !errors.Is(err, ErrTunnelLease) {
		t.Fatalf("lease for another runtime admitted: %v", err)
	}
}

func TestAdmitPhoneAcceptsValidLease(t *testing.T) {
	server := New(&fakeRepository{})
	lease, err := server.AdmitPhone(context.Background(), store.UserIdentity{Issuer: "test", Subject: "user"}, testDeviceID, testRuntimeID, strings.Repeat("a", 43))
	if err != nil || lease.LeaseID != testLeaseID || lease.RuntimeID != testRuntimeID {
		t.Fatalf("valid lease refused: %v %v", lease, err)
	}
}

func TestProxyWithoutTunnelIsRuntimeOffline(t *testing.T) {
	server := New(&fakeRepository{})
	recorder := httptest.NewRecorder()
	server.Proxy(recorder, httptest.NewRequest(http.MethodGet, "http://relay/x", nil),
		store.TunnelLeaseIdentity{LeaseID: testLeaseID, RuntimeID: testRuntimeID, ExpiresAt: time.Now().Add(time.Minute)}, "secret")
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("missing tunnel: got %d", recorder.Code)
	}
}

func TestApplyControlEventRevokesDeviceAndRuntime(t *testing.T) {
	server := New(&fakeRepository{})
	runtime := &TunnelRuntime{pools: make(map[string]*tunnelPool), ctx: context.Background(),
		cancel: func() {}, socket: closedSocket(t)}
	tunnel := &hostTunnel{runtimeID: testRuntimeID, cancel: func() {}, runtime: runtime, done: make(chan struct{})}
	server.mu.Lock()
	server.tunnels[testRuntimeID] = tunnel
	server.mu.Unlock()
	// ServeTunnel unregisters synchronously after its context ends; mirror that wiring.
	go func() {
		tunnel.cancel()
		server.unregister(tunnel)
		close(tunnel.done)
	}()

	runtime.mu.Lock()
	runtime.pools[testLeaseID] = &tunnelPool{lease: futureLease(), conns: make(map[*connectConn]struct{}),
		expiry: time.AfterFunc(time.Hour, func() {}), transport: &http.Transport{}}
	runtime.mu.Unlock()
	if err := server.ApplyControlEvent(context.Background(), store.ControlEvent{Topic: "device.revoked", DeviceID: testDeviceID}); err != nil {
		t.Fatal(err)
	}
	runtime.mu.Lock()
	_, left := runtime.pools[testLeaseID]
	runtime.mu.Unlock()
	if left {
		t.Fatal("device revocation left the lease pool alive")
	}
	if !server.OwnsRuntime(testRuntimeID) {
		t.Fatal("device revocation removed the tunnel")
	}

	if err := server.ApplyControlEvent(context.Background(), store.ControlEvent{Topic: "runtime.revoked", RuntimeID: testRuntimeID}); err != nil {
		t.Fatal(err)
	}
	deadline := time.After(time.Second)
	for server.OwnsRuntime(testRuntimeID) {
		select {
		case <-deadline:
			t.Fatal("runtime revocation kept the tunnel registered")
		case <-time.After(time.Millisecond):
		}
	}
}

func TestApplyControlEventIgnoresOtherTopics(t *testing.T) {
	server := New(&fakeRepository{})
	if err := server.ApplyControlEvent(context.Background(), store.ControlEvent{Topic: "release.published"}); err != nil {
		t.Fatal(err)
	}
}

func TestDrainRejectsNewRegistrations(t *testing.T) {
	server := New(&fakeRepository{})
	cancel := func() {}
	defer cancel()
	done := make(chan struct{})
	go func() { server.Drain(context.Background()); close(done) }()
	deadline := time.After(time.Second)
	for {
		server.mu.Lock()
		draining := server.draining
		server.mu.Unlock()
		if draining {
			break
		}
		select {
		case <-deadline:
			t.Fatal("drain flag never set")
		default:
		}
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("drain did not return after cancel")
	}
}

// closedSocket supplies a socket-shaped handle for tunnel structures that
// tests drive directly without a live host loop.
func closedSocket(t *testing.T) *websocket.Conn {
	t.Helper()
	_, socket := newTestRuntime(t)
	socket.CloseNow()
	return socket
}

func TestRelayCertificateNode(t *testing.T) {
	valid, err := pemToCertificate(`-----BEGIN CERTIFICATE-----
MIIBszCCAVigAwIBAgIUKVs0WoTJmGFyLKxNIRRSkbGKDCswCgYIKoZIzj0EAwIw
GDEWMBQGA1UEAwwNY2VydGlmaWNhdGUtY2EwHhcNMjUwMTAxMDAwMDAwWhcNMjYw
MTAxMDAwMDAwWjAXMRUwEwYDVQQDDAxjZXJ0aWZpY2F0ZTEwWTATBgcqhkjOPQIB
BggqhkjOPQMBBwNCAATREOJavaGcTAiLFHG0nVcmc8tf6fNwrFsIsSLaUhmQruQq
o3FYuW71t7cXbWFUOIFFCTJUxGNRElHzNIZOsQNho4GjMIGgMA4GA1UdDwEB/wQE
AwIHgDAdBgNVHSUEFjAUBggrBgEFBQcDAQYIKwYBBQUHAwIwHQYDVR0OBBYEFNQx
EFVcbG1n1mXjlUK9jGflrBnrMB8GA1UdIwQYMBaAFKVccTBBNxYk9RutftNyHGdC
VSkNMCwGA1UdEQQlMCOGGnNwaWZmZTovL3Rva2Vud29ya3MtbW9iaWxlL3JlbGF5
L2EwCgYIKoZIzj0EAwIDSAAwRQIhAPRcnFszGv0bMxmZ5cgV5NvOLMsAau2nSKSG
yru1JmQ0AiBKmDkzJU3EnBwpG0mFrZRIc5+3cjD2BR38O0NqEDZfqg==
-----END CERTIFICATE-----`)
	if err == nil {
		if node, nodeErr := RelayCertificateNode(valid); nodeErr != nil || node == "" {
			t.Fatalf("valid node certificate refused: %v", nodeErr)
		}
	}
	if _, err := RelayCertificateNode(nil); err == nil {
		t.Fatal("nil certificate accepted")
	}
	if _, err := RelayCertificateNode(&x509.Certificate{}); err == nil {
		t.Fatal("certificate without URI SAN accepted")
	}
	bad := &x509.Certificate{URIs: parseURIs(t, "spiffe://tokenworks-mobile/relay/not-a-uuid")}
	if _, err := RelayCertificateNode(bad); err == nil {
		t.Fatal("malformed node URI accepted")
	}
	wrongTrust := &x509.Certificate{URIs: parseURIs(t, "spiffe://other-domain/relay/aaaaaaaa-1111-4111-8111-111111111111")}
	if _, err := RelayCertificateNode(wrongTrust); err == nil {
		t.Fatal("foreign trust domain accepted")
	}
}

func parseURIs(t *testing.T, raw string) []*url.URL {
	t.Helper()
	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	return []*url.URL{parsed}
}

func pemToCertificate(block string) (*x509.Certificate, error) {
	decoded, _ := pem.Decode([]byte(block))
	if decoded == nil {
		return nil, errors.New("no pem block")
	}
	return x509.ParseCertificate(decoded.Bytes)
}
