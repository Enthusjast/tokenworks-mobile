package main

import "testing"

func TestPasswordOnlyStartupNeedsNoSSOAndPartialOIDCDoesNotDowngrade(t *testing.T) {
	for _, key := range []string{"REMOTE_ISSUER", "REMOTE_AUDIENCE", "REMOTE_JWKS_URL", "REMOTE_CLIENT_IDS", "REMOTE_REQUIRED_SCOPES", "REMOTE_ACCOUNTS_ORIGIN"} {
		t.Setenv(key, "")
	}
	if _, _, err := loadIdentity(nil, nil); err == nil {
		t.Fatal("startup accepted no authentication method")
	}
	t.Setenv("REMOTE_ACCOUNTS_ORIGIN", "http://127.0.0.1:8080")
	identity, proofs, err := loadIdentity(nil, nil)
	if err != nil || identity != nil || proofs != nil {
		t.Fatal("password-only startup required external identity")
	}
	t.Setenv("REMOTE_ISSUER", "https://id.example")
	if _, _, err = loadIdentity(nil, nil); err == nil {
		t.Fatal("partial external identity silently disabled")
	}
}
