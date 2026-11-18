package httpapi

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
)

const exampleShareID = "AAAAAAAAAAAAAAAAAAAAAA"

type shareRepositoryFixture struct{ blob []byte }

func (*shareRepositoryFixture) Create(context.Context, store.UserIdentity, string, string, time.Time, []byte) (store.SessionShareCreated, error) {
	panic("unauthenticated request reached creation storage")
}

func (fixture *shareRepositoryFixture) Read(_ context.Context, id string) ([]byte, error) {
	if id != exampleShareID || fixture.blob == nil {
		return nil, store.ErrSessionShareNotFound
	}
	return fixture.blob, nil
}

func TestSessionShareRoutesRequireOptInAndPublicationRequiresDPoP(t *testing.T) {
	identity, err := auth.New(auth.Config{Issuer: "https://issuer.example", Audience: "remote", JWKSURL: "https://issuer.example/jwks",
		AllowedClients: []string{"phone"}, RequiredScopes: []string{"remote"}, PublicOrigin: "https://remote.example", MaxTokenLifetime: time.Hour})
	if err != nil {
		t.Fatal(err)
	}
	base := "/share/" + exampleShareID
	request := func(handler http.Handler, method, path string) *httptest.ResponseRecorder {
		t.Helper()
		response := httptest.NewRecorder()
		r := httptest.NewRequest(method, path, nil)
		r.Header.Set("X-TokenWorks-Share-Confirmed", "v1")
		r.Header.Set("Content-Type", "application/octet-stream")
		handler.ServeHTTP(response, r)
		return response
	}
	off := New(&store.Postgres{}, identity)
	if r := request(off, "GET", base); r.Code != http.StatusNotFound {
		t.Fatalf("reader was mounted without configuration: %d", r.Code)
	}
	fixture := &shareRepositoryFixture{blob: append([]byte{1}, bytes.Repeat([]byte{0x9f}, 28)...)}
	on := New(&store.Postgres{}, identity, Config{SessionShares: fixture})
	if r := request(on, "POST", "/v1/session-shares"); r.Code != http.StatusUnauthorized {
		t.Fatalf("confirmation without proof reached publication: %d", r.Code)
	}
	if r := request(on, "GET", base); r.Code != http.StatusOK || !strings.Contains(r.Body.String(), "/share-reader/v1.js") ||
		!strings.Contains(r.Header().Get("Content-Security-Policy"), "frame-ancestors 'none'") || r.Header().Get("Referrer-Policy") != "no-referrer" {
		t.Fatalf("reader did not isolate the browser document: %d", r.Code)
	}
	if r := request(on, "GET", base+"/ciphertext"); r.Code != http.StatusOK || !bytes.Equal(r.Body.Bytes(), fixture.blob) ||
		r.Header().Get("Cache-Control") != "no-store" || r.Header().Get("Content-Type") != "application/octet-stream" {
		t.Fatalf("read path did not return only the uncached ciphertext: %d", r.Code)
	}
	if r := request(on, "DELETE", base+"/ciphertext"); r.Code != http.StatusMethodNotAllowed {
		t.Fatalf("reader permitted write methods: %d", r.Code)
	}
	fixture.blob = nil
	if r := request(on, "GET", base+"/ciphertext"); r.Code != http.StatusNotFound {
		t.Fatalf("reader returned an expired/missing share: %d", r.Code)
	}
}

func TestSessionShareCreationRejectsAbsentOrDuplicateConfirmation(t *testing.T) {
	handler := &sessionShareHTTP{repository: &shareRepositoryFixture{}}
	for _, values := range [][]string{nil, {"false"}, {"v1", "v1"}} {
		r := httptest.NewRequest("POST", "/v1/session-shares", nil)
		r.Header.Set("Content-Type", "application/octet-stream")
		for _, value := range values {
			r.Header.Add("X-TokenWorks-Share-Confirmed", value)
		}
		response := httptest.NewRecorder()
		handler.create(response, r)
		if response.Code != http.StatusBadRequest {
			t.Fatalf("creation accepted invalid confirmation %v: %d", values, response.Code)
		}
	}
}
