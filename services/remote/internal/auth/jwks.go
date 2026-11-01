package auth

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

const maxJWKSBytes = 128 << 10
const maxJWKSKeys = 32
const unknownKIDRefreshCooldown = 5 * time.Second

type publicJWK struct {
	kid string
	alg string
	key crypto.PublicKey
}

type keySet struct {
	url    string
	client *http.Client
	clock  func() time.Time

	mu                  sync.Mutex
	keys                map[string]publicJWK
	expires             time.Time
	cachedAt            time.Time
	unknownRefreshAfter time.Time
}

func newKeySet(rawURL string, client *http.Client, clock func() time.Time) (*keySet, error) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("auth: invalid JWKS URL")
	}
	if u.Scheme != "https" && !isLoopbackHTTP(u) {
		return nil, errors.New("auth: JWKS URL must use HTTPS")
	}
	if client == nil {
		client = &http.Client{
			Timeout: 5 * time.Second,
			CheckRedirect: func(request *http.Request, via []*http.Request) error {
				if len(via) >= 3 || request.URL.Scheme != u.Scheme || !strings.EqualFold(request.URL.Host, u.Host) {
					return errors.New("auth: unsafe JWKS redirect")
				}
				return nil
			},
		}
	}
	if clock == nil {
		clock = time.Now
	}
	return &keySet{url: u.String(), client: client, clock: clock, keys: make(map[string]publicJWK)}, nil
}

func isLoopbackHTTP(u *url.URL) bool {
	if u.Scheme != "http" {
		return false
	}
	return u.Hostname() == "localhost" || u.Hostname() == "127.0.0.1" || u.Hostname() == "::1"
}

func (set *keySet) get(ctx context.Context, kid, alg string) (crypto.PublicKey, error) {
	if kid == "" || len(kid) > 256 || strings.IndexFunc(kid, unicode.IsControl) >= 0 || alg != "ES256" && alg != "RS256" {
		return nil, errors.New("auth: signing key not found")
	}
	set.mu.Lock()
	defer set.mu.Unlock()
	now := set.clock()
	if !set.cachedAt.IsZero() && now.Round(0).Before(set.cachedAt.Round(0)) {
		set.keys = make(map[string]publicJWK)
		set.expires = time.Time{}
		set.cachedAt = time.Time{}
		set.unknownRefreshAfter = time.Time{}
		return nil, errors.New("auth: signing key cache clock changed")
	}
	refreshed := false
	if !now.Before(set.expires) {
		// Previously verified IDs may refresh even during the unknown-ID cooldown.
		// They must still pass a new fetch; expired material never authorizes.
		if _, known := set.keys[kid]; !known && now.Before(set.unknownRefreshAfter) {
			return nil, errors.New("auth: signing key not found")
		}
		if err := set.refresh(ctx); err != nil {
			if _, known := set.keys[kid]; !known {
				set.unknownRefreshAfter = set.clock().Add(unknownKIDRefreshCooldown)
			}
			return nil, err
		}
		refreshed = true
	}
	key, ok := set.keys[kid]
	if !ok && refreshed {
		set.unknownRefreshAfter = set.clock().Add(unknownKIDRefreshCooldown)
	}
	if !ok && !refreshed {
		now := set.clock()
		if now.Before(set.unknownRefreshAfter) {
			return nil, errors.New("auth: signing key not found")
		}
		set.unknownRefreshAfter = now.Add(unknownKIDRefreshCooldown)
		if err := set.refresh(ctx); err != nil {
			return nil, err
		}
		key, ok = set.keys[kid]
	}
	if !ok || key.alg != "" && key.alg != alg || !keyMatchesAlgorithm(key.key, alg) {
		return nil, errors.New("auth: signing key not found")
	}
	return key.key, nil
}

func (set *keySet) refresh(ctx context.Context) error {
	started := set.clock()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, set.url, nil)
	if err != nil {
		return errors.New("auth: JWKS fetch failed")
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Accept-Encoding", "identity")
	request.Header.Set("Cache-Control", "no-cache")
	response, err := set.client.Do(request)
	if err != nil {
		return errors.New("auth: JWKS fetch failed")
	}
	defer response.Body.Close()
	received := set.clock()
	if response.StatusCode != http.StatusOK {
		return errors.New("auth: JWKS fetch failed")
	}
	if encoding := response.Header.Get("Content-Encoding"); encoding != "" && encoding != "identity" {
		return errors.New("auth: invalid JWKS response")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxJWKSBytes+1))
	if err != nil || len(body) > maxJWKSBytes {
		return errors.New("auth: invalid JWKS response")
	}
	if !utf8.Valid(body) {
		return errors.New("auth: invalid JWKS response")
	}
	document, err := decodeObject(body)
	if err != nil {
		return errors.New("auth: invalid JWKS response")
	}
	entries, ok := document["keys"].([]any)
	if !ok || len(entries) == 0 || len(entries) > maxJWKSKeys {
		return errors.New("auth: invalid JWKS response")
	}
	keys := make(map[string]publicJWK, len(entries))
	for _, entry := range entries {
		raw, ok := entry.(map[string]any)
		if !ok {
			return errors.New("auth: invalid JWKS key")
		}
		parsed, err := parsePublicJWK(raw)
		if err != nil {
			return errors.New("auth: invalid JWKS key")
		}
		if parsed.kid == "" {
			continue
		}
		if _, duplicate := keys[parsed.kid]; duplicate {
			return errors.New("auth: ambiguous JWKS key ID")
		}
		keys[parsed.kid] = parsed
	}
	if len(keys) == 0 {
		return errors.New("auth: no usable signing keys")
	}
	set.keys = keys
	ready := set.clock()
	set.cachedAt = ready
	// Zero remaining freshness permits only this lookup. Later lookups refresh.
	set.expires = ready.Add(jwksFreshness(response.Header, started, received, ready))
	return nil
}

