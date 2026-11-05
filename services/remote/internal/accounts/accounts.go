// Package accounts owns local password identities and revocable browser sessions.
// OIDC access tokens and device proofs remain the responsibility of auth.
package accounts

import (
	"context"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5/pgconn"
	"tokenworks-mobile/services/remote/internal/store"
)

const LocalIssuer = "urn:tokenworks-mobile:local-account:v1"
const iterations = 600000
const sessionAge = 12 * time.Hour

var usernamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9_.-]{2,31}$`)
var ErrCredentials = errors.New("account: invalid credentials")
var ErrUnavailable = errors.New("account: unavailable")
var ErrLimited = errors.New("account: try later")
var ErrInput = errors.New("account: invalid input")
var ErrExists = errors.New("account: username unavailable")

// Provider describes a login choice, not a token issuer or a credential.
// A future SSO adapter must resolve a trusted issuer/subject; it must not merge by username.
type Provider struct {
	ID      string `json:"id"`
	Enabled bool   `json:"enabled"`
}

func Providers() []Provider { return []Provider{{"password", true}, {"sso", false}} }

type Service struct {
	database *store.Postgres
	epoch    string
	slots    chan struct{}
	dummy    string
}
type Session struct {
	UserID    string    `json:"user_id"`
	Username  string    `json:"username"`
	Subject   string    `json:"-"`
	ExpiresAt time.Time `json:"expires_at"`
	version   int64
	hash      string
}

func New(database *store.Postgres, epoch string) (*Service, error) {
	if database == nil || database.DB == nil || epoch == "" {
		return nil, ErrUnavailable
	}
	dummy, err := passwordHash("unusable-dummy-password")
	if err != nil {
		return nil, err
	}
	return &Service{database: database, epoch: epoch, slots: make(chan struct{}, 8), dummy: dummy}, nil
}
func normalize(name string) string { return strings.ToLower(strings.TrimSpace(name)) }
func validPassword(password string) bool {
	return utf8.ValidString(password) && utf8.RuneCountInString(password) >= 12 && len(password) <= 256
}
func secret() string {
	var raw [32]byte
	_, _ = rand.Read(raw[:])
	return base64.RawURLEncoding.EncodeToString(raw[:])
}
func digest(value string) []byte { hash := sha256.Sum256([]byte(value)); return hash[:] }
func passwordHash(password string) (string, error) {
	var salt [16]byte
	_, _ = rand.Read(salt[:])
	key, err := pbkdf2.Key(sha256.New, password, salt[:], iterations, 32)
	if err != nil {
		return "", ErrUnavailable
	}
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", iterations, base64.RawStdEncoding.EncodeToString(salt[:]), base64.RawStdEncoding.EncodeToString(key)), nil
}
func passwordMatches(encoded, password string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" || parts[1] != "600000" || len(password) > 256 {
		return false
	}
	salt, e1 := base64.RawStdEncoding.Strict().DecodeString(parts[2])
	expected, e2 := base64.RawStdEncoding.Strict().DecodeString(parts[3])
	if e1 != nil || e2 != nil || len(salt) != 16 || len(expected) != 32 {
		return false
	}
	actual, err := pbkdf2.Key(sha256.New, password, salt, iterations, 32)
	return err == nil && subtle.ConstantTimeCompare(actual, expected) == 1
}
func (s *Service) cryptoSlot() (func(), error) {
	select {
	case s.slots <- struct{}{}:
		return func() { <-s.slots }, nil
	default:
		return nil, ErrLimited
	}
}

// Fixed database buckets survive retries, process restarts and multiple nodes.
// Store only digests; ignore forwarded IP headers. Global budget bounds new-key growth.
func (s *Service) Attempt(ctx context.Context, ip, name string) error {
	tx, err := s.database.DB.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	for _, bucket := range []struct {
		key   string
		limit int
	}{{"global", 240}, {"ip:" + ip, 40}, {"user:" + normalize(name), 12}} {
		var count int
		err = tx.QueryRowContext(ctx, `insert into account_login_limits(bucket_hash) values($1)
   on conflict(bucket_hash) do update set started_at=case when account_login_limits.started_at<clock_timestamp()-interval '10 minutes' then clock_timestamp() else account_login_limits.started_at end,
   attempts=case when account_login_limits.started_at<clock_timestamp()-interval '10 minutes' then 1 else account_login_limits.attempts+1 end returning attempts`, digest(bucket.key)).Scan(&count)
		if err != nil {
			return ErrUnavailable
		}
		if count > bucket.limit {
			if tx.Commit() != nil {
				return ErrUnavailable
			}
			return ErrLimited
		}
	}
	if _, err = tx.ExecContext(ctx, `delete from account_login_limits where started_at<clock_timestamp()-interval '20 minutes'`); err != nil {
		return ErrUnavailable
	}
	if tx.Commit() != nil {
		return ErrUnavailable
	}
	return nil
}
func audit(ctx context.Context, tx *sql.Tx, user, action string) error {
	_, err := tx.ExecContext(ctx, `insert into audit_log(actor_type,actor_id,action,result,metadata) values('user',$1,$2,'success','{}')`, user, action)
	return err
}
func (s *Service) Register(ctx context.Context, name, password string) error {
	name = normalize(name)
	if !usernamePattern.MatchString(name) || !validPassword(password) {
		return ErrInput
	}
	release, err := s.cryptoSlot()
	if err != nil {
		return err
	}
	defer release()
	hash, err := passwordHash(password)
	if err != nil {
		return err
	}
	tx, err := s.database.DB.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	var id string
	if err = tx.QueryRowContext(ctx, `insert into users(issuer,subject) values($1,gen_random_uuid()::text) returning id::text`, LocalIssuer).Scan(&id); err != nil {
		return ErrUnavailable
	}
	if _, err = tx.ExecContext(ctx, `insert into local_accounts(user_id,username,password_hash) values($1,$2,$3)`, id, name, hash); err != nil {
		var pg *pgconn.PgError
		if errors.As(err, &pg) && pg.Code == "23505" {
			return ErrExists
		}
		return ErrUnavailable
	}
	if audit(ctx, tx, id, "account.register") != nil || tx.Commit() != nil {
		return ErrUnavailable
	}
	return nil
}
func (s *Service) Login(ctx context.Context, name, password string) (string, string, error) {
	release, err := s.cryptoSlot()
	if err != nil {
		return "", "", err
	}
	defer release()
	name = normalize(name)
	var id, hash string
	var version int64
	err = s.database.DB.QueryRowContext(ctx, `select a.user_id::text,a.password_hash,a.password_version from local_accounts a join users u on u.id=a.user_id where a.username=$1 and u.status='active' and u.issuer=$2`, name, LocalIssuer).Scan(&id, &hash, &version)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return "", "", ErrUnavailable
	}
	if hash == "" {
		hash = s.dummy
	}
	matched := passwordMatches(hash, password)
	if !matched || id == "" {
		return "", "", ErrCredentials
	}
	token, csrf := secret(), secret()
	tx, err := s.database.DB.BeginTx(ctx, nil)
	if err != nil {
		return "", "", ErrUnavailable
	}
	defer tx.Rollback()
	var current int64
	err = tx.QueryRowContext(ctx, `select a.password_version from local_accounts a join users u on u.id=a.user_id where a.user_id=$1 and u.status='active' for update of a,u`, id).Scan(&current)
	if err != nil || current != version {
		return "", "", ErrCredentials
	}
	if _, err = tx.ExecContext(ctx, `delete from account_sessions where expires_at<=clock_timestamp() or (user_id=$1 and token_hash not in (select token_hash from account_sessions where user_id=$1 order by created_at desc limit 7))`, id); err != nil {
		return "", "", ErrUnavailable
	}
	_, err = tx.ExecContext(ctx, `insert into account_sessions(token_hash,csrf_hash,user_id,password_version,database_epoch,expires_at) select $1,$2,$3,$4,epoch,clock_timestamp()+interval '12 hours' from remote_recovery_state where slot=1 and epoch=$5 and phase='ready'`, digest(token), digest(csrf), id, version, s.epoch)
	if err != nil {
		return "", "", ErrUnavailable
	}
	var exists bool
	if tx.QueryRowContext(ctx, `select exists(select 1 from account_sessions where token_hash=$1)`, digest(token)).Scan(&exists) != nil || !exists {
		return "", "", ErrUnavailable
	}
	if audit(ctx, tx, id, "account.login") != nil || tx.Commit() != nil {
		return "", "", ErrUnavailable
	}
	return token, csrf, nil
}
func (s *Service) Current(ctx context.Context, token string) (Session, error) {
	var value Session
	if len(token) != 43 {
		return value, ErrCredentials
	}
	err := s.database.DB.QueryRowContext(ctx, `select a.user_id::text,a.username,u.subject,s.expires_at,a.password_version,a.password_hash
 from account_sessions s join local_accounts a on a.user_id=s.user_id and a.password_version=s.password_version
 join users u on u.id=a.user_id and u.status='active' and u.issuer=$2
 join remote_recovery_state r on r.slot=1 and r.epoch=s.database_epoch and r.phase='ready'
 where s.token_hash=$1 and s.expires_at>clock_timestamp() and s.database_epoch=$3`, digest(token), LocalIssuer, s.epoch).Scan(&value.UserID, &value.Username, &value.Subject, &value.ExpiresAt, &value.version, &value.hash)
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrCredentials
	}
	if err != nil {
		return Session{}, ErrUnavailable
	}
	return value, nil
}

// The browser keeps the CSRF secret in a separate SameSite cookie; it is never
// an authorization credential. Only its digest is persisted server-side.
func (s *Service) CSRF(ctx context.Context, token, csrf string) error {
	if len(token) != 43 || len(csrf) != 43 {
		return ErrCredentials
	}
	var valid bool
	err := s.database.DB.QueryRowContext(ctx, `select exists(select 1 from account_sessions where token_hash=$1 and csrf_hash=$2 and database_epoch=$3 and expires_at>clock_timestamp())`, digest(token), digest(csrf), s.epoch).Scan(&valid)
	if err != nil {
		return ErrUnavailable
	}
	if !valid {
		return ErrCredentials
	}
	return nil
}
func (s *Service) Logout(ctx context.Context, token string) error {
	tx, err := s.database.DB.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	var id string
	err = tx.QueryRowContext(ctx, `delete from account_sessions where token_hash=$1 returning user_id::text`, digest(token)).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return ErrUnavailable
	}
	if audit(ctx, tx, id, "account.logout") != nil || tx.Commit() != nil {
		return ErrUnavailable
	}
	return nil
}
func (s *Service) ChangePassword(ctx context.Context, token, old, password string) error {
	if !validPassword(password) {
		return ErrInput
	}
	release, err := s.cryptoSlot()
	if err != nil {
		return err
	}
	defer release()
	account, err := s.Current(ctx, token)
	if err != nil {
		return err
	}
	if !passwordMatches(account.hash, old) {
		return ErrCredentials
	}
	hash, err := passwordHash(password)
	if err != nil {
		return err
	}
	tx, err := s.database.DB.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, `update local_accounts a set password_hash=$1,password_version=password_version+1 from users u
 where a.user_id=$2 and a.password_version=$3 and u.id=a.user_id and u.status='active'
 and exists(select 1 from account_sessions s join remote_recovery_state r on r.slot=1 and r.epoch=s.database_epoch and r.phase='ready'
 where s.user_id=a.user_id and s.token_hash=$4 and s.expires_at>clock_timestamp() and s.database_epoch=$5)`, hash, account.UserID, account.version, digest(token), s.epoch)
	if err != nil {
		return ErrUnavailable
	}
	n, _ := result.RowsAffected()
	if n != 1 {
		return ErrCredentials
	}
	if _, err = tx.ExecContext(ctx, `delete from account_sessions where user_id=$1`, account.UserID); err != nil {
		return ErrUnavailable
	}
	if audit(ctx, tx, account.UserID, "account.password_changed") != nil || tx.Commit() != nil {
		return ErrUnavailable
	}
	return nil
}
func (s *Service) Overview(ctx context.Context, session Session) (store.AccountIdentity, []store.Host, error) {
	identity := store.UserIdentity{Issuer: LocalIssuer, Subject: session.Subject}
	account, err := s.database.AccountIdentity(ctx, identity)
	if err != nil {
		return account, nil, ErrUnavailable
	}
	hosts, err := s.database.ListHosts(ctx, identity)
	if err != nil {
		return account, nil, ErrUnavailable
	}
	return account, hosts, nil
}
