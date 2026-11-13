package relay

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const (
	testRuntimeID  = "aaaaaaaa-1111-4111-8111-111111111111"
	testDeviceID   = "bbbbbbbb-2222-4222-8222-222222222222"
	testUserID     = "cccccccc-3333-4333-8333-333333333333"
	testLeaseID    = "dddddddd-4444-4444-8444-444444444444"
	testInstanceID = "eeeeeeee-5555-4555-8555-555555555555"
)

func futureLease() TunnelLease {
	return TunnelLease{ID: testLeaseID, DeviceID: testDeviceID, UserID: testUserID,
		RuntimeID: testRuntimeID, GrantVersion: 7, ExpiresAt: time.Now().Add(time.Minute)}
}

// A silent host socket: the tunnel never needs a real dsh peer to assert
// admission and revocation behavior.
func newTestRuntime(t *testing.T) (*TunnelRuntime, *websocket.Conn) {
	t.Helper()
	ready := make(chan *websocket.Conn, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		socket, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		ready <- socket
		defer socket.Close(websocket.StatusNormalClosure, "test done")
		for {
			if _, _, err := socket.Read(r.Context()); err != nil {
				return
			}
		}
	}))
	t.Cleanup(server.Close)
	request, err := http.NewRequest(http.MethodGet, server.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Connection", "Upgrade")
	request.Header.Set("Upgrade", "websocket")
	request.Header.Set("Sec-WebSocket-Version", "13")
	request.Header.Set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	socket := <-ready
	t.Cleanup(func() { socket.CloseNow() })
	runtime := NewTunnelRuntime(context.Background(), socket)
	t.Cleanup(runtime.Close)
	return runtime, socket
}

func TestTunnelRuntimeProxyRejectsRefusedLeases(t *testing.T) {
	runtime, _ := newTestRuntime(t)
	request := httptest.NewRequest(http.MethodGet, "http://dsh-api/session/list", nil)

	lease := futureLease()
	lease.ID = ""
	recorder := httptest.NewRecorder()
	runtime.Proxy(recorder, request, lease, "secret")
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("empty lease id: got %d", recorder.Code)
	}

	recorder = httptest.NewRecorder()
	runtime.Proxy(recorder, request, lease, "")
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("empty secret: got %d", recorder.Code)
	}

	expired := futureLease()
	expired.ExpiresAt = time.Now().Add(-time.Second)
	recorder = httptest.NewRecorder()
	runtime.Proxy(recorder, request, expired, "secret")
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("expired lease: got %d", recorder.Code)
	}
}

func TestTunnelRuntimeProxyRejectsClosedRuntime(t *testing.T) {
	runtime, _ := newTestRuntime(t)
	runtime.Close()
	recorder := httptest.NewRecorder()
	runtime.Proxy(recorder, httptest.NewRequest(http.MethodGet, "http://dsh-api/x", nil), futureLease(), "secret")
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("closed runtime: got %d", recorder.Code)
	}
}

func TestTunnelRuntimeProxyRejectsChangedLeaseIdentity(t *testing.T) {
	runtime, _ := newTestRuntime(t)
	lease := futureLease()
	// Seed a pool under one grant version, then present different facts.
	runtime.mu.Lock()
	runtime.pools[lease.ID] = &tunnelPool{lease: lease, conns: make(map[*connectConn]struct{}), expiry: time.AfterFunc(time.Hour, func() {}), transport: &http.Transport{}}
	runtime.mu.Unlock()
	defer runtime.RevokeLease(lease.ID)

	mutated := futureLease()
	mutated.GrantVersion++
	recorder := httptest.NewRecorder()
	runtime.Proxy(recorder, httptest.NewRequest(http.MethodGet, "http://dsh-api/x", nil), mutated, "secret")
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("changed grant version: got %d", recorder.Code)
	}

	mutated = futureLease()
	mutated.DeviceID = testInstanceID
	recorder = httptest.NewRecorder()
	runtime.Proxy(recorder, httptest.NewRequest(http.MethodGet, "http://dsh-api/x", nil), mutated, "secret")
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("changed device: got %d", recorder.Code)
	}
}

func TestTunnelRuntimeConnectRefusesMissingSecret(t *testing.T) {
	runtime, _ := newTestRuntime(t)
	runtime.mu.Lock()
	runtime.pools[testLeaseID] = &tunnelPool{lease: futureLease(), conns: make(map[*connectConn]struct{}), expiry: time.AfterFunc(time.Hour, func() {}), transport: &http.Transport{}}
	runtime.mu.Unlock()
	defer runtime.RevokeLease(testLeaseID)

	if _, err := runtime.connect(context.Background(), testLeaseID, ""); !errors.Is(err, ErrTunnelAdmission) {
		t.Fatalf("missing secret: got %v", err)
	}
}

