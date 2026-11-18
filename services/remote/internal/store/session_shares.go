package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"time"
)

const (
	SessionShareMaxBytes = 2 << 20
	SessionShareMinBytes = 1 + 12 + 16 // version, GCM nonce, authentication tag
)

var (
	ErrSessionShareNotFound = errors.New("store: session share absent or expired")
	ErrSessionShareQuota    = errors.New("store: session share quota exceeded")
)

// SessionShares is short-lived shared storage across Relay nodes. It never
// receives a transcript, transcript identity, or browser fragment key.
type SessionShares struct {
	db    *sql.DB
	epoch string
}

type SessionShareCreated struct {
	ID        string    `json:"id"`
	ExpiresAt time.Time `json:"expires_at"`
}

func NewSessionShares(ctx context.Context, postgres *Postgres, epoch string) (*SessionShares, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	rows, err := postgres.DB.QueryContext(ctx, `select id_hash, publisher_id, epoch, ciphertext, expires_at from session_shares limit 0`)
	if err != nil {
		return nil, ErrDatabaseUnavailable
	}
	_ = rows.Close()
	return &SessionShares{db: postgres.DB, epoch: epoch}, nil
}

func (shares *SessionShares) Create(ctx context.Context, identity UserIdentity, device, proof string, tokenExpires time.Time, ciphertext []byte) (SessionShareCreated, error) {
	if !grantUUIDPattern.MatchString(device) || len(ciphertext) < SessionShareMinBytes || len(ciphertext) > SessionShareMaxBytes || ciphertext[0] != 1 {
		return SessionShareCreated{}, ErrAccessDenied
	}
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	tx, err := shares.db.BeginTx(ctx, nil)
	if err != nil {
		return SessionShareCreated{}, ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	if _, err := lockRecoveryReady(ctx, tx, shares.epoch); err != nil {
		return SessionShareCreated{}, err
	}
	// Serialize the quota across the publisher's devices and independently
	// reject device/identity changes that raced the HTTP authentication.
	var publisher string
	err = tx.QueryRowContext(ctx, `select u.id::text from users u join devices d on d.user_id=u.id
		where d.id=$1 and d.proof_key_thumbprint=$2 and d.revoked_at is null
		and u.issuer=$3 and u.subject=$4 and u.status='active' and $5::timestamptz>clock_timestamp()
		for no key update of u for share of d`, device, proof, identity.Issuer, identity.Subject, tokenExpires).Scan(&publisher)
	if errors.Is(err, sql.ErrNoRows) {
		return SessionShareCreated{}, ErrAccessDenied
	}
	if err != nil {
		return SessionShareCreated{}, ErrDatabaseUnavailable
	}
	var count int
	if err := tx.QueryRowContext(ctx, `select count(*) from session_shares where publisher_id=$1 and epoch=$2 and expires_at>clock_timestamp()`, publisher, shares.epoch).Scan(&count); err != nil {
		return SessionShareCreated{}, ErrDatabaseUnavailable
	}
	if count >= 5 {
		return SessionShareCreated{}, ErrSessionShareQuota
	}
	var token [16]byte
	if _, err := rand.Read(token[:]); err != nil {
		return SessionShareCreated{}, ErrDatabaseUnavailable
	}
	id := base64.RawURLEncoding.EncodeToString(token[:])
	digest := sha256.Sum256(token[:])
	created := SessionShareCreated{ID: id}
	err = tx.QueryRowContext(ctx, `insert into session_shares(id_hash, publisher_id, epoch, ciphertext, expires_at)
		values($1,$2,$3,$4,clock_timestamp() + interval '15 minutes') returning expires_at`, digest[:], publisher, shares.epoch, ciphertext).Scan(&created.ExpiresAt)
	if err != nil || tx.Commit() != nil {
		return SessionShareCreated{}, ErrDatabaseUnavailable
	}
	return created, nil
}

func ValidSessionShareID(id string) bool {
	_, ok := sessionShareDigest(id)
	return ok
}

func sessionShareDigest(id string) ([32]byte, bool) {
	if len(id) != 22 {
		return [32]byte{}, false
	}
	var raw [16]byte
	n, err := base64.RawURLEncoding.Strict().Decode(raw[:], []byte(id))
	if err != nil || n != len(raw) {
		return [32]byte{}, false
	}
	return sha256.Sum256(raw[:]), true
}

func (shares *SessionShares) Read(ctx context.Context, id string) ([]byte, error) {
	digest, ok := sessionShareDigest(id)
	if !ok {
		return nil, ErrSessionShareNotFound
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	var ciphertext []byte
	err := shares.db.QueryRowContext(ctx, `select share.ciphertext from session_shares share
		join remote_recovery_state s on s.slot=1 and s.epoch=share.epoch and s.phase='ready'
		where share.id_hash=$1 and share.epoch=$2 and share.expires_at>clock_timestamp()`, digest[:], shares.epoch).Scan(&ciphertext)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrSessionShareNotFound
	}
	if err != nil {
		return nil, ErrDatabaseUnavailable
	}
	return ciphertext, nil
}

func (shares *SessionShares) Run(ctx context.Context) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		operation, cancel := context.WithTimeout(ctx, 3*time.Second)
		_, _ = shares.db.ExecContext(operation, `delete from session_shares where expires_at<=clock_timestamp() or epoch not in
			(select epoch from remote_recovery_state where slot=1)`)
		cancel()
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
