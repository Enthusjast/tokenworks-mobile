package push

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type oauthResult struct {
	token string
	err   error
}

func callOAuth(source *ServiceAccountTokens, ctx context.Context) <-chan oauthResult {
	result := make(chan oauthResult, 1)
	go func() { token, err := source.Token(ctx); result <- oauthResult{token, err} }()
	return result
}

func receiveOAuth(t *testing.T, result <-chan oauthResult) oauthResult {
	t.Helper()
	select {
	case value := <-result:
		return value
	case <-time.After(time.Second):
		t.Fatal("OAuth caller remained blocked on another request")
		return oauthResult{}
	}
}

func waitOAuth(t *testing.T, event <-chan struct{}) {
	t.Helper()
	select {
	case <-event:
	case <-time.After(time.Second):
		t.Fatal("OAuth fixture did not reach the controlled boundary")
	}
}

func unavailableOAuth(t *testing.T, result <-chan oauthResult) {
	t.Helper()
	value := receiveOAuth(t, result)
	if value.token != "" || !errors.Is(value.err, ErrUnavailable) {
		t.Fatal("retired or failed OAuth caller received credentials or private error detail")
	}
}

// Observe the actual wait boundary, without sleep-based scheduling assumptions.
type observedOAuthContext struct {
	context.Context
	waiting, entered    chan struct{}
	waitOnce, enterOnce sync.Once
}

func (ctx *observedOAuthContext) Done() <-chan struct{} {
	if ctx.waiting != nil {
		ctx.waitOnce.Do(func() { close(ctx.waiting) })
	}
	return ctx.Context.Done()
}
func (ctx *observedOAuthContext) Err() error {
	err := ctx.Context.Err()
	if ctx.entered != nil {
		ctx.enterOnce.Do(func() { close(ctx.entered) })
	}
	return err
}

func TestOAuthCancelledWaiterLeavesSharedTLSRefreshAvailableToOtherCallers(t *testing.T) {
	source, _ := account(t)
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	var calls atomic.Int32
	peer := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if calls.Add(1) == 1 {
			close(entered)
			<-release
		}
		_, _ = io.WriteString(w, `{"access_token":"PRIVATE_SHARED_TOKEN","token_type":"Bearer","expires_in":3600}`)
	}))
	defer peer.Close()
	defer once.Do(func() { close(release) })
	source.endpoint, source.client = peer.URL, secureClient(peer.Client())
	leader := callOAuth(source, t.Context())
	waitOAuth(t, entered)
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	observed := &observedOAuthContext{Context: ctx, waiting: make(chan struct{})}
	waiter := callOAuth(source, observed)
	waitOAuth(t, observed.waiting)
	cancel()
	unavailableOAuth(t, waiter)
	if calls.Load() != 1 {
		t.Fatal("cancelled waiter started a separate provider request")
	}
	once.Do(func() { close(release) })
	if value := receiveOAuth(t, leader); value.err != nil || value.token != "PRIVATE_SHARED_TOKEN" {
		t.Fatal("independent waiter cancellation retired the shared refresh")
	}
	if token, err := source.Token(t.Context()); err != nil || token != "PRIVATE_SHARED_TOKEN" || calls.Load() != 1 {
		t.Fatal("successful shared refresh was not cached")
	}
}

