package requestlog

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

type logBuffer struct {
	sync.Mutex
	bytes.Buffer
}

func (b *logBuffer) Write(p []byte) (int, error) {
	b.Lock()
	defer b.Unlock()
	return b.Buffer.Write(p)
}

func (b *logBuffer) records(t *testing.T) []map[string]any {
	t.Helper()
	b.Lock()
	defer b.Unlock()
	var result []map[string]any
	for line := range strings.SplitSeq(strings.TrimSpace(b.Buffer.String()), "\n") {
		if line == "" {
			continue
		}
		var record map[string]any
		if err := json.Unmarshal([]byte(line), &record); err != nil {
			t.Fatal(err)
		}
		result = append(result, record)
	}
	return result
}

func TestCorrelationPrivacyAndIndependentAttempts(t *testing.T) {
	var logs logBuffer
	logger := slog.New(slog.NewJSONHandler(&logs, nil))
	var seen []string
	handler := Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, ID(r.Context()))
		// Downstream code must not accidentally replace the response's ID.
		w.Header().Set(Header, "PRIVATE_RESPONSE_HEADER")
		w.WriteHeader(http.StatusAccepted)
		_, _ = io.WriteString(w, `{"ok":true,"data":{"request_id":"PRIVATE_COMMAND_ID"}}`)
	}), "public", logger)
	for range 2 {
		r := httptest.NewRequest("PRIVATE_METHOD", "/v1/PRIVATE_PATH?token=PRIVATE_TOKEN", strings.NewReader("PRIVATE_BODY"))
		r.Header.Set(Header, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
		r.Header.Set("Authorization", "DPoP PRIVATE_AUTHORIZATION")
		r.Header.Set("DPoP", "PRIVATE_PROOF")
		r.Header.Set("User-Agent", "PRIVATE_AGENT")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != http.StatusAccepted || !validID.MatchString(w.Header().Get(Header)) ||
			w.Header().Get(Header) != seen[len(seen)-1] || w.Header().Get(Header) == r.Header.Get(Header) ||
			w.Body.String() != `{"ok":true,"data":{"request_id":"PRIVATE_COMMAND_ID"}}` {
			t.Fatal("correlation changed the response or accepted an external ID")
		}
	}
	if seen[0] == seen[1] || ID(context.Background()) != "" {
		t.Fatal("HTTP attempts reused a correlation ID")
	}
	records := logs.records(t)
	if len(records) != 2 {
		t.Fatal("missing access records")
	}
	for index, record := range records {
		if len(record) != 10 || record["msg"] != "http_request" || record["request_id"] != seen[index] ||
			record["listener"] != "public" || record["method"] != "OTHER" || record["status"] != float64(202) ||
			record["hijacked"] != false || record["completed"] != true || record["duration_ms"].(float64) < 0 {
			t.Fatal("unexpected access record fields")
		}
		raw, _ := json.Marshal(record)
		if bytes.Contains(raw, []byte("PRIVATE_")) {
			t.Fatal("access record disclosed request or response data")
		}
	}
}

func TestCorrelationProbeAndPanicSemantics(t *testing.T) {
	var logs logBuffer
	logger := slog.New(slog.NewJSONHandler(&logs, nil))
	for _, test := range []struct {
		path   string
		status int
	}{{"/healthz", 204}, {"/readyz", 204}, {"/readyz", 503}} {
		w := httptest.NewRecorder()
		Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(test.status)
		}), "public", logger).ServeHTTP(w, httptest.NewRequest("GET", test.path, nil))
		if !validID.MatchString(w.Header().Get(Header)) {
			t.Fatal("probe response has no correlation ID")
		}
	}
	func() {
		defer func() {
			if recover() != http.ErrAbortHandler {
				t.Fatal("middleware swallowed or replaced handler panic")
			}
		}()
		Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
			panic(http.ErrAbortHandler)
		}), "public", logger).ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/panic", nil))
	}()
	records := logs.records(t)
	if len(records) != 2 || records[0]["status"] != float64(503) || records[1]["status"] != float64(0) || records[1]["completed"] != false {
		t.Fatal("successful probes were logged, or panic was reported as a completed HTTP 200")
	}
}

