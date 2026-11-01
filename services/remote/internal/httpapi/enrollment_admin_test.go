package httpapi

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"dsh-mobile-ustc/services/remote/internal/auth"
	"dsh-mobile-ustc/services/remote/internal/store"
)

type enrollmentAdminTestRepository struct {
	*fakeRepository
	calls                             int
	identity                          store.UserIdentity
	device, proof, enrollment, digest string
	err                               error
}

func (f *enrollmentAdminTestRepository) ReviewEnrollmentAdmin(_ context.Context, identity store.UserIdentity, device, proof, enrollment string) (store.EnrollmentAdminReview, error) {
	f.calls++
	f.identity = identity
	f.device = device
	f.proof = proof
	f.enrollment = enrollment
	return store.EnrollmentAdminReview{EnrollmentID: enrollment, ReviewSHA256: strings.Repeat("a", 64)}, f.err
}
func (f *enrollmentAdminTestRepository) ApproveEnrollmentAdmin(ctx context.Context, identity store.UserIdentity, device, proof, enrollment, digest string) (store.EnrollmentConfirmation, error) {
	_, err := f.ReviewEnrollmentAdmin(ctx, identity, device, proof, enrollment)
	f.digest = digest
	return store.EnrollmentConfirmation{EnrollmentID: enrollment, State: "approved"}, err
}

func TestEnrollmentAdminHTTPRequiresPhoneDPoPAndExactReview(t *testing.T) {
	issuerKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	proofKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	proofJWK := httpProofJWK(proofKey)
	proofJKT, err := auth.JWKThumbprint(proofJWK)
	if err != nil {
		t.Fatal(err)
	}
	keys := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []any{httpTestRSAJWK(issuerKey)}})
	}))
	defer keys.Close()
	now := time.Now().UTC().Truncate(time.Second)
	verifier, err := auth.New(auth.Config{Issuer: "https://id.example.edu", Audience: "urn:dsh-remote", JWKSURL: keys.URL, AllowedClients: []string{"mobile-client", "host-client"}, RequiredScopes: []string{"remote.access"}, PublicOrigin: "https://api.example.edu", MaxTokenLifetime: 15 * time.Minute, Clock: func() time.Time { return now }})
	if err != nil {
		t.Fatal(err)
	}
	token := func(client string) string {
		return httpSignCompact(t, map[string]any{"typ": "at+jwt", "alg": "RS256", "kid": "issuer-key"}, map[string]any{
			"iss": "https://id.example.edu", "sub": "fixture-admin", "aud": "urn:dsh-remote", "client_id": client, "jti": "admin-access-" + client, "iat": now.Unix(), "exp": now.Add(5 * time.Minute).Unix(), "scope": "remote.access", "cnf": map[string]any{"jkt": proofJKT}}, issuerKey)
	}
	access, hostAccess := token("mobile-client"), token("host-client")
	const id = "66666666-6666-4666-8666-666666666666"
	const device = "44444444-4444-4444-8444-444444444444"
	repo := &enrollmentAdminTestRepository{fakeRepository: &fakeRepository{activeDeviceID: device}}
	handler := New(repo, verifier, Config{HostClientIDs: []string{"host-client"}})
	base := "https://api.example.edu/v1/enrollments/" + id
	for _, method := range []string{"GET", "POST"} {
		suffix := "/admin-review"
		if method == "POST" {
			suffix = "/admin-approve"
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, httptest.NewRequest(method, base+suffix, nil))
		if w.Code != 401 || repo.calls != 0 {
			t.Fatal("anonymous request reached administrator operation")
		}
	}
	nonce := ""
	sequence := 0
	send := func(client, method, path string, body ...any) *httptest.ResponseRecorder {
		sequence++
		jti := "enrollment-admin-proof-" + strings.Repeat("x", sequence)
		w := sendDPoPRequest(t, handler, client, proofKey, proofJWK, method, path, jti, nonce, body...)
		nonce = w.Header().Get("DPoP-Nonce")
		return w
	}
	if w := send(access, "GET", base+"/admin-review"); w.Code != 401 || nonce == "" || repo.calls != 0 {
		t.Fatal("missing DPoP challenge")
	}
	if w := send(access, "GET", base+"/admin-review"); w.Code != 200 || repo.calls != 1 || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("protected administrator review failed")
	}
	if repo.identity != (store.UserIdentity{Issuer: "https://id.example.edu", Subject: "fixture-admin"}) || repo.device != device || repo.proof != proofJKT || repo.enrollment != id {
		t.Fatal("review trusted caller-selected authority")
	}
	for _, body := range []any{
		map[string]any{"Review_SHA256": strings.Repeat("a", 64)}, map[string]any{"review_sha256": nil},
		map[string]any{"review_sha256": strings.Repeat("a", 64), "role": "admin"},
		map[string]any{"review_sha256": strings.Repeat("A", 64)}, map[string]any{},
	} {
		if w := send(access, "POST", base+"/admin-approve", body); w.Code != 400 || repo.calls != 1 {
			t.Fatal("invalid approval reached repository", w.Code)
		}
	}
	if w := send(access, "GET", base+"/admin-review?user_id=attacker"); w.Code != 400 || repo.calls != 1 {
		t.Fatal("administrator query selector accepted")
	}
	repo.err = store.ErrAccessDenied
	if w := send(access, "POST", base+"/admin-approve", map[string]any{"review_sha256": strings.Repeat("a", 64)}); w.Code != 403 {
		t.Fatal("repository role denial was bypassed")
	}
	repo.err = nil
	if w := send(access, "POST", base+"/admin-approve", map[string]any{"review_sha256": strings.Repeat("a", 64)}); w.Code != 200 || repo.digest != strings.Repeat("a", 64) {
		t.Fatal("exact administrator approval failed")
	}
	calls := repo.calls
	if w := send(hostAccess, "POST", base+"/admin-approve", map[string]any{"review_sha256": strings.Repeat("a", 64)}); w.Code != 403 || repo.calls != calls {
		t.Fatal("Host client acted as administrator phone", w.Code)
	}
	repo.activeDeviceErr = store.ErrDeviceNotFound
	if w := send(access, "GET", base+"/admin-review"); w.Code != 403 || repo.calls != calls {
		t.Fatal("revoked device reached administrator review")
	}
}