func parsePublicJWK(raw map[string]any) (publicJWK, error) {
	stringClaim := func(name string) string {
		value, _ := raw[name].(string)
		return value
	}
	kid, alg := stringClaim("kid"), stringClaim("alg")
	if value, exists := raw["kid"]; exists {
		if _, ok := value.(string); !ok || len(kid) > 256 || strings.IndexFunc(kid, unicode.IsControl) >= 0 {
			return publicJWK{}, errors.New("auth: invalid JWK key ID")
		}
	}
	if value, exists := raw["alg"]; exists {
		if _, ok := value.(string); !ok || alg != "RS256" && alg != "ES256" {
			return publicJWK{}, errors.New("auth: unsupported JWK algorithm")
		}
	}
	for _, name := range []string{"d", "p", "q", "dp", "dq", "qi", "oth", "k", "jku", "x5u"} {
		if _, exists := raw[name]; exists {
			return publicJWK{}, errors.New("auth: private or remote JWK is not accepted")
		}
	}
	if rawUse, exists := raw["use"]; exists {
		usage, ok := rawUse.(string)
		if !ok || usage != "sig" {
			return publicJWK{}, errors.New("auth: non-signing JWK")
		}
	}
	if rawOperations, exists := raw["key_ops"]; exists {
		operations, ok := rawOperations.([]any)
		if !ok || len(operations) != 1 || operations[0] != "verify" {
			return publicJWK{}, errors.New("auth: JWK must be restricted to signature verification")
		}
	}
	switch stringClaim("kty") {
	case "RSA":
		nBytes, err := decodeJWKBytes(stringClaim("n"))
		if err != nil || len(nBytes) < 256 || len(nBytes) > 1024 || nBytes[0] == 0 {
			return publicJWK{}, errors.New("auth: invalid RSA modulus")
		}
		eBytes, err := decodeJWKBytes(stringClaim("e"))
		if err != nil || len(eBytes) == 0 || len(eBytes) > 4 || eBytes[0] == 0 {
			return publicJWK{}, errors.New("auth: invalid RSA exponent")
		}
		exponent := int(new(big.Int).SetBytes(eBytes).Int64())
		if exponent < 3 || exponent%2 == 0 {
			return publicJWK{}, errors.New("auth: invalid RSA exponent")
		}
		modulus := new(big.Int).SetBytes(nBytes)
		if modulus.BitLen() < 2048 || modulus.BitLen() > 8192 || modulus.Bit(0) != 1 || alg != "" && alg != "RS256" {
			return publicJWK{}, errors.New("auth: invalid RSA signing key")
		}
		return publicJWK{kid: kid, alg: alg, key: &rsa.PublicKey{N: modulus, E: exponent}}, nil
	case "EC":
		if stringClaim("crv") != "P-256" || alg != "" && alg != "ES256" {
			return publicJWK{}, errors.New("auth: unsupported elliptic curve")
		}
		x, err := decodeCoordinate(stringClaim("x"))
		if err != nil {
			return publicJWK{}, err
		}
		y, err := decodeCoordinate(stringClaim("y"))
		if err != nil || !elliptic.P256().IsOnCurve(x, y) {
			return publicJWK{}, errors.New("auth: invalid elliptic curve point")
		}
		return publicJWK{kid: kid, alg: alg, key: &ecdsa.PublicKey{Curve: elliptic.P256(), X: x, Y: y}}, nil
	default:
		return publicJWK{}, fmt.Errorf("auth: unsupported JWK key type")
	}
}

// Strict decoding also rejects CR/LF, which encoding/base64 otherwise ignores.
func decodeJWKBytes(value string) ([]byte, error) {
	decoded, err := base64.RawURLEncoding.Strict().DecodeString(value)
	if err != nil || base64.RawURLEncoding.EncodeToString(decoded) != value {
		return nil, errors.New("auth: noncanonical JWK encoding")
	}
	return decoded, nil
}

func decodeCoordinate(value string) (*big.Int, error) {
	bytes, err := decodeJWKBytes(value)
	if err != nil || len(bytes) != 32 {
		return nil, errors.New("auth: invalid elliptic curve coordinate")
	}
	return new(big.Int).SetBytes(bytes), nil
}

func containsString(values []any, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}

func keyMatchesAlgorithm(key crypto.PublicKey, algorithm string) bool {
	switch algorithm {
	case "RS256":
		_, ok := key.(*rsa.PublicKey)
		return ok
	case "ES256":
		public, ok := key.(*ecdsa.PublicKey)
		return ok && public.Curve == elliptic.P256()
	default:
		return false
	}
}

func verifySignature(key crypto.PublicKey, algorithm string, input, signature []byte) error {
	digest := sha256.Sum256(input)
	switch algorithm {
	case "RS256":
		public, ok := key.(*rsa.PublicKey)
		if !ok || rsa.VerifyPKCS1v15(public, crypto.SHA256, digest[:], signature) != nil {
			return errors.New("auth: invalid JWT signature")
		}
		return nil
	case "ES256":
		public, ok := key.(*ecdsa.PublicKey)
		if !ok || len(signature) != 64 {
			return errors.New("auth: invalid JWT signature")
		}
		r := new(big.Int).SetBytes(signature[:32])
		s := new(big.Int).SetBytes(signature[32:])
		if !ecdsa.Verify(public, digest[:], r, s) {
			return errors.New("auth: invalid JWT signature")
		}
		return nil
	default:
		return errors.New("auth: unsupported signature algorithm")
	}
}
