package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
)

// Password accounts run without an IdP. A partially configured external token
// verifier remains an error; it never falls back to password authentication.
func loadIdentity(database *store.Postgres, recovery *store.RecoveryGuard) (*auth.Authenticator, *store.PostgresProofStore, error) {
	configured := false
	for _, key := range []string{"REMOTE_ISSUER", "REMOTE_AUDIENCE", "REMOTE_JWKS_URL", "REMOTE_CLIENT_IDS", "REMOTE_REQUIRED_SCOPES"} {
		configured = configured || os.Getenv(key) != ""
	}
	if !configured {
		if os.Getenv("REMOTE_ACCOUNTS_ORIGIN") == "" {
			return nil, nil, errors.New("configure local accounts or external identity")
		}
		return nil, nil, nil
	}
	scope, err := auth.ProofScope(os.Getenv("REMOTE_ISSUER"), os.Getenv("REMOTE_AUDIENCE"), os.Getenv("REMOTE_PUBLIC_ORIGIN"))
	if err != nil {
		return nil, nil, fmt.Errorf("remote service identity configuration is invalid: %w", err)
	}
	scope, err = auth.ProofScopeInEpoch(scope, recovery.Epoch())
	if err != nil {
		return nil, nil, errors.New("remote service proof epoch is invalid")
	}
	limit := store.DefaultProofEntriesPerBucket
	if raw := strings.TrimSpace(os.Getenv("REMOTE_DPOP_ENTRIES_PER_BUCKET")); raw != "" {
		limit, err = strconv.Atoi(raw)
		if err != nil {
			return nil, nil, errors.New("remote proof-state capacity is invalid")
		}
	}
	proofs, err := store.NewPostgresProofStore(context.Background(), database.DB, scope, limit)
	if err != nil {
		return nil, nil, errors.New("remote shared proof state is unavailable or inconsistently configured")
	}
	verifier, err := auth.New(auth.Config{Issuer: os.Getenv("REMOTE_ISSUER"), Audience: os.Getenv("REMOTE_AUDIENCE"), JWKSURL: os.Getenv("REMOTE_JWKS_URL"), AllowedClients: csv(os.Getenv("REMOTE_CLIENT_IDS")), RequiredScopes: csv(os.Getenv("REMOTE_REQUIRED_SCOPES")), PublicOrigin: os.Getenv("REMOTE_PUBLIC_ORIGIN"), MaxTokenLifetime: 15 * time.Minute, Proofs: proofs, DatabaseEpoch: recovery.Epoch(), MinimumIssuedAt: recovery.TokenNotBefore()})
	if err != nil {
		return nil, nil, fmt.Errorf("remote service identity configuration is invalid: %w", err)
	}
	return verifier, proofs, nil
}
