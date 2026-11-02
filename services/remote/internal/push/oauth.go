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
	"io"
	"net/http"
	"net/mail"
	"net/url"
	"strings"
	"sync"
	"time"

	"dsh-mobile-ustc/services/remote/internal/auth"
)

const googleTokenURL = "https://oauth2.googleapis.com/token"
const messagingScope = "https://www.googleapis.com/auth/firebase.messaging"

type AccessTokens interface {
	Token(context.Context) (string, error)
}
type ServiceAccountTokens struct {
	email, kid, endpoint string
	key                  *rsa.PrivateKey
	client               *http.Client
	mu                   sync.Mutex
	token                string
	issued, expires      time.Time
	generation           uint64
	refresh              *oauthRefresh
}

type oauthRefresh struct {
	ctx        context.Context
	cancel     context.CancelFunc
	done       chan struct{}
	started    time.Time
	generation uint64
	available  bool // guarded by ServiceAccountTokens.mu
}

func (*ServiceAccountTokens) String() string   { return "FCMServiceAccountTokens" }
func (*ServiceAccountTokens) GoString() string { return "FCMServiceAccountTokens" }

func secureClient(source *http.Client) *http.Client {
	client := &http.Client{}
	if source != nil {
		*client = *source
	}
	client.Jar = nil
	client.Timeout = 10 * time.Second
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return client
}

// The private JSON is loaded from a protected deployment file, never from a phone or environment value.
func NewServiceAccountTokens(raw []byte, client *http.Client) (*ServiceAccountTokens, error) {
	if len(raw) == 0 || len(raw) > 64<<10 || auth.ValidateJSONObject(raw) != nil {
		return nil, ErrInvalid
	}
	var source struct {
		Type     string `json:"type"`
		Email    string `json:"client_email"`
		Kid      string `json:"private_key_id"`
		Key      string `json:"private_key"`
		TokenURI string `json:"token_uri"`
	}
	if json.Unmarshal(raw, &source) != nil || source.Type != "service_account" || source.TokenURI != googleTokenURL || len(source.Email) > 320 || len(source.Kid) < 1 || len(source.Kid) > 128 {
		return nil, ErrInvalid
	}
	email, err := mail.ParseAddress(source.Email)
	if err != nil || email.Address != source.Email || !strings.HasSuffix(source.Email, ".gserviceaccount.com") {
		return nil, ErrInvalid
	}
	for _, char := range source.Kid {
		if char < '!' || char > '~' {
			return nil, ErrInvalid
		}
	}
	block, rest := pem.Decode([]byte(source.Key))
	if block == nil || block.Type != "PRIVATE KEY" || len(bytes.TrimSpace(rest)) != 0 {
		return nil, ErrInvalid
	}
	defer clear(block.Bytes)
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, ErrInvalid
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok || key.N.BitLen() < 2048 || key.N.BitLen() > 8192 || key.Validate() != nil {
		return nil, ErrInvalid
	}
	return &ServiceAccountTokens{email: source.Email, kid: source.Kid, key: key, endpoint: googleTokenURL, client: secureClient(client)}, nil
}

func (source *ServiceAccountTokens) cached(now time.Time) bool {
	return source.token != "" && now.UnixNano() >= source.issued.UnixNano() && source.expires.Sub(now) > time.Minute
}

