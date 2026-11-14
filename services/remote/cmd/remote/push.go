package main

import (
	"context"
	"encoding/base64"
	"errors"
	"strings"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/push"
	"tokenworks-mobile/services/remote/internal/store"
)

// REMOTE_PUSH_CONFIG_FILE is optional. Its protected JSON object contains
// deployment_sha256, service_account_file and token_encryption_key_file. The
// latter protected file holds a base64-encoded, independently generated 32-byte
// AES key. Relative credential paths resolve beside the configuration file.
// No configuration means authenticated push routes truthfully return 501.
func loadPush(path string, database *store.Postgres, recovery *store.RecoveryGuard) (*push.Service, error) {
	if path == "" { return nil, nil }
	invalid := errors.New("remote push configuration is invalid or unavailable")
	body, err := readProtectedFile(path, 16384)
	if err != nil { return nil, invalid }
	defer clear(body)
	var config struct {
		DeploymentSHA256 string `json:"deployment_sha256"`
		ServiceAccountFile string `json:"service_account_file"`
		TokenEncryptionKeyFile string `json:"token_encryption_key_file"`
	}
	if strictFileJSON(body, &config) != nil || config.ServiceAccountFile == "" || config.TokenEncryptionKeyFile == "" { return nil, invalid }
	credentials, err := readProtectedFile(relativeFile(path, config.ServiceAccountFile), 65536)
	if err != nil { return nil, invalid }
	defer clear(credentials)
	if auth.ValidateJSONObject(credentials) != nil { return nil, invalid }
	provider, err := push.NewFCM(credentials)
	if err != nil { return nil, invalid }
	encodedKey, err := readProtectedFile(relativeFile(path, config.TokenEncryptionKeyFile), 128)
	if err != nil { return nil, invalid }
	defer clear(encodedKey)
	key, err := base64.StdEncoding.Strict().DecodeString(strings.TrimSpace(string(encodedKey)))
	if err != nil { return nil, invalid }
	defer clear(key)
	tokenCipher, err := push.NewTokenCipher(key)
	if err != nil { return nil, invalid }
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var ready bool
	if database.DB.QueryRowContext(ctx, `select to_regclass('public.push_registrations') is not null and to_regclass('public.push_deliveries') is not null`).Scan(&ready) != nil || !ready { return nil, invalid }
	service, err := push.New(recovery.PushRepository(), config.DeploymentSHA256, tokenCipher, provider)
	if err != nil { return nil, invalid }
	return service, nil
}
