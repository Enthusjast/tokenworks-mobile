// Package requestlog correlates HTTP attempts without inspecting business data.
package requestlog

import (
	"bufio"
	"context"
	"crypto/rand"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"regexp"
	"sync/atomic"
	"time"
)

const Header = "X-Request-ID"

var validID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func ValidID(value string) bool { return validID.MatchString(value) }

type contextKey struct{}
type attempt struct {
	id           atomic.Value
	jsonEnvelope bool
}

// ID identifies one HTTP attempt, not a command or its retries.
func ID(ctx context.Context) string {
	if value, ok := ctx.Value(contextKey{}).(*attempt); ok {
		return value.id.Load().(string)
	}
	return ""
}

// Middleware must enclose admission guards as well as the router. Every entry
// gets a fresh server ID; caller-supplied headers never select the initial ID.
// The log has a fixed set of safe fields and never includes URL or headers.
func Middleware(next http.Handler, listener string, logger *slog.Logger) http.Handler {
	if listener != "public" && listener != "relay" {
		listener = "other"
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		var raw [16]byte
		_, _ = rand.Read(raw[:]) // crypto/rand.Read is infallible in Go 1.26.
		raw[6], raw[8] = raw[6]&0x0f|0x40, raw[8]&0x3f|0x80
		value := &attempt{}
		value.id.Store(fmt.Sprintf("%x-%x-%x-%x-%x", raw[:4], raw[4:6], raw[6:8], raw[8:10], raw[10:]))
		r = r.WithContext(context.WithValue(r.Context(), contextKey{}, value))
		response := &writer{ResponseWriter: w, attempt: value}
		response.setID()
		completed := false
		defer func() {
			status := response.status
			if completed && status == 0 && !response.hijacked {
				status = http.StatusOK
			}
			// Keep successful readiness polling out of the access log. Its
			// responses still carry an ID; failed probes remain visible.
			probe := r.Method == "GET" && (r.URL.Path == "/healthz" || r.URL.Path == "/readyz")
			if logger != nil && !(probe && completed && (status == http.StatusOK || status == http.StatusNoContent)) {
				logger.Info("http_request", "request_id", ID(r.Context()), "listener", listener,
					"method", safeMethod(r.Method), "status", status,
					"duration_ms", float64(time.Since(started))/float64(time.Millisecond),
					"hijacked", response.hijacked, "completed", completed)
			}
		}()
		versions := r.Header.Values(EnvelopeHeader)
		if len(versions) > 1 || len(versions) == 1 && versions[0] == "" {
			Error(response, http.StatusBadRequest, "protocol.invalid")
			completed = true
			return
		}
		if len(versions) == 1 {
			if versions[0] != RequestIDEnvelope {
				Error(response, http.StatusNotAcceptable, "protocol.unsupported")
				completed = true
				return
			}
			value.jsonEnvelope = true
		}
		next.ServeHTTP(response, r)
		completed = true
	})
}

// ForwardHeaders copies only the server-generated correlation ID. It is not an
// identity or authorization proof, and is never read from the public headers.
func ForwardHeaders(ctx context.Context, target http.Header) {
	if id := ID(ctx); id != "" {
		target.Set(Header, id)
	}
	if JSONEnabled(ctx) {
		target.Set(EnvelopeHeader, RequestIDEnvelope)
	}
}

// AdoptForwarded may ONLY be called after authenticating the internal Relay
// certificate, current node generation and route. Invalid/multiple IDs leave
// the new local ID intact, including when talking to an older Relay.
func AdoptForwarded(w http.ResponseWriter, r *http.Request) {
	values := r.Header.Values(Header)
	value, ok := r.Context().Value(contextKey{}).(*attempt)
	if !ok || len(values) != 1 || !validID.MatchString(values[0]) {
		return
	}
	value.id.Store(values[0])
	w.Header().Set(Header, values[0])
}

func safeMethod(method string) string {
	switch method {
	case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT", "TRACE":
		return method
	default:
		return "OTHER"
	}
}

// Preserve streaming, deadlines and WebSocket upgrades without buffering or
// inspecting bodies. Unwrap also lets ResponseController reach the transport.
type writer struct {
	http.ResponseWriter
	attempt  *attempt
	status   int
	hijacked bool
}

func (w *writer) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *writer) setID()                      { w.Header().Set(Header, w.attempt.id.Load().(string)) }

func (w *writer) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.setID()
	w.ResponseWriter.WriteHeader(status)
	if status == http.StatusSwitchingProtocols || status >= 200 {
		w.status = status
	}
}

func (w *writer) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(data)
}

func (w *writer) Flush() { _ = w.FlushError() }
func (w *writer) FlushError() error {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return http.NewResponseController(w.ResponseWriter).Flush()
}

func (w *writer) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	w.setID()
	connection, buffered, err := http.NewResponseController(w.ResponseWriter).Hijack()
	if err == nil {
		w.hijacked = true
	}
	return connection, buffered, err
}
