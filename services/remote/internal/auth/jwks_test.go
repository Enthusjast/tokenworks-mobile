package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestJWKSRejectsAmbiguousDocumentsWithoutReplacingVerifiedKeys(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	public := testRSAJWK(key, "known-key")
	encoded, err := json.Marshal(public)
	if err != nil {
		t.Fatal(err)
	}
	replacement, err := json.Marshal(testRSAJWK(key, "unknown-key"))
	if err != nil {
		t.Fatal(err)
	}
	valid := []byte(fmt.Sprintf(`{"keys":[%s]}`, encoded))
	duplicateTop := []byte(fmt.Sprintf(`{"keys":[%s],"keys":[%s]}`, encoded, replacement))
	duplicateNested := []byte(fmt.Sprintf(`{"keys":[{"k\u0074y":"RSA",%s]}`, replacement[1:]))
	invalidUTF8 := append([]byte(fmt.Sprintf(`{"keys":[%s],"name":"`, replacement)), 0xff, '"', '}')
	tests := map[string][]byte{
		"duplicate top-level keys": duplicateTop,
		"duplicate escaped member": duplicateNested,
		"invalid UTF-8":            invalidUTF8,
		"oversized body":           []byte(strings.Repeat(" ", maxJWKSBytes+1)),
		"too many keys":            []byte(`{"keys":[` + strings.TrimSuffix(strings.Repeat(string(encoded)+",", maxJWKSKeys+1), ",") + `]}`),
		"non-object key":           []byte(`{"keys":[null]}`),
	}
	for _, name := range []string{"d", "p", "q", "dp", "dq", "qi", "oth", "k", "jku", "x5u"} {
		candidate := testRSAJWK(key, "unknown-key")
		candidate[name] = nil
		bytes, err := json.Marshal(map[string]any{"keys": []any{candidate}})
		if err != nil {
			t.Fatal(err)
		}
		tests["private or remote member "+name] = bytes
	}
	for name, malformed := range tests {
		t.Run(name, func(t *testing.T) {
			var mu sync.Mutex
			body := valid
			server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
				mu.Lock()
				defer mu.Unlock()
				response.Header().Set("Cache-Control", "max-age=300")
				_, _ = response.Write(body)
			}))
			defer server.Close()
			now := time.Now()
			set, err := newKeySet(server.URL, server.Client(), func() time.Time { return now })
			if err != nil {
				t.Fatal(err)
			}
			if _, err := set.get(context.Background(), "known-key", "RS256"); err != nil {
				t.Fatalf("valid key set rejected: %v", err)
			}
			mu.Lock()
			body = malformed
			mu.Unlock()
			if _, err := set.get(context.Background(), "unknown-key", "RS256"); err == nil {
				t.Fatal("malformed refresh accepted")
			}
			if _, err := set.get(context.Background(), "known-key", "RS256"); err != nil {
				t.Fatalf("malformed refresh replaced the previous key set: %v", err)
			}
			now = now.Add(6 * time.Minute)
			if _, err := set.get(context.Background(), "known-key", "RS256"); err == nil {
				t.Fatal("expired keys were reused after a malformed refresh")
			}
		})
	}
}

func TestJWKSMustRejectAnActual2047BitSigningKey(t *testing.T) {
	keys := newTestKeys(t)
	weak, err := rsa.GenerateKey(rand.Reader, 2047)
	if err != nil {
		t.Fatal(err)
	}
	if weak.N.BitLen() != 2047 || len(weak.N.Bytes()) != 256 {
		t.Fatal("invalid weak-key fixture")
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []any{testRSAJWK(weak, "issuer-key-1")}})
	}))
	defer server.Close()
	keys.verifier.keys, err = newKeySet(server.URL, server.Client(), func() time.Time { return keys.now })
	if err != nil {
		t.Fatal(err)
	}
	compact := signCompact(t, map[string]any{"typ": "at+jwt", "alg": "RS256", "kid": "issuer-key-1"}, validAccessClaims(keys), weak)
	if _, err = keys.verifier.VerifyAccessToken(t.Context(), compact); err == nil {
		t.Fatal("2047-bit signed token accepted")
	}
}