func (source *ServiceAccountTokens) Token(ctx context.Context) (string, error) {
	if ctx.Err() != nil {
		return "", ErrUnavailable
	}
	source.mu.Lock()
	if ctx.Err() != nil {
		source.mu.Unlock()
		return "", ErrUnavailable
	}
	if source.cached(time.Now()) {
		token := source.token
		source.mu.Unlock()
		return token, nil
	}
	refresh := source.refresh
	start := refresh == nil
	if start {
		// The first caller owns the shared request lifetime. Cancellation never
		// detaches provider I/O from its originating delivery/shutdown context.
		refreshCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		refresh = &oauthRefresh{ctx: refreshCtx, cancel: cancel, done: make(chan struct{}), started: time.Now(), generation: source.generation}
		source.refresh = refresh
	}
	source.mu.Unlock()
	if start {
		go source.refreshToken(refresh)
	}
	// Every caller, including the initiator, can leave independently. Keep the
	// one refresh slot owned until provider I/O and body cleanup actually settle.
	select {
	case <-ctx.Done():
		return "", ErrUnavailable
	case <-refresh.ctx.Done():
	case <-refresh.done:
	}
	source.mu.Lock()
	defer source.mu.Unlock()
	if ctx.Err() != nil || !refresh.available || refresh.generation != source.generation || !source.cached(time.Now()) {
		return "", ErrUnavailable
	}
	return source.token, nil
}

func (source *ServiceAccountTokens) refreshToken(refresh *oauthRefresh) {
	defer refresh.cancel()
	token, ttl, err := source.fetchToken(refresh.ctx, refresh.started)
	source.mu.Lock()
	defer source.mu.Unlock()
	if err == nil && refresh.ctx.Err() == nil && refresh.generation == source.generation {
		source.token = token
		source.issued = refresh.started
		source.expires = refresh.started.Add(ttl)
		refresh.available = true
	}
	source.refresh = nil
	close(refresh.done)
}

func (source *ServiceAccountTokens) fetchToken(ctx context.Context, now time.Time) (string, time.Duration, error) {
	if ctx.Err() != nil {
		return "", 0, ErrUnavailable
	}
	header, _ := json.Marshal(map[string]string{"alg": "RS256", "typ": "JWT", "kid": source.kid})
	claims, _ := json.Marshal(map[string]any{"iss": source.email, "scope": messagingScope, "aud": googleTokenURL, "iat": now.Unix(), "exp": now.Add(time.Hour).Unix()})
	unsigned := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(claims)
	digest := sha256.Sum256([]byte(unsigned))
	signature, err := rsa.SignPKCS1v15(rand.Reader, source.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", 0, ErrUnavailable
	}
	assertion := unsigned + "." + base64.RawURLEncoding.EncodeToString(signature)
	request, err := http.NewRequestWithContext(ctx, "POST", source.endpoint, strings.NewReader(url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {assertion}}.Encode()))
	if err != nil {
		return "", 0, ErrUnavailable
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cache-Control", "no-store")
	response, err := source.client.Do(request)
	if err != nil {
		return "", 0, ErrUnavailable
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, (16<<10)+1))
	defer clear(body)
	if err != nil || response.StatusCode != 200 || len(body) > 16<<10 || auth.ValidateJSONObject(body) != nil {
		return "", 0, ErrUnavailable
	}
	var result struct {
		Token   string `json:"access_token"`
		Type    string `json:"token_type"`
		Seconds int64  `json:"expires_in"`
	}
	if json.Unmarshal(body, &result) != nil || result.Type != "Bearer" || result.Seconds < 120 || result.Seconds > 3600 || len(result.Token) < 1 || len(result.Token) > 8192 {
		return "", 0, ErrUnavailable
	}
	for _, char := range result.Token {
		if char < '!' || char > '~' {
			return "", 0, ErrUnavailable
		}
	}
	// The initial wall time plus monotonic deadline bounds even a slow response or clock rollback.
	if time.Now().UnixNano() < now.UnixNano() || time.Since(now) >= time.Duration(result.Seconds)*time.Second-time.Minute {
		return "", 0, ErrUnavailable
	}
	return result.Token, time.Duration(result.Seconds) * time.Second, nil
}
func (source *ServiceAccountTokens) Invalidate(token string) {
	source.mu.Lock()
	var cancel context.CancelFunc
	if token != "" && source.token == token {
		source.token = ""
		source.generation++
		if source.refresh != nil {
			cancel = source.refresh.cancel
		}
	}
	source.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}