func TestOAuthConcurrentWaitersShareOneFailedAttemptAndRetryOnlyOnLaterCall(t *testing.T) {
	source, _ := account(t)
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	var calls atomic.Int32
	peer := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if calls.Add(1) == 1 {
			close(entered)
			<-release
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = io.WriteString(w, "PRIVATE_PROVIDER_FAILURE")
			return
		}
		_, _ = io.WriteString(w, `{"access_token":"PRIVATE_RECOVERED_TOKEN","token_type":"Bearer","expires_in":3600}`)
	}))
	defer peer.Close()
	defer once.Do(func() { close(release) })
	source.endpoint, source.client = peer.URL, secureClient(peer.Client())
	results := []<-chan oauthResult{callOAuth(source, t.Context())}
	waitOAuth(t, entered)
	for range 4 {
		ctx := &observedOAuthContext{Context: t.Context(), waiting: make(chan struct{})}
		results = append(results, callOAuth(source, ctx))
		waitOAuth(t, ctx.waiting)
	}
	once.Do(func() { close(release) })
	for _, result := range results {
		unavailableOAuth(t, result)
	}
	if calls.Load() != 1 {
		t.Fatal("one failed refresh cascaded into implicit provider retries")
	}
	if token, err := source.Token(t.Context()); err != nil || token != "PRIVATE_RECOVERED_TOKEN" || calls.Load() != 2 {
		t.Fatal("a later independent call could not recover after failed refresh")
	}
}

type oauthRoundTrip func(*http.Request) (*http.Response, error)

func (transport oauthRoundTrip) RoundTrip(request *http.Request) (*http.Response, error) {
	return transport(request)
}

func TestOAuthRetiredRefreshCannotPublishLateCredentialsOrReleaseCapacityEarly(t *testing.T) {
	for _, retirement := range []string{"initiator-cancelled", "matching-token-invalidated"} {
		t.Run(retirement, func(t *testing.T) {
			source, _ := account(t)
			source.token, source.issued, source.expires = "PRIVATE_OLD_TOKEN", time.Now().Add(-2*time.Hour), time.Now().Add(-time.Hour)
			entered, release := make(chan struct{}), make(chan struct{})
			var once sync.Once
			defer once.Do(func() { close(release) })
			var calls atomic.Int32
			source.client = secureClient(&http.Client{Transport: oauthRoundTrip(func(*http.Request) (*http.Response, error) {
				token := "PRIVATE_RECOVERED_TOKEN"
				if calls.Add(1) == 1 {
					close(entered)
					<-release // deliberately ignores cancellation until the transport settles
					token = "PRIVATE_LATE_TOKEN"
				}
				return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(
					`{"access_token":"` + token + `","token_type":"Bearer","expires_in":3600}`))}, nil
			})})
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			first := callOAuth(source, ctx)
			waitOAuth(t, entered)
			source.mu.Lock()
			refresh := source.refresh
			source.mu.Unlock()
			if retirement == "initiator-cancelled" {
				cancel()
			} else {
				invalidated := make(chan struct{})
				go func() { source.Invalidate("PRIVATE_OLD_TOKEN"); close(invalidated) }()
				waitOAuth(t, invalidated)
			}
			unavailableOAuth(t, first)
			unavailableOAuth(t, callOAuth(source, t.Context()))
			source.mu.Lock()
			owned := source.refresh == refresh
			source.mu.Unlock()
			if !owned || calls.Load() != 1 {
				t.Fatal("retired unsettled refresh lost its capacity slot")
			}
			once.Do(func() { close(release) })
			waitOAuth(t, refresh.done)
			source.mu.Lock()
			late := source.token == "PRIVATE_LATE_TOKEN"
			source.mu.Unlock()
			if late {
				t.Fatal("retired transport response repopulated credentials")
			}
			if token, err := source.Token(t.Context()); err != nil || token != "PRIVATE_RECOVERED_TOKEN" || calls.Load() != 2 {
				t.Fatal("settled retired refresh prevented a new independent attempt")
			}
		})
	}
}

func TestOAuthCancellationWhileAcquiringStateCannotReturnCachedCredentials(t *testing.T) {
	source, _ := account(t)
	source.token, source.issued, source.expires = "PRIVATE_CACHED_TOKEN", time.Now(), time.Now().Add(time.Hour)
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	observed := &observedOAuthContext{Context: ctx, entered: make(chan struct{})}
	var result <-chan oauthResult
	func() {
		source.mu.Lock()
		defer source.mu.Unlock()
		result = callOAuth(source, observed)
		waitOAuth(t, observed.entered)
		cancel()
	}()
	unavailableOAuth(t, result)
}
