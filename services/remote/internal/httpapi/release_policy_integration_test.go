package httpapi

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/releases"
	"tokenworks-mobile/services/remote/internal/store"
	"tokenworks-mobile/services/remote/internal/testsupport"
)

func TestAppReleaseTwoHTTPSNodesFenceOldCatalogAfterWithdrawal(t *testing.T) {
	ctx, db := testsupport.PostgresDatabase(t)
	postgres := &store.Postgres{DB: db}
	issuer := "https://id.example.edu"
	_, err := db.ExecContext(ctx, `insert into orgs(id,slug,name) values('11111111-1111-4111-8111-111111111111','release-fixture','Release fixture');
 insert into users(id,issuer,subject) values('22222222-2222-4222-8222-222222222222','https://id.example.edu','release-user');
 insert into org_memberships(org_id,user_id,role) values('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','member')`)
	if err != nil {
		t.Fatal(err)
	}
	accessKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	proofKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	approvalKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	proofJWK, approvalJWK := httpProofJWK(proofKey), httpProofJWK(approvalKey)
	proofJKT, _ := auth.JWKThumbprint(proofJWK)
	approvalJKT, _ := auth.JWKThumbprint(approvalJWK)
	proofRaw, _ := json.Marshal(proofJWK)
	approvalRaw, _ := json.Marshal(approvalJWK)
	if _, err = postgres.RegisterDevice(ctx, store.DeviceRegistration{Issuer: issuer, Subject: "release-user", Name: "Fixture phone", ApprovalPublicJWK: approvalRaw, ApprovalKeyThumbprint: approvalJKT, ProofPublicJWK: proofRaw, ProofKeyThumbprint: proofJKT}); err != nil {
		t.Fatal(err)
	}
	jwks := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []any{httpTestRSAJWK(accessKey)}})
	}))
	defer jwks.Close()
	now := time.Now().UTC().Truncate(time.Second)
	access := httpSignCompact(t, map[string]any{"typ": "at+jwt", "alg": "RS256", "kid": "issuer-key"}, map[string]any{
		"iss": issuer, "sub": "release-user", "aud": "urn:release-test", "client_id": "fixture-phone", "jti": "fixture-release-access", "iat": now.Unix(), "exp": now.Add(5 * time.Minute).Unix(), "scope": "remote.access", "cnf": map[string]any{"jkt": proofJKT}}, accessKey)
	raw, err := os.ReadFile("../../../../deploy/android/releases.example.json")
	if err != nil {
		t.Fatal(err)
	}
	catalog, err := releases.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	ref, _ := catalog.Policy()
	if err = postgres.RegisterAppReleaseCatalog(ctx, store.OriginalDatabaseEpoch, catalog); err != nil {
		t.Fatal(err)
	}
	start := func(snapshot *releases.Catalog) *httptest.Server {
		t.Helper()
		server := httptest.NewUnstartedServer(nil)
		verifier, err := auth.New(auth.Config{Issuer: issuer, Audience: "urn:release-test", JWKSURL: jwks.URL, AllowedClients: []string{"fixture-phone"}, RequiredScopes: []string{"remote.access"}, PublicOrigin: "https://" + server.Listener.Addr().String(), MaxTokenLifetime: 15 * time.Minute})
		if err != nil {
			server.Close()
			t.Fatal(err)
		}
		server.Config.Handler = New(postgres, verifier, Config{Releases: snapshot})
		server.StartTLS()
		t.Cleanup(server.Close)
		return server
	}
	first, second := start(catalog), start(catalog)
	nonces := map[*httptest.Server]string{}
	sequence := 0
	send := func(server *httptest.Server, path string) (int, string) {
		t.Helper()
		for attempt := 0; attempt < 2; attempt++ {
			sequence++
			ath := sha256.Sum256([]byte(access))
			htu := server.URL + strings.Split(path, "?")[0]
			proof := httpSignCompact(t, map[string]any{"typ": "dpop+jwt", "alg": "ES256", "jwk": proofJWK}, map[string]any{"htm": "GET", "htu": htu, "iat": time.Now().Unix(), "jti": fmt.Sprintf("fixture-release-proof-%04d", sequence), "ath": base64.RawURLEncoding.EncodeToString(ath[:]), "nonce": nonces[server]}, proofKey)
			request, err := http.NewRequestWithContext(ctx, "GET", server.URL+path, nil)
			if err != nil {
				t.Fatal(err)
			}
			request.Header.Set("Authorization", "DPoP "+access)
			request.Header.Set("DPoP", proof)
			response, err := server.Client().Do(request)
			if err != nil {
				t.Fatal(err)
			}
			body, err := io.ReadAll(io.LimitReader(response.Body, 128<<10))
			_ = response.Body.Close()
			if err != nil {
				t.Fatal(err)
			}
			nonces[server] = response.Header.Get("DPoP-Nonce")
			if response.StatusCode == 401 && attempt == 0 {
				continue
			}
			return response.StatusCode, string(body)
		}
		t.Fatal("nonce challenge never completed")
		return 0, ""
	}
	path := "/v1/app/releases/latest?organization_id=11111111-1111-4111-8111-111111111111&version_code=1&protocol_version=1&android_api=26"
	for _, server := range []*httptest.Server{first, second} {
		status, body := send(server, path)
		if status != 200 || !strings.Contains(body, `"version_code":2`) {
			t.Fatal("initial HTTPS node did not select fixture release", status)
		}
	}
	var config map[string]any
	_ = json.Unmarshal(raw, &config)
	config["publications"] = []any{}
	withdrawal, _ := json.Marshal(config)
	withdrawn, err := releases.Parse(withdrawal)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = postgres.ReplaceAppReleasePolicy(ctx, store.OriginalDatabaseEpoch, "11111111-1111-4111-8111-111111111111", ref.SHA256, withdrawn); err != nil {
		t.Fatal(err)
	}
	for _, server := range []*httptest.Server{first, second} {
		status, body := send(server, path)
		if status != 503 || !strings.Contains(body, "update.unavailable") || strings.Contains(body, "download_url") {
			t.Fatal("old HTTPS node still advertised withdrawn release", status)
		}
		if status, _ = send(server, "/v1/me"); status != 200 {
			t.Fatal("release policy change stopped unrelated identity reads", status)
		}
	}
	replacement := start(withdrawn)
	status, body := send(replacement, path)
	if status != 200 || !strings.Contains(body, `"release":null`) {
		t.Fatal("new policy did not serve withdrawal", status)
	}
}
