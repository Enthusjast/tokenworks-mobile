package relay

import (
	"bytes"
	"context"
	"crypto/rand"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"tokenworks-mobile/services/remote/internal/store"
)

const (
	testRuntimeID = "aaaaaaaa-1111-4111-8111-111111111111"
	testDeviceID = "bbbbbbbb-2222-4222-8222-222222222222"
	testUserID = "cccccccc-3333-4333-8333-333333333333"
	testLeaseID = "dddddddd-4444-4444-8444-444444444444"
	testInstanceID = "eeeeeeee-5555-4555-8555-555555555555"
)

func futureLease() store.TunnelLeaseIdentity {
	return store.TunnelLeaseIdentity{LeaseID: testLeaseID, DeviceID: testDeviceID, UserID: testUserID,
		RuntimeID: testRuntimeID, InstallationID: testInstanceID, GrantVersion: 7, Role: "owner", IsInstallationOwner: true,
		ExpiresAt: time.Now().Add(time.Minute)}
}

// This fixture carries real HTTP/2 over a real binary WebSocket. Its endpoint
// handles CONNECT bytes only, so native-looking input cannot become outer HTTP.
func newTestRuntime(t *testing.T, handler http.Handler) *TunnelRuntime {
	t.Helper()
	protocols := new(http.Protocols)
	protocols.SetUnencryptedHTTP2(true)
	host := &http.Server{Handler: handler, Protocols: protocols}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		socket, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
		if err != nil { return }
		defer socket.CloseNow()
		conn := websocket.NetConn(context.Background(), socket, websocket.MessageBinary)
		_ = host.Serve(&oneConnListener{conn: conn, done: make(chan struct{})})
	}))
	t.Cleanup(server.Close)
	t.Cleanup(func() { host.Close() })
	socket, _, err := websocket.Dial(context.Background(), server.URL, nil)
	if err != nil { t.Fatal(err) }
	runtime := NewTunnelRuntime(context.Background(), socket)
	t.Cleanup(runtime.Close)
	return runtime
}

type oneConnListener struct {
	conn net.Conn
	mu sync.Mutex
	done chan struct{}
	once sync.Once
}
func (listener *oneConnListener) Accept() (net.Conn, error) {
	listener.mu.Lock()
	if listener.conn != nil { conn := listener.conn; listener.conn = nil; listener.mu.Unlock(); return conn, nil }
	listener.mu.Unlock()
	<-listener.done
	return nil, net.ErrClosed
}
func (listener *oneConnListener) Close() error { listener.once.Do(func() { close(listener.done) }); return nil }
func (listener *oneConnListener) Addr() net.Addr { return netAddr{} }
type netAddr struct{}
func (netAddr) Network() string { return "test" }
func (netAddr) String() string { return "test" }

func dialCarrier(t *testing.T, runtime *TunnelRuntime, lease store.TunnelLeaseIdentity) (*websocket.Conn, context.Context) {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		runtime.Carrier(w, r, lease, strings.Repeat("a", 43))
	}))
	t.Cleanup(server.Close)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	socket, _, err := websocket.Dial(ctx, server.URL, &websocket.DialOptions{HTTPHeader: http.Header{carrierPurposeHeader: {"native"}}})
	if err != nil { t.Fatal(err) }
	t.Cleanup(func() { socket.CloseNow() })
	return socket, ctx
}

func echoConnect(t *testing.T, done chan<- struct{}) http.Handler {
	t.Helper()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() { if done != nil { done <- struct{}{} } }()
		if r.Method != http.MethodConnect || r.ProtoMajor != 2 || r.Host != tunnelAuthority ||
			r.Header.Get("Proxy-Authorization") != "Bearer "+strings.Repeat("a", 43) ||
			r.Header.Get("X-TokenWorks-Lease-Id") != testLeaseID || r.Header.Get(carrierPurposeHeader) != "native" {
			t.Errorf("wrong carrier CONNECT: %s %s %s %v", r.Method, r.Proto, r.Host, r.Header)
			w.WriteHeader(http.StatusForbidden); return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		buffer := make([]byte, carrierBufferBytes)
		for {
			n, err := r.Body.Read(buffer)
			if n > 0 { if _, writeErr := w.Write(buffer[:n]); writeErr != nil { return }; w.(http.Flusher).Flush() }
			if err != nil { return }
		}
	})
}

