package store

import (
	"bytes"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/testsupport"
)

func TestSessionSharesDurableCiphertextQuotaAndExpiration(t *testing.T) {
	ctx, db := testsupport.PostgresDatabase(t)
	const user = "11111111-1111-4111-8111-111111111111"
	const device = "22222222-2222-4222-8222-222222222222"
	proof := strings.Repeat("A", 43)
	identity := UserIdentity{Issuer: "https://share.example", Subject: "publisher"}
	postgres := &Postgres{DB: db}
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := db.ExecContext(ctx, query, args...); err != nil {
			t.Fatal(err)
		}
	}
	migration, err := os.ReadFile("../../../../deploy/sql/session_shares.sql")
	if err != nil {
		t.Fatal(err)
	}
	exec(string(migration))
	exec(`insert into users(id,issuer,subject) values($1,$2,$3)`, user, identity.Issuer, identity.Subject)
	exec(`insert into devices(id,user_id,name,approval_public_jwk,approval_key_thumbprint,proof_public_jwk,proof_key_thumbprint)
		values($1,$2,'Share phone','{}',$3,'{}',$4)`, device, user, strings.Repeat("B", 43), proof)
	publisher, err := NewSessionShares(ctx, postgres, OriginalDatabaseEpoch)
	if err != nil {
		t.Fatal(err)
	}
	// A distinct service instance uses the same database, not this node's memory.
	reader, err := NewSessionShares(ctx, postgres, OriginalDatabaseEpoch)
	if err != nil {
		t.Fatal(err)
	}
	blob := append([]byte{1}, bytes.Repeat([]byte{0xb1}, SessionShareMinBytes-1)...)
	until := time.Now().Add(5 * time.Minute)
	if _, err := publisher.Create(ctx, identity, device, strings.Repeat("C", 43), until, blob); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("accepted a mismatched phone proof", err)
	}
	if _, err := publisher.Create(ctx, identity, device, proof, time.Now().Add(-time.Minute), blob); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("accepted an expired access token", err)
	}
	for range 5 {
		created, err := publisher.Create(ctx, identity, device, proof, until, blob)
		if err != nil || !ValidSessionShareID(created.ID) || time.Until(created.ExpiresAt) > 15*time.Minute || time.Until(created.ExpiresAt) < 14*time.Minute {
			t.Fatal("share did not get a bounded expiration", err)
		}
		if got, err := reader.Read(ctx, created.ID); err != nil || !bytes.Equal(got, blob) {
			t.Fatal("another node did not receive the same ciphertext", err)
		}
	}
	if _, err := publisher.Create(ctx, identity, device, proof, until, blob); !errors.Is(err, ErrSessionShareQuota) {
		t.Fatal("publisher exceeded active share quota", err)
	}
	exec(`update session_shares set expires_at=clock_timestamp()-interval '1 second'`)
	created, err := publisher.Create(ctx, identity, device, proof, until, blob)
	if err != nil {
		t.Fatal("expired rows retained quota", err)
	}
	exec(`update session_shares set expires_at=clock_timestamp()-interval '1 second'`)
	if _, err := reader.Read(ctx, created.ID); !errors.Is(err, ErrSessionShareNotFound) {
		t.Fatal("expired ciphertext remained readable before cleanup", err)
	}
	exec(`update devices set revoked_at=clock_timestamp() where id=$1`, device)
	if _, err := publisher.Create(ctx, identity, device, proof, until, blob); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("revoked phone could still create links", err)
	}
}