func TestJWKSCanonicalPublicKeyMaterial(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	for name, patch := range map[string]map[string]any{
		"kid type": {"kid": 12}, "kid controls": {"kid": "bad\u0085id"}, "kid byte length": {"kid": strings.Repeat("界", 86)},
		"alg type": {"alg": false}, "alg empty": {"alg": ""}, "alg mismatch": {"alg": "ES256"},
		"leading zero n": {"n": base64.RawURLEncoding.EncodeToString(append([]byte{0}, key.N.Bytes()...))},
		"leading zero e": {"e": "AAEAAQ"}, "noncanonical bits": {"e": "Ax"}, "newline": {"e": "AQ\nAB"},
	} {
		t.Run(name, func(t *testing.T) {
			row := testRSAJWK(key, "known")
			for k, v := range patch {
				row[k] = v
			}
			if _, err := parsePublicJWK(row); err == nil {
				t.Fatal("invalid JWK accepted")
			}
		})
	}
	proof, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	bare := testProofJWK(proof)
	if _, err := parsePublicJWK(bare); err != nil {
		t.Fatal(err)
	}
	x := bare["x"].(string)
	bare["x"] = x[:4] + "\n" + x[4:]
	if _, err := parsePublicJWK(bare); err == nil {
		t.Fatal("noncanonical P-256 coordinate accepted")
	}
}

func TestJWKSZeroReuseRotationAndUnknownCooldown(t *testing.T) {
	first, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	second, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	document := func(key *ecdsa.PrivateKey, kid string) string {
		row := testProofJWK(key)
		row["kid"] = kid
		body, _ := json.Marshal(map[string]any{"keys": []any{row}})
		return string(body)
	}
	for _, control := range []string{"max-age=0", "no-cache", "no-store", "max-age=30"} {
		t.Run(control, func(t *testing.T) {
			var body atomic.Value
			body.Store(document(first, "known"))
			var requests atomic.Int64
			var fail atomic.Bool
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				if r.Header.Get("Cache-Control") != "no-cache" || r.Header.Get("Accept-Encoding") != "identity" {
					t.Error("missing explicit fetch policy")
				}
				w.Header().Set("Cache-Control", control)
				if control == "max-age=30" {
					w.Header().Set("Age", "30")
				}
				if fail.Load() {
					w.WriteHeader(http.StatusServiceUnavailable)
					return
				}
				_, _ = w.Write([]byte(body.Load().(string)))
			}))
			defer server.Close()
			now := time.Now()
			set, err := newKeySet(server.URL, server.Client(), func() time.Time { return now })
			if err != nil {
				t.Fatal(err)
			}
			get := func(kid string) (any, error) { return set.get(t.Context(), kid, "ES256") }
			if _, err := get("known"); err != nil {
				t.Fatal(err)
			}
			body.Store(document(second, "known"))
			got, err := get("known")
			if err != nil {
				t.Fatal(err)
			}
			if !got.(*ecdsa.PublicKey).Equal(&second.PublicKey) || requests.Load() != 2 {
				t.Fatal("old same-ID key reused")
			}
			fail.Store(true)
			if _, err = get("known"); err == nil {
				t.Fatal("failed refresh used expired key")
			}
			fail.Store(false)
			body.Store(document(second, "replacement"))
			if _, err = get("known"); err == nil {
				t.Fatal("removed key accepted")
			}
			baseline := requests.Load()
			for _, kid := range []string{"unknown-a", "unknown-b"} {
				if _, err = get(kid); err == nil {
					t.Fatal("unknown key accepted")
				}
			}
			if requests.Load() != baseline {
				t.Fatal("unknown identifiers bypassed cooldown")
			}
			// A previously verified ID may refresh, but never authorize using old material.
			if _, err = get("replacement"); err != nil {
				t.Fatal(err)
			}
			now = now.Add(6 * time.Second)
			if _, err = get("unknown-c"); err == nil {
				t.Fatal("unknown key accepted after cooldown")
			}
			if requests.Load() != baseline+2 {
				t.Fatal("refresh did not resume after cooldown")
			}
		})
	}
}

func TestJWKSBackwardClockDiscardsReusableKeys(t *testing.T) {
	keys := newTestKeys(t)
	now := keys.now
	keys.verifier.keys.clock = func() time.Time { return now }
	if _, err := keys.verifier.keys.get(t.Context(), "issuer-key-1", "RS256"); err != nil {
		t.Fatal(err)
	}
	now = now.Add(-time.Second)
	if _, err := keys.verifier.keys.get(t.Context(), "issuer-key-1", "RS256"); err == nil {
		t.Fatal("backward clock reused cached key")
	}
	if len(keys.verifier.keys.keys) != 0 || !keys.verifier.keys.expires.IsZero() {
		t.Fatal("cache not discarded")
	}
}
