package push

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

// FCM sends data-only HTTP v1 messages. Credentials, tokens and provider response
// bodies are never returned in errors (and must never enter request logs).
type FCM struct {
	email string
	keyID string
	key *rsa.PrivateKey
	tokenURL string
	sendURL string
	client *http.Client
	mu sync.Mutex
	accessToken string
	tokenExpires time.Time
}

func NewFCM(serviceAccount []byte) (*FCM, error) {
	var account struct {
		Type string `json:"type"`
		ProjectID string `json:"project_id"`
		PrivateKeyID string `json:"private_key_id"`
		PrivateKey string `json:"private_key"`
		ClientEmail string `json:"client_email"`
		TokenURI string `json:"token_uri"`
	}
	if json.Unmarshal(serviceAccount, &account) != nil || account.Type != "service_account" ||
		!regexp.MustCompile(`^[a-z][a-z0-9-]{4,61}[a-z0-9]$`).MatchString(account.ProjectID) ||
		!strings.HasSuffix(account.ClientEmail, ".iam.gserviceaccount.com") || strings.ContainsAny(account.ClientEmail, "\r\n ") ||
		account.PrivateKeyID == "" || len(account.PrivateKeyID) > 256 || account.TokenURI != "https://oauth2.googleapis.com/token" {
		return nil, ErrInvalid
	}
	block, rest := pem.Decode([]byte(account.PrivateKey))
	if block == nil || len(bytes.TrimSpace(rest)) != 0 { return nil, ErrInvalid }
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil { return nil, ErrInvalid }
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok || key.N.BitLen() < 2048 || key.Validate() != nil { return nil, ErrInvalid }
	return &FCM{email: account.ClientEmail, keyID: account.PrivateKeyID, key: key,
		tokenURL: account.TokenURI, sendURL: "https://fcm.googleapis.com/v1/projects/"+account.ProjectID+"/messages:send",
		client: &http.Client{Timeout: 15*time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}

func encodedJSON(value any) string {
	body, _ := json.Marshal(value)
	return base64.RawURLEncoding.EncodeToString(body)
}

func (provider *FCM) access(ctx context.Context) (string, error) {
	provider.mu.Lock()
	defer provider.mu.Unlock()
	now := time.Now()
	if provider.accessToken != "" && provider.tokenExpires.After(now.Add(time.Minute)) { return provider.accessToken, nil }
	header := encodedJSON(map[string]string{"alg": "RS256", "typ": "JWT", "kid": provider.keyID})
	claims := encodedJSON(map[string]any{"iss": provider.email, "scope": "https://www.googleapis.com/auth/firebase.messaging", "aud": provider.tokenURL, "iat": now.Unix(), "exp": now.Add(time.Hour).Unix()})
	input := header+"."+claims
	digest := sha256.Sum256([]byte(input))
	signature, err := rsa.SignPKCS1v15(rand.Reader, provider.key, crypto.SHA256, digest[:])
	if err != nil { return "", ErrUnavailable }
	form := url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {input+"."+base64.RawURLEncoding.EncodeToString(signature)}}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, provider.tokenURL, strings.NewReader(form.Encode()))
	if err != nil { return "", ErrUnavailable }
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := provider.client.Do(request)
	if err != nil { return "", ErrUnavailable }
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 65537))
	if err != nil || len(body) > 65536 || response.StatusCode != http.StatusOK { return "", ErrUnavailable }
	var result struct {
		AccessToken string `json:"access_token"`
		TokenType string `json:"token_type"`
		ExpiresIn int64 `json:"expires_in"`
	}
	if json.Unmarshal(body, &result) != nil || result.AccessToken == "" || len(result.AccessToken) > 16384 ||
		strings.IndexFunc(result.AccessToken, func(r rune) bool { return r < 33 || r > 126 }) >= 0 ||
		!strings.EqualFold(result.TokenType, "Bearer") || result.ExpiresIn <= 60 || result.ExpiresIn > 3600 { return "", ErrUnavailable }
	provider.accessToken = result.AccessToken
	provider.tokenExpires = now.Add(time.Duration(result.ExpiresIn)*time.Second)
	return provider.accessToken, nil
}

func (provider *FCM) Send(ctx context.Context, token string, reference Reference) error {
	if _, err := reference.Data(time.Now()); err != nil { return err }
	access, err := provider.access(ctx)
	if err != nil { return err }
	// OAuth acquisition can take seconds: expiry is enforced again at actual send.
	data, err := reference.Data(time.Now())
	if err != nil { return err }
	ttl := int64(time.Until(reference.Emit.ExpiresAt).Seconds())
	if ttl < 0 { return ErrInvalid }
	body, _ := json.Marshal(map[string]any{"message": map[string]any{"token": token, "data": data,
		"android": map[string]string{"priority": "HIGH", "ttl": fmt.Sprintf("%ds", ttl)}}})
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, provider.sendURL, bytes.NewReader(body))
	if err != nil { return ErrUnavailable }
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+access)
	response, err := provider.client.Do(request)
	if err != nil { return ErrUnavailable }
	defer response.Body.Close()
	result, err := io.ReadAll(io.LimitReader(response.Body, 65537))
	if err != nil || len(result) > 65536 { return ErrUnavailable }
	if response.StatusCode == http.StatusOK { return nil }
	if response.StatusCode == http.StatusUnauthorized {
		provider.mu.Lock()
		if provider.accessToken == access { provider.accessToken = "" }
		provider.mu.Unlock()
	}
	var failure struct { Error struct { Details []struct { Type string `json:"@type"`; Code string `json:"errorCode"` } `json:"details"` } `json:"error"` }
	if json.Unmarshal(result, &failure) == nil {
		for _, detail := range failure.Error.Details {
			if detail.Type == "type.googleapis.com/google.firebase.fcm.v1.FcmError" && detail.Code == "UNREGISTERED" { return ErrUnregistered }
		}
	}
	return ErrUnavailable
}
