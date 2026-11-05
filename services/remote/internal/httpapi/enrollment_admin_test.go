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

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
)

type enrollmentAdminTestRepository struct {
	*fakeRepository
	calls                             int
	identity                          store.UserIdentity
	device, proof, enrollment, digest string
	organization, cursor              string
	limit                             int
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

func (f *enrollmentAdminTestRepository) ListEnrollmentApprovals(ctx context.Context, identity store.UserIdentity, device, proof, organization string, limit int, cursor string) (store.EnrollmentDirectory, error) {
	_, err := f.ReviewEnrollmentAdmin(ctx, identity, device, proof, "")
	f.organization = organization
	f.limit = limit
	f.cursor = cursor
	return store.EnrollmentDirectory{OrganizationID: organization, Items: []store.EnrollmentSummary{}}, err
}
func (f *enrollmentAdminTestRepository) DenyEnrollmentAdmin(ctx context.Context, identity store.UserIdentity, device, proof, enrollment, digest string) (store.EnrollmentAdminDenial, error) {
	_, err := f.ReviewEnrollmentAdmin(ctx, identity, device, proof, enrollment)
	f.digest = digest
	return store.EnrollmentAdminDenial{EnrollmentID: enrollment, State: "denied"}, err
}

func (f *enrollmentAdminTestRepository) ObserveEnrollmentTerminal(ctx context.Context, identity store.UserIdentity, key, request string) (store.EnrollmentTerminal, error) {
	_, err := f.ReviewEnrollmentAdmin(ctx, identity, "", key, request)
	return store.EnrollmentTerminal{Version: 1, RequestID: request, State: "expired"}, err
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
	verifier, err := auth.New(auth.Config{Issuer: "https://id.example.edu", Audience: "urn:tokenworks-mobile", JWKSURL: keys.URL, AllowedClients: []string{"mobile-client", "host-client"}, RequiredScopes: []string{"remote.access"}, PublicOrigin: "https://api.example.edu", MaxTokenLifetime: 15 * time.Minute, Clock: func() time.Time { return now }})
	if err != nil {
		t.Fatal(err)
	}
	token := func(client string) string {
		return httpSignCompact(t, map[string]any{"typ": "at+jwt", "alg": "RS256", "kid": "issuer-key"}, map[string]any{
			"iss": "https://id.example.edu", "sub": "fixture-admin", "aud": "urn:tokenworks-mobile", "client_id": client, "jti": "admin-access-" + client, "iat": now.Unix(), "exp": now.Add(5 * time.Minute).Unix(), "scope": "remote.access", "cnf": map[string]any{"jkt": proofJKT}}, issuerKey)
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
	repo.activeDeviceErr = nil
	collection := "https://api.example.edu/v1/enrollments"
	organization := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	for _, query := range []string{"", "?organization_id=", "?organization_id=" + organization + "&organization_id=" + organization, "?organization_id=" + organization + "&owner_user_id=attacker", "?organization_id=" + organization + "&limit=01", "?organization_id=" + organization + "&cursor=a&cursor=b"} {
		if w := send(access, "GET", collection+query); w.Code != 400 || repo.calls != calls {
			t.Fatal("invalid administrator directory selector reached repository", w.Code)
		}
	}
	if w := send(access, "GET", collection+"?organization_id="+organization+"&limit=1&cursor=locator"); w.Code != 200 || repo.organization != organization || repo.limit != 1 || repo.cursor != "locator" || repo.device != device || repo.proof != proofJKT {
		t.Fatal("directory did not bind current phone and organization")
	}
	calls = repo.calls
	for _, body := range []any{map[string]any{"review_sha256": nil}, map[string]any{"review_sha256": strings.Repeat("a", 64), "reason": "PRIVATE_REASON"}, map[string]any{"Review_SHA256": strings.Repeat("a", 64)}} {
		if w := send(access, "POST", base+"/admin-deny", body); w.Code != 400 || repo.calls != calls {
			t.Fatal("invalid administrator denial reached repository")
		}
	}
	if w := send(access, "POST", base+"/admin-deny", map[string]any{"review_sha256": strings.Repeat("a", 64)}); w.Code != 200 || repo.digest != strings.Repeat("a", 64) {
		t.Fatal("exact administrator denial failed")
	}
	calls = repo.calls
	if w := send(hostAccess, "GET", collection+"?organization_id="+organization); w.Code != 403 || repo.calls != calls {
		t.Fatal("Host client listed administrator requests")
	}
	if w := send(hostAccess, "POST", base+"/admin-deny", map[string]any{"review_sha256": strings.Repeat("a", 64)}); w.Code != 403 || repo.calls != calls {
		t.Fatal("Host client denied an enrollment")
	}
	repo.err = store.ErrAccessDenied
	if w := send(access, "GET", collection+"?organization_id="+organization); w.Code != 403 {
		t.Fatal("repository directory authorization denial was ignored")
	}
	if w := send(access, "POST", base+"/admin-deny", map[string]any{"review_sha256": strings.Repeat("a", 64)}); w.Code != 403 {
		t.Fatal("repository denial authorization was ignored")
	}
	repo.err = nil
	calls = repo.calls
	terminal := "https://api.example.edu/v1/enrollments/host-terminal"
	if w := send(access, "POST", terminal, map[string]any{"request_id": id}); w.Code != 403 || repo.calls != calls {
		t.Fatal("phone client performed Host terminal observation")
	}
	for _, body := range []any{map[string]any{"request_id": id, "code": "PRIVATE_CODE"}, map[string]any{"Request_ID": id}, map[string]any{"request_id": nil}} {
		if w := send(hostAccess, "POST", terminal, body); w.Code != 400 || repo.calls != calls {
			t.Fatal("invalid Host terminal body reached repository")
		}
	}
	repo.activeDeviceErr = store.ErrDeviceNotFound
	if w := send(hostAccess, "POST", terminal, map[string]any{"request_id": id}); w.Code != 200 || repo.proof != proofJKT || repo.enrollment != id || repo.device != "" {
		t.Fatal("Host terminal endpoint failed installation-bound authority")
	}
	calls = repo.calls
	if w := send(hostAccess, "POST", terminal+"?runtime_id=attacker", map[string]any{"request_id": id}); w.Code != 400 || repo.calls != calls {
		t.Fatal("Host terminal selector was accepted")
	}
}