func TestTunnelRuntimeConnectEnforcesLeaseBudget(t *testing.T) {
	runtime, _ := newTestRuntime(t)
	pool := &tunnelPool{lease: futureLease(), conns: make(map[*connectConn]struct{}), expiry: time.AfterFunc(time.Hour, func() {}), transport: &http.Transport{}}
	for range maxConnectsPerLease {
		pool.conns[&connectConn{}] = struct{}{}
	}
	runtime.active = maxConnectsPerLease
	runtime.mu.Lock()
	runtime.pools[testLeaseID] = pool
	runtime.mu.Unlock()
	defer runtime.RevokeLease(testLeaseID)

	if _, err := runtime.connect(context.Background(), testLeaseID, "secret"); !errors.Is(err, ErrTunnelAdmission) {
		t.Fatalf("full lease budget admitted: %v", err)
	}

	runtime.mu.Lock()
	pool.conns = make(map[*connectConn]struct{})
	runtime.pools[testLeaseID] = pool
	runtime.mu.Unlock()

	for range maxConnectsPerRuntime {
		runtime.active++
	}
	if _, err := runtime.connect(context.Background(), testLeaseID, "secret"); !errors.Is(err, ErrTunnelAdmission) {
		t.Fatalf("full runtime budget admitted: %v", err)
	}
}

func TestTunnelRuntimeRevokeDeviceDropsOnlyOwnLeases(t *testing.T) {
	runtime, _ := newTestRuntime(t)
	own := futureLease()
	other := futureLease()
	other.DeviceID = testInstanceID
	other.ID = testInstanceID
	runtime.mu.Lock()
	runtime.pools[own.ID] = &tunnelPool{lease: own, conns: make(map[*connectConn]struct{}), expiry: time.AfterFunc(time.Hour, func() {}), transport: &http.Transport{}}
	runtime.pools[other.ID] = &tunnelPool{lease: other, conns: make(map[*connectConn]struct{}), expiry: time.AfterFunc(time.Hour, func() {}), transport: &http.Transport{}}
	runtime.mu.Unlock()

	runtime.RevokeDevice(testDeviceID)

	runtime.mu.Lock()
	_, ownLeft := runtime.pools[own.ID]
	_, otherLeft := runtime.pools[other.ID]
	runtime.mu.Unlock()
	if ownLeft {
		t.Fatal("own device lease survived revocation")
	}
	if !otherLeft {
		t.Fatal("unrelated lease was revoked")
	}
}

func TestTunnelRuntimeRevokeLeaseClosesConnections(t *testing.T) {
	runtime, _ := newTestRuntime(t)
	runtime.mu.Lock()
	runtime.pools[testLeaseID] = &tunnelPool{lease: futureLease(), conns: make(map[*connectConn]struct{}), expiry: time.AfterFunc(time.Hour, func() {}), transport: &http.Transport{}}
	runtime.mu.Unlock()

	conn := &connectConn{reader: io.NopCloser(bytes.NewReader(nil))}
	closed := make(chan struct{})
	conn.onClose = func() { close(closed) }
	runtime.mu.Lock()
	runtime.pools[testLeaseID].conns[conn] = struct{}{}
	runtime.active++
	runtime.mu.Unlock()

	runtime.RevokeLease(testLeaseID)
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("revoked lease did not close its connection")
	}
	runtime.mu.Lock()
	active := runtime.active
	pools := len(runtime.pools)
	runtime.mu.Unlock()
	if active != 0 || pools != 0 {
		t.Fatalf("bookkeeping leaked: active=%d pools=%d", active, pools)
	}
}

func TestMessageBoundConnWritesIn64KiBChunks(t *testing.T) {
	var sizes []int
	base := writeCounter(func(data []byte) (int, error) {
		sizes = append(sizes, len(data))
		return len(data), nil
	})
	conn := &messageBoundConn{Conn: base}
	payload := make([]byte, 64<<10+17)
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	if len(sizes) != 2 || sizes[0] != 64<<10 || sizes[1] != 17 {
		t.Fatalf("unexpected chunking: %v", sizes)
	}
}

type writeCounter func([]byte) (int, error)

func (write writeCounter) Read([]byte) (int, error)         { return 0, io.EOF }
func (write writeCounter) Write(data []byte) (int, error)   { return write(data) }
func (write writeCounter) Close() error                     { return nil }
func (write writeCounter) LocalAddr() net.Addr              { return netAddr{} }
func (write writeCounter) RemoteAddr() net.Addr             { return netAddr{} }
func (write writeCounter) SetDeadline(time.Time) error      { return nil }
func (write writeCounter) SetReadDeadline(time.Time) error  { return nil }
func (write writeCounter) SetWriteDeadline(time.Time) error { return nil }

type netAddr struct{}

func (netAddr) Network() string { return "test" }
func (netAddr) String() string  { return "test" }