func TestCorrelationForwardsOnlyGeneratedAndValidatedIDs(t *testing.T) {
	const trusted = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	for _, values := range [][]string{nil, {trusted}, {trusted, trusted}, {trusted + ", " + trusted}, {"PRIVATE_ID"}, {strings.ToUpper(trusted)}} {
		r := httptest.NewRequest("GET", "/internal/relay/v1/http", nil)
		r.Header[http.CanonicalHeaderKey(Header)] = values
		w := httptest.NewRecorder()
		Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			initial := ID(r.Context())
			headers := http.Header{}
			ForwardHeaders(r.Context(), headers)
			if headers.Get(Header) != initial || initial == trusted {
				t.Fatal("forwarded caller-selected ID before trust check")
			}
			AdoptForwarded(w, r) // Models the call after the Relay trust check.
			if (ID(r.Context()) == trusted) != (len(values) == 1 && values[0] == trusted) {
				t.Fatal("adopted malformed or ambiguous internal ID")
			}
			w.WriteHeader(204)
			if w.Header().Get(Header) != ID(r.Context()) {
				t.Fatal("context and response IDs differ")
			}
		}), "relay", nil).ServeHTTP(w, r)
	}
}

func TestCorrelationPreservesStreamingAndTransportControls(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	release := make(chan struct{})
	done := make(chan error, 1)
	server := httptest.NewServer(Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		controller := http.NewResponseController(w)
		if err := controller.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
			done <- err
			return
		}
		w.WriteHeader(http.StatusEarlyHints)
		_, _ = io.WriteString(w, "first\n")
		if err := controller.Flush(); err != nil {
			done <- err
			return
		}
		select {
		case <-release:
		case <-r.Context().Done():
		}
		_, err := io.WriteString(w, "second\n")
		done <- err
	}), "public", nil))
	defer server.Close()
	var released sync.Once
	allowSecond := func() { released.Do(func() { close(release) }) }
	defer allowSecond()
	r, _ := http.NewRequestWithContext(ctx, "GET", server.URL, nil)
	response, err := server.Client().Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	first := make([]byte, len("first\n"))
	if _, err := io.ReadFull(response.Body, first); err != nil || string(first) != "first\n" || response.StatusCode != 200 || !validID.MatchString(response.Header.Get(Header)) {
		t.Fatal("stream was buffered, had the wrong final status, or lost its ID", err)
	}
	allowSecond()
	second, err := io.ReadAll(response.Body)
	if err != nil || string(second) != "second\n" {
		t.Fatal("stream continuation failed", err)
	}
	if err := <-done; err != nil {
		t.Fatal("transport control failed", err)
	}
}

func TestCorrelationPreservesWebSocketUpgrade(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	var logs logBuffer
	done := make(chan struct{})
	handler := Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		connection, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Error(err)
			return
		}
		defer connection.CloseNow()
		kind, body, err := connection.Read(ctx)
		if err == nil {
			err = connection.Write(ctx, kind, body)
		}
		if err != nil {
			t.Error(err)
		}
	}), "public", slog.New(slog.NewJSONHandler(&logs, nil)))
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(done)
		handler.ServeHTTP(w, r)
	}))
	defer server.Close()
	connection, reply, err := websocket.Dial(ctx, server.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.CloseNow()
	if reply.StatusCode != 101 || !validID.MatchString(reply.Header.Get(Header)) {
		t.Fatal("upgrade response lost its correlation ID")
	}
	if err := connection.Write(ctx, websocket.MessageText, []byte("PRIVATE_SOCKET_BODY")); err != nil {
		t.Fatal(err)
	}
	_, body, err := connection.Read(ctx)
	if err != nil || string(body) != "PRIVATE_SOCKET_BODY" {
		t.Fatal("upgraded connection failed", err)
	}
	<-done
	records := logs.records(t)
	if len(records) != 1 || records[0]["status"] != float64(101) || records[0]["hijacked"] != true || records[0]["request_id"] != reply.Header.Get(Header) {
		t.Fatal("upgrade access record is incorrect")
	}
}
