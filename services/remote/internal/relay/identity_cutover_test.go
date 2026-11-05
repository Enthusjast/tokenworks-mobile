package relay

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/store"
	"github.com/coder/websocket"
)

func TestTokenWorksRelayRejectsLegacySubprotocolAndAuthorization(t *testing.T) {
	for _, test := range []struct {
		name, scheme, protocol string
	}{
		{"legacy subprotocol", "TokenWorks-Mobile-Ticket", "dsh.remote.v1"},
		{"legacy authorization", "DSH-Ticket", "tokenworks-mobile.remote.v1"},
	} {
		t.Run(test.name, func(t *testing.T) {
			key := relayTestKey(t)
			publicJWK, thumbprint := relayTestPublicJWK(t, key)
			fake := &relayTestStore{ticket: store.TicketRedemption{
				DeviceID: "22222222-2222-4222-8222-222222222222", ProofKeyThumbprint: thumbprint,
				ProofPublicJWK: publicJWK, RuntimeID: "33333333-3333-4333-8333-333333333333",
			}}
			server := httptest.NewServer(New(fake))
			t.Cleanup(server.Close)
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			connection, response, err := websocket.Dial(ctx, wsURL(server.URL), &websocket.DialOptions{
				Subprotocols: []string{test.protocol},
				HTTPHeader: http.Header{"Authorization": []string{test.scheme + " " + strings.Repeat("E", 43)}},
			})
			if err == nil {
				defer connection.CloseNow()
				if _, _, err := connection.Read(ctx); err == nil || ctx.Err() != nil {
					t.Fatal("legacy identity reached the relay challenge or was not promptly rejected", err)
				}
			} else if response == nil {
				t.Fatal("relay admission failed without an HTTP response", err)
			}
			fake.mu.Lock()
			defer fake.mu.Unlock()
			if fake.ticketConsumeCalls != 0 || fake.attached.ConnectionID != "" {
				t.Fatal("legacy connection consumed authority")
			}
		})
	}
}
