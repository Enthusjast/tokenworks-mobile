package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"time"
)

var ErrTunnelLeaseInvalid = errors.New("store: tunnel lease is invalid")

// TunnelLease is the short-lived authorization behind one device's native
// traffic. Only the SHA-256 of its secret is stored.
type TunnelLease struct {
	ID         string
	Secret     string
	ExpiresAt  time.Time
	GrantVersion int64
}

type TunnelLeaseIdentity struct {
	LeaseID        string         `json:"lease_id"`
	UserID         string         `json:"sub"`
	DeviceID       string         `json:"device_id"`
	RuntimeID      string         `json:"runtime_id"`
	InstallationID string         `json:"installation_id"`
	GrantVersion   int64          `json:"grant_version"`
	Bindings       []GrantBinding `json:"bindings"`
	ExpiresAt      time.Time      `json:"exp"`
}

func (postgres *Postgres) IssueTunnelLease(ctx context.Context, identity UserIdentity, deviceID, runtimeID string) (TunnelLease, error) {
	if !grantUUIDPattern.MatchString(deviceID) || !grantUUIDPattern.MatchString(runtimeID) {
		return TunnelLease{}, ErrTunnelLeaseInvalid
	}
	return retrySerializable(ctx, func() (TunnelLease, error) {
		return postgres.issueTunnelLeaseOnce(ctx, identity, deviceID, runtimeID)
	}, postgres.admissionGate())
}

func (postgres *Postgres) issueTunnelLeaseOnce(ctx context.Context, identity UserIdentity, deviceID, runtimeID string) (TunnelLease, error) {
	transaction, err := postgres.DB.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return TunnelLease{}, transactionFailure(err)
	}
	defer transaction.Rollback()
	userID, err := postgres.userIDForSerializable(ctx, transaction, identity.Issuer, identity.Subject)
	if err != nil {
		return TunnelLease{}, err
	}
	var deviceKeyVersion int64
	err = transaction.QueryRowContext(ctx, `
		select key_version from devices
		where id = $1 and user_id = $2 and revoked_at is null for share`, deviceID, userID).Scan(&deviceKeyVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return TunnelLease{}, ErrDeviceNotFound
	}
	if err != nil {
		return TunnelLease{}, transactionFailure(err)
	}
	var installationID string
	var grantVersion int64
	var bindingsJSON []byte
	err = transaction.QueryRowContext(ctx, `
		select i.id::text, g.grant_version, g.bindings
		from runtime_grants g
		join runtimes r on r.id = g.runtime_id and r.revoked_at is null and `+runtimeEnrollmentApprovedSQL+`
		join installations i on i.id = r.installation_id and i.revoked_at is null
		join hosts h on h.id = i.host_id and h.state = 'active'
		join org_memberships m on m.org_id = h.org_id and m.user_id = g.user_id
		where g.runtime_id = $1 and g.user_id = $2 and g.revoked_at is null
		  and (g.expires_at is null or g.expires_at > clock_timestamp())
		for share of g, r, i, h`, runtimeID, userID).Scan(&installationID, &grantVersion, &bindingsJSON)
	if errors.Is(err, sql.ErrNoRows) {
		return TunnelLease{}, ErrAccessDenied
	}
	if err != nil {
		return TunnelLease{}, transactionFailure(err)
	}
	var bindings []GrantBinding
	if json.Unmarshal(bindingsJSON, &bindings) != nil || len(bindings) == 0 {
		return TunnelLease{}, ErrTunnelLeaseInvalid
	}
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return TunnelLease{}, transactionFailure(err)
	}
	token := base64.RawURLEncoding.EncodeToString(secret)
	for index := range secret {
		secret[index] = 0
	}
	digest := sha256.Sum256([]byte(token))
	var lease TunnelLease
	err = transaction.QueryRowContext(ctx, `
		insert into tunnel_leases (device_id, user_id, runtime_id, installation_id, device_key_version, grant_version, secret_sha256, expires_at)
		values ($1, $2, $3, $4, $5, $6, $7, clock_timestamp() + interval '5 minutes')
		returning id::text, expires_at`, deviceID, userID, runtimeID, installationID, deviceKeyVersion, grantVersion, digest[:]).Scan(&lease.ID, &lease.ExpiresAt)
	if err != nil {
		return TunnelLease{}, transactionFailure(err)
	}
	lease.Secret, lease.GrantVersion = token, grantVersion
	if err := transaction.Commit(); err != nil {
		return TunnelLease{}, transactionFailure(err)
	}
	return lease, nil
}

