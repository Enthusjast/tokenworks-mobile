// Generate one independent Go/JOSE decision vector. Private keys never leave memory.
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"os"

	jose "github.com/go-jose/go-jose/v4"
	"tokenworks-mobile/services/remote/internal/approval"
	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/canonical"
)

func main() {
	raw, err := io.ReadAll(io.LimitReader(os.Stdin, 16385))
	if err != nil || len(raw) > 16384 {
		os.Exit(1)
	}
	payload, err := canonical.Object(raw)
	if err != nil {
		os.Exit(1)
	}
	var decision approval.Decision
	if json.Unmarshal(payload, &decision) != nil {
		os.Exit(1)
	}
	pair, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		os.Exit(1)
	}
	public := map[string]string{"kty": "EC", "crv": "P-256", "x": base64.RawURLEncoding.EncodeToString(pair.X.FillBytes(make([]byte, 32))), "y": base64.RawURLEncoding.EncodeToString(pair.Y.FillBytes(make([]byte, 32)))}
	publicJSON, err := json.Marshal(public)
	if err != nil {
		os.Exit(1)
	}
	jkt, err := auth.PublicP256JWKThumbprintJSON(publicJSON)
	if err != nil {
		os.Exit(1)
	}
	options := (&jose.SignerOptions{}).WithType(jose.ContentType(approval.JWSType)).WithHeader(jose.HeaderKey("kid"), approval.KeyID(decision.DeviceID, decision.KeyVersion))
	signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.ES256, Key: pair}, options)
	if err != nil {
		os.Exit(1)
	}
	object, err := signer.Sign(payload)
	if err != nil {
		os.Exit(1)
	}
	compact, err := object.CompactSerialize()
	if err != nil {
		os.Exit(1)
	}
	registered := map[string]any{"user_id": decision.UserID, "device_id": decision.DeviceID, "key_version": decision.KeyVersion, "approval_key_jkt": jkt, "public_jwk": public, "active": true}
	if json.NewEncoder(os.Stdout).Encode(map[string]any{"compact": compact, "registered": registered}) != nil {
		os.Exit(1)
	}
}
