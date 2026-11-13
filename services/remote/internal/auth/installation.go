package auth

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"net/http"
	"strings"
	"time"
)

// InstallationDPoP verifies a standard DPoP proof presented with the opaque
// installation credential on a Host tunnel or lease-introspection request.
// It returns the rotated server nonce, which the caller echoes as DPoP-Nonce.
func (auth *Authenticator) InstallationDPoP(request *http.Request, credential string, publicJWK []byte, proof string) (string, error) {
	if len(proof) == 0 || len(proof) > maxDPoPBytes || credential == "" {
		return "", errInvalidProof
	}
	registered, err := decodeObject(publicJWK)
	if err != nil {
		return "", errInvalidProof
	}
	registeredThumbprint, err := JWKThumbprint(registered)
	if err != nil {
		return "", errInvalidProof
	}
	header, claims, signingInput, signature, err := parseCompactJWS(proof)
	if err != nil || !strings.EqualFold(stringClaim(header, "typ"), "dpop+jwt") || stringClaim(header, "alg") != "ES256" ||
		header["crit"] != nil || header["jku"] != nil || header["x5u"] != nil {
		return "", errInvalidProof
	}
	jwk, ok := header["jwk"].(map[string]any)
	if !ok {
		return "", errInvalidProof
	}
	key, err := parsePublicJWK(jwk)
	if err != nil || !keyMatchesAlgorithm(key.key, "ES256") || verifySignature(key.key, "ES256", signingInput, signature) != nil {
		return "", errInvalidProof
	}
	thumbprint, err := JWKThumbprint(jwk)
	if err != nil || subtle.ConstantTimeCompare([]byte(thumbprint), []byte(registeredThumbprint)) != 1 {
		return "", errInvalidProof
	}
	if stringClaim(claims, "htm") != request.Method || !sameDPoPURI(stringClaim(claims, "htu"), auth.requestURI(request)) {
		return "", errInvalidProof
	}
	accessHash := sha256.Sum256([]byte(credential))
	if subtle.ConstantTimeCompare([]byte(stringClaim(claims, "ath")), []byte(base64.RawURLEncoding.EncodeToString(accessHash[:]))) != 1 {
		return "", errInvalidProof
	}
	jti := stringClaim(claims, "jti")
	if len(jti) < 16 || len(jti) > 256 {
		return "", errInvalidProof
	}
	issuedAt, ok := numericDate(claims["iat"])
	now := auth.clock()
	if !ok || issuedAt.Before(now.Add(-auth.clockSkew)) || issuedAt.After(now.Add(auth.clockSkew)) {
		return "", errInvalidProof
	}
	nonce := stringClaim(claims, "nonce")
	if len(nonce) > 256 {
		return "", errInvalidProof
	}
	checked, err := auth.proofs.ConsumeAndRotate(request.Context(), thumbprint, jti, nonce, now.Add(5*time.Minute))
	if err != nil || !ValidProofThumbprint(checked.Nonce) {
		return "", ErrProofStateUnavailable
	}
	if !checked.Accepted {
		return checked.Nonce, errInvalidProof
	}
	return checked.Nonce, nil
}
