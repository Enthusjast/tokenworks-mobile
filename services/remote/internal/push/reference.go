package push

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

var ErrInvalid = errors.New("push: invalid reference or registration")
var ErrDenied = errors.New("push: access denied")
var ErrUnavailable = errors.New("push: unavailable")
var ErrUnregistered = errors.New("push: provider token unregistered")
var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
var digest = regexp.MustCompile(`^[0-9a-f]{64}$`)

type Owner struct {
	AccountID string `json:"account_id"`
	OrganizationID string `json:"organization_id"`
	DeploymentSHA256 string `json:"deployment_sha256"`
	UserID string `json:"user_id"`
	DeviceID string `json:"device_id"`
}

// Fingerprint matches LocalDataOwner's canonical sorted JSON document.
func (owner Owner) Fingerprint() string {
	body, _ := json.Marshal(map[string]string{"account_id": owner.AccountID, "organization_id": owner.OrganizationID,
		"deployment_sha256": owner.DeploymentSHA256, "user_id": owner.UserID, "device_id": owner.DeviceID})
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

func (owner Owner) Valid() bool {
	return uuid.MatchString(owner.AccountID) && uuid.MatchString(owner.OrganizationID) && digest.MatchString(owner.DeploymentSHA256) && uuid.MatchString(owner.UserID) && uuid.MatchString(owner.DeviceID)
}

type Registration struct {
	Provider string `json:"provider"`
	Token string `json:"token"`
	AccountID string `json:"account_id"`
	OrganizationID string `json:"organization_id"`
	DeploymentSHA256 string `json:"deployment_sha256"`
	OwnerFingerprint string `json:"owner_fingerprint"`
}

func (registration Registration) Valid(deployment string) bool {
	return registration.Provider == "fcm" && len(registration.Token) > 0 && len(registration.Token) <= 4096 &&
		strings.IndexFunc(registration.Token, func(r rune) bool { return r < 33 || r > 126 }) < 0 &&
		uuid.MatchString(registration.AccountID) && uuid.MatchString(registration.OrganizationID) &&
		registration.DeploymentSHA256 == deployment && digest.MatchString(deployment) && digest.MatchString(registration.OwnerFingerprint)
}

type Emit struct {
	NotificationID string `json:"notification_id"`
	RuntimeID string `json:"runtime_id"`
	SessionID string `json:"session_id"`
	Kind string `json:"kind"`
	ExpiresAt time.Time `json:"expires_at"`
}

func (emit Emit) Valid(now time.Time) bool {
	return uuid.MatchString(emit.NotificationID) && uuid.MatchString(emit.RuntimeID) && utf8.ValidString(emit.SessionID) &&
		len(emit.SessionID) > 0 && utf8.RuneCountInString(emit.SessionID) <= 256 &&
		strings.IndexFunc(emit.SessionID, func(r rune) bool { return r < 33 || r == 127 }) < 0 &&
		(emit.Kind == "interaction" || emit.Kind == "completed") && emit.ExpiresAt.After(now) && !emit.ExpiresAt.After(now.Add(15*time.Minute))
}

type Reference struct {
	BindingID string
	Owner Owner
	Emit Emit
}

func (reference Reference) Data(now time.Time) (map[string]string, error) {
	if !uuid.MatchString(reference.BindingID) || !reference.Owner.Valid() || !reference.Emit.Valid(now) {
		return nil, ErrInvalid
	}
	return map[string]string{"v": "1", "binding_id": reference.BindingID, "notification_id": reference.Emit.NotificationID,
		"account_id": reference.Owner.AccountID, "organization_id": reference.Owner.OrganizationID,
		"deployment_sha256": reference.Owner.DeploymentSHA256, "user_id": reference.Owner.UserID, "device_id": reference.Owner.DeviceID,
		"owner_fingerprint": reference.Owner.Fingerprint(), "runtime_id": reference.Emit.RuntimeID, "session_id": reference.Emit.SessionID,
		"kind": reference.Emit.Kind, "expires_at": reference.Emit.ExpiresAt.UTC().Format(time.RFC3339Nano)}, nil
}

type TokenCipher struct { aead cipher.AEAD }

func NewTokenCipher(key []byte) (*TokenCipher, error) {
	if len(key) != 32 { return nil, ErrInvalid }
	block, err := aes.NewCipher(key)
	if err != nil { return nil, ErrInvalid }
	aead, err := cipher.NewGCM(block)
	if err != nil { return nil, ErrInvalid }
	return &TokenCipher{aead: aead}, nil
}

func (tokenCipher *TokenCipher) Encrypt(token, binding, fingerprint string) ([]byte, error) {
	nonce := make([]byte, tokenCipher.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil { return nil, ErrUnavailable }
	return tokenCipher.aead.Seal(nonce, nonce, []byte(token), []byte(binding+"\x00"+fingerprint)), nil
}

func (tokenCipher *TokenCipher) Decrypt(ciphertext []byte, binding, fingerprint string) ([]byte, error) {
	n := tokenCipher.aead.NonceSize()
	if len(ciphertext) < n+tokenCipher.aead.Overhead() { return nil, ErrInvalid }
	cleartext, err := tokenCipher.aead.Open(nil, ciphertext[:n], ciphertext[n:], []byte(binding+"\x00"+fingerprint))
	if err != nil { return nil, ErrInvalid }
	return cleartext, nil
}
