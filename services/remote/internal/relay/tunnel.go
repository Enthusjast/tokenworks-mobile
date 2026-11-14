package relay

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"reflect"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"tokenworks-mobile/services/remote/internal/store"
)

const tunnelAuthority = "dsh-noise:1"
const carrierPurposeHeader = "X-TokenWorks-Carrier-Purpose"
const carrierBufferBytes = 32 << 10
const carrierWindowBytes = 256 << 10

var ErrTunnelAdmission = errors.New("relay: tunnel admission refused")

// TunnelRuntime multiplexes bounded opaque carrier streams, never native HTTP.
// There is exactly one physical Host connection and no reconnect/replay path.
type TunnelRuntime struct {
	ctx context.Context
	cancel context.CancelFunc
	socket *websocket.Conn
	wire net.Conn
	h2 *http.Transport
	handed atomic.Bool
	mu sync.Mutex
	streams map[*carrierStream]struct{}
	closed bool
}

func NewTunnelRuntime(parent context.Context, socket *websocket.Conn) *TunnelRuntime {
	ctx, cancel := context.WithCancel(parent)
	wire := websocket.NetConn(ctx, socket, websocket.MessageBinary)
	socket.SetReadLimit(carrierWindowBytes)
	runtime := &TunnelRuntime{ctx: ctx, cancel: cancel, socket: socket, wire: wire, streams: make(map[*carrierStream]struct{})}
	protocols := new(http.Protocols)
	protocols.SetUnencryptedHTTP2(true)
	runtime.h2 = &http.Transport{
		Protocols: protocols, MaxConnsPerHost: 1, MaxResponseHeaderBytes: 16 << 10,
		DisableCompression: true,
		HTTP2: &http.HTTP2Config{StrictMaxConcurrentRequests: true, MaxReceiveBufferPerStream: 128 << 10,
			MaxReceiveBufferPerConnection: 2 << 20, MaxReadFrameSize: 16 << 10},
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			if ctx.Err() != nil || runtime.ctx.Err() != nil || !runtime.handed.CompareAndSwap(false, true) {
				return nil, ErrTunnelAdmission
			}
			return &messageBoundConn{Conn: wire, failed: cancel}, nil
		},
	}
	return runtime
}

// HTTP/2 writes can coalesce frames; keep each outer WS message bounded too.
type messageBoundConn struct {
	net.Conn
	failed context.CancelFunc
}

func (conn *messageBoundConn) Read(data []byte) (int, error) {
	n, err := conn.Conn.Read(data)
	if err != nil && conn.failed != nil { conn.failed() }
	return n, err
}

func (conn *messageBoundConn) Write(data []byte) (int, error) {
	total := 0
	for len(data) > 0 {
		size := min(len(data), carrierBufferBytes)
		n, err := conn.Conn.Write(data[:size])
		total += n
		data = data[n:]
		if err == nil && n == 0 { err = io.ErrShortWrite }
		if err != nil {
			if conn.failed != nil { conn.failed() }
			return total, err
		}
	}
	return total, nil
}

// CarrierPurpose is public ingress metadata, not native authorization.
func CarrierPurpose(request *http.Request) string {
	purpose := singleHeader(request, carrierPurposeHeader)
	if purpose != "native" && purpose != "pairing-control" { return "" }
	return purpose
}

func validCarrierRequest(request *http.Request) bool {
	return request.Method == http.MethodGet && request.URL.RawQuery == "" && !request.URL.ForceQuery &&
		CarrierPurpose(request) != "" && request.Header.Get("Sec-WebSocket-Protocol") == "" &&
		request.Header.Get("Upgrade") == "websocket"
}