// InspectTunnelLease serves the owning installation only. It never returns
// another device's identity or any business payload.
func (postgres *Postgres) InspectTunnelLease(ctx context.Context, installationID, leaseID, secret string) (TunnelLeaseIdentity, error) {
	if !grantUUIDPattern.MatchString(installationID) || !grantUUIDPattern.MatchString(leaseID) || len(secret) != 43 {
		return TunnelLeaseIdentity{}, ErrTunnelLeaseInvalid
	}
	decoded, err := base64.RawURLEncoding.Strict().DecodeString(secret)
	if err != nil || len(decoded) != 32 || base64.RawURLEncoding.EncodeToString(decoded) != secret {
		return TunnelLeaseIdentity{}, ErrTunnelLeaseInvalid
	}
	clear(decoded)
	digest := sha256.Sum256([]byte(secret))
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	var identity TunnelLeaseIdentity
	var bindingsJSON []byte
	err = postgres.DB.QueryRowContext(ctx, `
		select lease.id::text, lease.user_id::text, lease.device_id::text, lease.runtime_id::text,
		       lease.installation_id::text, lease.grant_version, g.bindings, lease.expires_at
		from tunnel_leases lease
		join runtime_grants g on g.runtime_id = lease.runtime_id and g.user_id = lease.user_id and g.grant_version = lease.grant_version
		join devices d on d.id = lease.device_id and d.revoked_at is null and d.key_version = lease.device_key_version
		join runtimes r on r.id = lease.runtime_id and r.revoked_at is null and `+runtimeEnrollmentApprovedSQL+`
		join installations i on i.id = r.installation_id and i.id = lease.installation_id and i.revoked_at is null
		join hosts h on h.id = i.host_id and h.state = 'active'
		join org_memberships m on m.org_id = h.org_id and m.user_id = lease.user_id
		where lease.id = $1 and lease.secret_sha256 = $2 and lease.installation_id = $3
		  and lease.revoked_at is null and lease.expires_at > clock_timestamp()
		  and g.revoked_at is null and (g.expires_at is null or g.expires_at > clock_timestamp())`,
		leaseID, digest[:], installationID).Scan(&identity.LeaseID, &identity.UserID, &identity.DeviceID, &identity.RuntimeID,
		&identity.InstallationID, &identity.GrantVersion, &bindingsJSON, &identity.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return TunnelLeaseIdentity{}, ErrTunnelLeaseInvalid
	}
	if err != nil {
		return TunnelLeaseIdentity{}, ErrDatabaseUnavailable
	}
	if json.Unmarshal(bindingsJSON, &identity.Bindings) != nil {
		return TunnelLeaseIdentity{}, ErrDatabaseUnavailable
	}
	return identity, nil
}

// VerifyTunnelLease admits one phone request on this node. It returns granting
// facts only; the Relay never inspects the forwarded body.
func (postgres *Postgres) VerifyTunnelLease(ctx context.Context, identity UserIdentity, deviceID, runtimeID, secret string) (TunnelLeaseIdentity, error) {
	if !grantUUIDPattern.MatchString(deviceID) || !grantUUIDPattern.MatchString(runtimeID) || len(secret) != 43 {
		return TunnelLeaseIdentity{}, ErrTunnelLeaseInvalid
	}
	digest := sha256.Sum256([]byte(secret))
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	var lease TunnelLeaseIdentity
	err := postgres.DB.QueryRowContext(ctx, `
		select lease.id::text, lease.user_id::text, lease.device_id::text, lease.runtime_id::text,
		       lease.installation_id::text, lease.grant_version, lease.expires_at
		from tunnel_leases lease
		join users u on u.id = lease.user_id and u.issuer = $4 and u.subject = $5 and u.status = 'active'
		join devices d on d.id = lease.device_id and d.user_id = u.id and d.revoked_at is null and d.key_version = lease.device_key_version
		where lease.secret_sha256 = $1 and lease.device_id = $2 and lease.runtime_id = $3
		  and lease.revoked_at is null and lease.expires_at > clock_timestamp()`,
		digest[:], deviceID, runtimeID, identity.Issuer, identity.Subject).Scan(&lease.LeaseID, &lease.UserID, &lease.DeviceID,
		&lease.RuntimeID, &lease.InstallationID, &lease.GrantVersion, &lease.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return TunnelLeaseIdentity{}, ErrTunnelLeaseInvalid
	}
	if err != nil {
		return TunnelLeaseIdentity{}, ErrDatabaseUnavailable
	}
	return lease, nil
}

// RevokeTunnelLeases closes the leases selected by one control event and
// reports nothing about devices the caller does not own.
func (postgres *Postgres) RevokeTunnelLeases(ctx context.Context, topic, deviceID, runtimeID string) error {
	var query string
	var arguments []any
	switch topic {
	case "device.revoked":
		query = `update tunnel_leases set revoked_at = clock_timestamp() where device_id = $1 and revoked_at is null`
		arguments = []any{deviceID}
	case "runtime.revoked":
		query = `update tunnel_leases set revoked_at = clock_timestamp() where runtime_id = $1 and revoked_at is null`
		arguments = []any{runtimeID}
	default:
		query = `update tunnel_leases set revoked_at = clock_timestamp() where runtime_id = $1 and revoked_at is null`
		arguments = []any{runtimeID}
	}
	if _, err := postgres.DB.ExecContext(ctx, query, arguments...); err != nil {
		return ErrDatabaseUnavailable
	}
	return nil
}
