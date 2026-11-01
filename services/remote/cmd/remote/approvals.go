package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"path/filepath"
	"slices"
	"time"

	"dsh-mobile-ustc/services/remote/internal/approval"
	"dsh-mobile-ustc/services/remote/internal/auth"
	"dsh-mobile-ustc/services/remote/internal/relay"
	"dsh-mobile-ustc/services/remote/internal/store"
)

var errApprovalConfiguration = errors.New("remote service approval policy, issuer mapping or protected signing key configuration is invalid")

type approvalDeployment struct {
	Policy struct {
		Version int64  `json:"version"`
		Profile string `json:"assurance_profile"`
		MaxAge  *int64 `json:"max_auth_age_seconds"`
	} `json:"policy"`
	Mappings []auth.AssuranceMapping `json:"mappings"`
	Signing  struct {
		Issuer      string `json:"issuer"`
		KeyID       string `json:"key_id"`
		PrivateFile string `json:"private_key_file"`
		ValidFrom   string `json:"valid_from"`
		ValidUntil  string `json:"valid_until"`
	} `json:"signing"`
}

func loadApprovalConfig(path string, database *store.Postgres, issuer string, allowedClients []string) (*relay.ApprovalConfig, error) {
	if path == "" {
		return nil, nil
	}
	raw, err := readProtectedFile(path, 512<<10)
	if err != nil {
		return nil, errApprovalConfiguration
	}
	defer clear(raw)
	var config approvalDeployment
	if strictFileJSON(raw, &config) != nil || config.Policy.MaxAge == nil || len(config.Mappings) == 0 {
		return nil, errApprovalConfiguration
	}
	mapped := false
	for _, mapping := range config.Mappings {
		if mapping.Issuer != issuer || !slices.Contains(allowedClients, mapping.ClientID) {
			return nil, errApprovalConfiguration
		}
		if mapping.Profile == config.Policy.Profile {
			mapped = true
		}
	}
	if !mapped {
		return nil, errApprovalConfiguration
	}
	assurance, err := auth.NewAssuranceVerifier(config.Mappings)
	if err != nil {
		return nil, errApprovalConfiguration
	}
	control, err := store.NewApprovalControl(database, store.ApprovalPolicy{Version: config.Policy.Version, AssuranceProfile: config.Policy.Profile, MaxAuthAgeSeconds: *config.Policy.MaxAge}, assurance)
	if err != nil {
		return nil, errApprovalConfiguration
	}
	from, err := time.Parse("2006-01-02T15:04:05.000Z", config.Signing.ValidFrom)
	if err != nil || from.Format("2006-01-02T15:04:05.000Z") != config.Signing.ValidFrom {
		return nil, errApprovalConfiguration
	}
	until, err := time.Parse("2006-01-02T15:04:05.000Z", config.Signing.ValidUntil)
	if err != nil || until.Format("2006-01-02T15:04:05.000Z") != config.Signing.ValidUntil || from.After(time.Now()) || !until.After(time.Now()) {
		return nil, errApprovalConfiguration
	}
	keyPath := config.Signing.PrivateFile
	if keyPath == "" {
		return nil, errApprovalConfiguration
	}
	if !filepath.IsAbs(keyPath) {
		keyPath = filepath.Join(filepath.Dir(path), keyPath)
	}
	key, err := readApprovalKey(keyPath)
	if err != nil {
		return nil, errApprovalConfiguration
	}
	defer key.D.SetInt64(0)
	signer, err := approval.NewAuthorizationSigner(config.Signing.Issuer, config.Signing.KeyID, key, from, until)
	if err != nil {
		return nil, errApprovalConfiguration
	}
	provider, err := store.NewApprovalLeaseProvider(control, signer)
	if err != nil {
		return nil, errApprovalConfiguration
	}
	return &relay.ApprovalConfig{Control: control, Leases: provider}, nil
}

func readApprovalKey(path string) (*ecdsa.PrivateKey, error) {
	raw, err := readProtectedFile(path, 16<<10)
	defer clear(raw)
	if err != nil {
		return nil, errApprovalConfiguration
	}
	block, rest := pem.Decode(raw)
	if block == nil || len(bytes.TrimSpace(rest)) != 0 || len(block.Headers) != 0 {
		return nil, errApprovalConfiguration
	}
	defer clear(block.Bytes)
	var parsed any
	if block.Type == "PRIVATE KEY" {
		parsed, err = x509.ParsePKCS8PrivateKey(block.Bytes)
	} else if block.Type == "EC PRIVATE KEY" {
		parsed, err = x509.ParseECPrivateKey(block.Bytes)
	} else {
		return nil, errApprovalConfiguration
	}
	if err != nil {
		return nil, errApprovalConfiguration
	}
	key, ok := parsed.(*ecdsa.PrivateKey)
	if !ok || key.D == nil {
		return nil, errApprovalConfiguration
	}
	return key, nil
}