func (runtime *TunnelRuntime) Carrier(response http.ResponseWriter, request *http.Request, lease store.TunnelLeaseIdentity, secret string) {
	if !validCarrierRequest(request) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	stream, err := runtime.connect(request.Context(), lease, secret, CarrierPurpose(request))
	if err != nil {
		writeError(response, http.StatusServiceUnavailable, "runtime.offline")
		return
	}
	defer stream.Close()
	socket, err := websocket.Accept(response, request, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil { return }
	defer socket.CloseNow()
	stop := context.AfterFunc(stream.ctx, func() { socket.CloseNow() })
	defer stop()
	phone := websocket.NetConn(stream.ctx, socket, websocket.MessageBinary)
	socket.SetReadLimit(carrierWindowBytes)
	splice(phone, stream, func() { socket.CloseNow(); stream.Close() })
}

// Two fixed buffers and blocking writes provide backpressure in both directions.
// Even EOF closes both sides: unauthenticated truncation is never graceful CLOSE.
func splice(left, right io.ReadWriter, closeBoth func()) {
	done := make(chan struct{}, 1)
	go func() {
		_, _ = io.CopyBuffer(left, right, make([]byte, carrierBufferBytes))
		closeBoth()
		done <- struct{}{}
	}()
	_, _ = io.CopyBuffer(right, left, make([]byte, carrierBufferBytes))
	closeBoth()
	<-done
}

func (runtime *TunnelRuntime) connect(parent context.Context, lease store.TunnelLeaseIdentity, secret, purpose string) (*carrierStream, error) {
	if !uuidPattern.MatchString(lease.LeaseID) || !uuidPattern.MatchString(lease.DeviceID) ||
		!uuidPattern.MatchString(lease.RuntimeID) || !lease.ExpiresAt.After(time.Now()) || len(secret) != 43 ||
		(purpose != "native" && purpose != "pairing-control") {
		return nil, ErrTunnelAdmission
	}
	ctx, cancel := context.WithDeadline(parent, lease.ExpiresAt)
	reader, writer := io.Pipe()
	stream := &carrierStream{ctx: ctx, cancel: cancel, lease: lease, secret: secret, input: reader, writer: writer, runtime: runtime}
	runtime.mu.Lock()
	deviceStreams := 0
	for current := range runtime.streams {
		if current.lease.LeaseID == lease.LeaseID && !reflect.DeepEqual(current.lease, lease) {
			runtime.mu.Unlock(); cancel(); reader.Close(); writer.Close()
			return nil, ErrTunnelAdmission
		}
		if current.lease.DeviceID == lease.DeviceID { deviceStreams++ }
	}
	if runtime.closed || runtime.ctx.Err() != nil || len(runtime.streams) >= maxConnectsPerRuntime || deviceStreams >= maxConnectsPerLease {
		runtime.mu.Unlock(); cancel(); reader.Close(); writer.Close()
		return nil, ErrTunnelAdmission
	}
	runtime.streams[stream] = struct{}{}
	runtime.mu.Unlock()
	stopRuntime := context.AfterFunc(runtime.ctx, cancel)
	stopClose := context.AfterFunc(ctx, func() { stream.Close() })
	stream.mu.Lock()
	stream.stopRuntime, stream.stopClose = stopRuntime, stopClose
	if stream.closed { stopRuntime(); stopClose() }
	stream.mu.Unlock()
	timer := time.AfterFunc(15*time.Second, cancel)
	request := &http.Request{Method: http.MethodConnect, URL: &url.URL{Scheme: "http", Host: tunnelAuthority},
		Host: tunnelAuthority, Header: make(http.Header), Body: reader}
	request.Header.Set("Proxy-Authorization", "Bearer "+secret)
	request.Header.Set("X-TokenWorks-Lease-Id", lease.LeaseID)
	request.Header.Set(carrierPurposeHeader, purpose)
	response, err := runtime.h2.RoundTrip(request.WithContext(ctx))
	timer.Stop()
	if err != nil { stream.Close(); return nil, err }
	stream.mu.Lock()
	if stream.closed || ctx.Err() != nil || response.StatusCode != http.StatusOK || response.ProtoMajor != 2 {
		stream.mu.Unlock(); response.Body.Close(); stream.Close()
		return nil, ErrTunnelAdmission
	}
	stream.reader = response.Body
	stream.mu.Unlock()
	return stream, nil
}

func (runtime *TunnelRuntime) revoke(matches func(store.TunnelLeaseIdentity) bool) {
	runtime.mu.Lock()
	streams := make([]*carrierStream, 0, len(runtime.streams))
	for stream := range runtime.streams { if matches(stream.lease) { streams = append(streams, stream) } }
	runtime.mu.Unlock()
	for _, stream := range streams { stream.Close() }
}

func (runtime *TunnelRuntime) RevokeLease(id string) {
	runtime.revoke(func(lease store.TunnelLeaseIdentity) bool { return lease.LeaseID == id })
}

func (runtime *TunnelRuntime) RevokeDevice(deviceID string) {
	runtime.revoke(func(lease store.TunnelLeaseIdentity) bool { return lease.DeviceID == deviceID })
}

func (runtime *TunnelRuntime) Revalidate(ctx context.Context, inspect func(context.Context, string, string) (store.TunnelLeaseIdentity, error)) error {
	runtime.mu.Lock()
	streams := make([]*carrierStream, 0, len(runtime.streams))
	for stream := range runtime.streams { streams = append(streams, stream) }
	runtime.mu.Unlock()
	var result error
	for _, stream := range streams {
		lease, err := inspect(ctx, stream.lease.LeaseID, stream.secret)
		if err != nil || !reflect.DeepEqual(lease, stream.lease) { stream.Close() }
		result = errors.Join(result, err)
	}
	return result
}

func (runtime *TunnelRuntime) Close() {
	runtime.mu.Lock()
	if runtime.closed { runtime.mu.Unlock(); return }
	runtime.closed = true
	runtime.mu.Unlock()
	runtime.cancel()
	runtime.revoke(func(store.TunnelLeaseIdentity) bool { return true })
	runtime.socket.CloseNow()
	runtime.h2.CloseIdleConnections()
}

type carrierStream struct {
	ctx context.Context
	cancel context.CancelFunc
	lease store.TunnelLeaseIdentity
	secret string
	runtime *TunnelRuntime
	input *io.PipeReader
	writer *io.PipeWriter
	mu sync.Mutex
	reader io.ReadCloser
	closed bool
	stopRuntime func() bool
	stopClose func() bool
}

func (stream *carrierStream) Read(data []byte) (int, error) { return stream.reader.Read(data) }
func (stream *carrierStream) Write(data []byte) (int, error) { return stream.writer.Write(data) }
func (stream *carrierStream) Close() error {
	stream.mu.Lock()
	if stream.closed { stream.mu.Unlock(); return nil }
	stream.closed = true
	stream.cancel()
	if stream.stopRuntime != nil { stream.stopRuntime() }
	if stream.stopClose != nil { stream.stopClose() }
	if stream.reader != nil { stream.reader.Close() }
	stream.input.Close()
	stream.writer.Close()
	stream.mu.Unlock()
	stream.runtime.mu.Lock()
	delete(stream.runtime.streams, stream)
	stream.runtime.mu.Unlock()
	return nil
}
