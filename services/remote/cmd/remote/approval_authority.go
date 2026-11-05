package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"os"

	"tokenworks-mobile/services/remote/internal/canonical"
)

const approvalAuthorityFormat = "tokenworks-mobile-approval-authority/v1"

type approvalPublicAuthority struct {
	Issuer     string          `json:"issuer"`
	KeyID      string          `json:"key_id"`
	PublicJWK  json.RawMessage `json:"public_jwk"`
	KeyJKT     string          `json:"key_jkt"`
	Active     bool            `json:"active"`
	ValidFrom  string          `json:"valid_from"`
	ValidUntil string          `json:"valid_until"`
}
type approvalPublicPolicy struct {
	Version int64  `json:"version"`
	Profile string `json:"assurance_profile"`
	MaxAge  int64  `json:"max_auth_age_seconds"`
}
type approvalAuthorityDescription struct {
	Format    string                  `json:"format"`
	Authority approvalPublicAuthority `json:"authority"`
	Policy    approvalPublicPolicy    `json:"policy"`
	SHA256    string                  `json:"sha256,omitempty"`
}

// Read-only offline operator command. The output is public metadata, never a
// service signing proof or evidence that a node has loaded this configuration.
func runApprovalAuthorityCommand(args []string, output io.Writer) error {
	if len(args) == 0 || args[0] != "describe" {
		return errApprovalConfiguration
	}
	flags := flag.NewFlagSet("approval-authority describe", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	path := flags.String("policy-file", os.Getenv("REMOTE_APPROVAL_POLICY_FILE"), "protected approval deployment file")
	if flags.Parse(args[1:]) != nil || flags.NArg() != 0 || *path == "" {
		return errApprovalConfiguration
	}
	config, _, err := readApprovalDeployment(*path, os.Getenv("REMOTE_ISSUER"), csv(os.Getenv("REMOTE_CLIENT_IDS")))
	if err != nil {
		return errApprovalConfiguration
	}
	signer, err := approvalDeploymentSigner(*path, config, false)
	if err != nil {
		return errApprovalConfiguration
	}
	anchor := signer.Authority()
	document := approvalAuthorityDescription{Format: approvalAuthorityFormat,
		Authority: approvalPublicAuthority{Issuer: anchor.Issuer, KeyID: anchor.KeyID, PublicJWK: anchor.PublicJWK, KeyJKT: anchor.JKT, Active: anchor.Active,
			ValidFrom: anchor.ValidFrom.Format("2006-01-02T15:04:05.000Z"), ValidUntil: anchor.ValidUntil.Format("2006-01-02T15:04:05.000Z")},
		Policy: approvalPublicPolicy{Version: config.Policy.Version, Profile: config.Policy.Profile, MaxAge: *config.Policy.MaxAge}}
	raw, err := json.Marshal(document)
	if err != nil {
		return errApprovalConfiguration
	}
	canonicalBody, err := canonical.Object(raw)
	if err != nil {
		return errApprovalConfiguration
	}
	digest := sha256.Sum256(canonicalBody)
	document.SHA256 = hex.EncodeToString(digest[:])
	if json.NewEncoder(output).Encode(document) != nil {
		return errors.New("remote public approval authority output failed")
	}
	return nil
}