func TestCarrierOpaqueBinaryRoundTrip(t *testing.T) {
	done := make(chan struct{}, 1)
	runtime := newTestRuntime(t, echoConnect(t, done))
	socket, ctx := dialCarrier(t, runtime, futureLease())
	payload := make([]byte, 180<<10)
	if _, err := rand.Read(payload); err != nil { t.Fatal(err) }
	copy(payload, []byte("GET /api/private HTTP/1.1\r\nAuthorization: cleartext-canary\r\n\r\n\x00\xff"))
	// Writes and reads run concurrently: larger-than-window traffic must not
	// depend on an unbounded queue on either side.
	written := make(chan error, 1)
	go func() { written <- socket.Write(ctx, websocket.MessageBinary, payload) }()
	wire := websocket.NetConn(ctx, socket, websocket.MessageBinary)
	socket.SetReadLimit(carrierWindowBytes)
	actual := make([]byte, len(payload))
	if _, err := io.ReadFull(wire, actual); err != nil { t.Fatal(err) }
	if err := <-written; err != nil { t.Fatal(err) }
	if !bytes.Equal(actual, payload) { t.Fatal("opaque carrier changed bytes") }
	socket.CloseNow()
	select { case <-done: case <-ctx.Done(): t.Fatal("phone cancellation did not close CONNECT") }
}

func TestCarrierExpiryAndRevocationCloseBothSides(t *testing.T) {
	for _, revoke := range []bool{false, true} {
		t.Run(map[bool]string{false:"expiry", true:"revoke"}[revoke], func(t *testing.T) {
			done := make(chan struct{}, 1)
			runtime := newTestRuntime(t, echoConnect(t, done))
			lease := futureLease()
			if !revoke { lease.ExpiresAt = time.Now().Add(500*time.Millisecond) }
			socket, ctx := dialCarrier(t, runtime, lease)
			if err := socket.Write(ctx, websocket.MessageBinary, []byte{0, 255, 1}); err != nil { t.Fatal(err) }
			if _, _, err := socket.Read(ctx); err != nil { t.Fatal(err) }
			if revoke { runtime.RevokeDevice(testDeviceID) }
			if _, _, err := socket.Read(ctx); err == nil { t.Fatal("retired lease left phone WS open") }
			select { case <-done: case <-ctx.Done(): t.Fatal("retired lease left CONNECT open") }
			runtime.mu.Lock(); count := len(runtime.streams); runtime.mu.Unlock()
			if count != 0 { t.Fatalf("retired carrier leaked %d streams", count) }
		})
	}
}

func TestCarrierRejectsTextAndClosesCONNECT(t *testing.T) {
	done := make(chan struct{}, 1)
	runtime := newTestRuntime(t, echoConnect(t, done))
	socket, ctx := dialCarrier(t, runtime, futureLease())
	if err := socket.Write(ctx, websocket.MessageText, []byte("not binary")); err != nil { t.Fatal(err) }
	if _, _, err := socket.Read(ctx); err == nil { t.Fatal("text carrier accepted") }
	select { case <-done: case <-ctx.Done(): t.Fatal("text failure did not close CONNECT") }
}

func TestCarrierConnectRefusesExpiredAndMissingLease(t *testing.T) {
	runtime := newTestRuntime(t, echoConnect(t, nil))
	for _, change := range []func(*store.TunnelLeaseIdentity){
		func(lease *store.TunnelLeaseIdentity) { lease.LeaseID = "" },
		func(lease *store.TunnelLeaseIdentity) { lease.ExpiresAt = time.Now().Add(-time.Second) },
	} {
		lease := futureLease(); change(&lease)
		if _, err := runtime.connect(context.Background(), lease, strings.Repeat("a",43), "native"); err == nil { t.Fatal("invalid lease accepted") }
	}
}

func TestCarrierDeviceBudgetSpansLeases(t *testing.T) {
	runtime := newTestRuntime(t, echoConnect(t, nil))
	lease := futureLease()
	for range maxConnectsPerLease {
		stream, err := runtime.connect(context.Background(), lease, strings.Repeat("a",43), "native")
		if err != nil { t.Fatal(err) }
		defer stream.Close()
	}
	lease.LeaseID = testInstanceID
	if _, err := runtime.connect(context.Background(), lease, strings.Repeat("a",43), "native"); err == nil { t.Fatal("new lease bypassed device carrier budget") }
}

func TestCarrierHostEOFClosesPhoneWithoutRetry(t *testing.T) {
	var calls atomicCounter
	runtime := newTestRuntime(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.add()
		w.WriteHeader(http.StatusOK); w.(http.Flusher).Flush()
		w.Write([]byte{0, 1, 255}); w.(http.Flusher).Flush()
	}))
	socket, ctx := dialCarrier(t, runtime, futureLease())
	for { if _, _, err := socket.Read(ctx); err != nil { break } }
	if calls.get() != 1 { t.Fatal("carrier CONNECT retried") }
}

type atomicCounter struct { mu sync.Mutex; n int }
func (counter *atomicCounter) add() { counter.mu.Lock(); counter.n++; counter.mu.Unlock() }
func (counter *atomicCounter) get() int { counter.mu.Lock(); defer counter.mu.Unlock(); return counter.n }
