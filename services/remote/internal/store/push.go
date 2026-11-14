package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"time"

	"tokenworks-mobile/services/remote/internal/push"
)

// PushRepository pins background delivery to the same recovery incarnation as
// HTTP admission; a restored queue is never sent under a different epoch.
type PushRepository struct {
	*Postgres
	recovery RecoveryState
}

func (guard *RecoveryGuard) PushRepository() *PushRepository {
	return &PushRepository{Postgres: guard.postgres, recovery: guard.state}
}

func (postgres *PushRepository) lockReady(ctx context.Context, tx *sql.Tx) error {
	state, err := lockRecoveryReady(ctx, tx, postgres.recovery.Epoch)
	if err != nil || !sameRecoveryCutoff(state.TokenNotBefore, postgres.recovery.TokenNotBefore) {
		return push.ErrUnavailable
	}
	return nil
}

func pushActor(ctx context.Context, tx *sql.Tx, actor push.Actor) (string, int64, error) {
	var user string
	var version int64
	err := tx.QueryRowContext(ctx, `select u.id::text,d.key_version from users u
	 join devices d on d.user_id=u.id and d.id=$3 and d.proof_key_thumbprint=$4 and d.revoked_at is null
	 where u.issuer=$1 and u.subject=$2 and u.status='active' for share of u,d`, actor.Issuer, actor.Subject, actor.DeviceID, actor.ProofJKT).Scan(&user, &version)
	if errors.Is(err, sql.ErrNoRows) { return "", 0, push.ErrDenied }
	if err != nil { return "", 0, push.ErrUnavailable }
	return user, version, nil
}

func (postgres *PushRepository) RegisterPush(ctx context.Context, actor push.Actor, registration push.Registration, tokenCipher *push.TokenCipher) (string, error) {
	tx, err := postgres.DB.BeginTx(ctx, nil)
	if err != nil { return "", push.ErrUnavailable }
	defer tx.Rollback()
	if err := postgres.lockReady(ctx, tx); err != nil { return "", err }
	user, version, err := pushActor(ctx, tx, actor)
	if err != nil { return "", err }
	owner := push.Owner{AccountID: registration.AccountID, OrganizationID: registration.OrganizationID, DeploymentSHA256: registration.DeploymentSHA256, UserID: user, DeviceID: actor.DeviceID}
	if !owner.Valid() || owner.Fingerprint() != registration.OwnerFingerprint { return "", push.ErrDenied }
	var member string
	err = tx.QueryRowContext(ctx, `select user_id::text from org_memberships where org_id=$1 and user_id=$2 for share`, owner.OrganizationID, user).Scan(&member)
	if errors.Is(err, sql.ErrNoRows) { return "", push.ErrDenied }
	if err != nil { return "", push.ErrUnavailable }
	// Serialize rotation/revocation on the device, before touching its binding.
	// No old queue row can be replayed onto a newly issued binding ID.
	if _, err := tx.ExecContext(ctx, `select pg_advisory_xact_lock(hashtextextended($1,711))`, actor.DeviceID); err != nil { return "", push.ErrUnavailable }
	tokenHash := sha256.Sum256([]byte(registration.Token))
	var foreign bool
	if err := tx.QueryRowContext(ctx, `select exists(select 1 from push_registrations where token_sha256=$1 and device_id<>$2)`, tokenHash[:], actor.DeviceID).Scan(&foreign); err != nil { return "", push.ErrUnavailable }
	if foreign { return "", push.ErrDenied }
	var id string
	// An identical authenticated registration is idempotent; rotations always
	// allocate a new binding and cancel all pending old references atomically.
	err = tx.QueryRowContext(ctx, `select id::text from push_registrations where device_id=$1 and account_id=$2 and org_id=$3
	 and user_id=$4 and deployment_sha256=$5 and owner_fingerprint=$6 and token_sha256=$7 and device_key_version=$8 and proof_key_thumbprint=$9`,
		actor.DeviceID, owner.AccountID, owner.OrganizationID, user, owner.DeploymentSHA256, registration.OwnerFingerprint, tokenHash[:], version, actor.ProofJKT).Scan(&id)
	if err == nil {
		if tx.Commit() != nil { return "", push.ErrUnavailable }
		return id, nil
	}
	if !errors.Is(err, sql.ErrNoRows) { return "", push.ErrUnavailable }
	if _, err := tx.ExecContext(ctx, `delete from push_registrations where device_id=$1`, actor.DeviceID); err != nil { return "", push.ErrUnavailable }
	if err := tx.QueryRowContext(ctx, `select gen_random_uuid()::text`).Scan(&id); err != nil { return "", push.ErrUnavailable }
	ciphertext, err := tokenCipher.Encrypt(registration.Token, id, registration.OwnerFingerprint)
	if err != nil { return "", err }
	_, err = tx.ExecContext(ctx, `insert into push_registrations(id,account_id,org_id,user_id,device_id,device_key_version,deployment_sha256,owner_fingerprint,token_sha256,token_ciphertext,proof_key_thumbprint)
	 values($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`, id, owner.AccountID, owner.OrganizationID, user, actor.DeviceID, version, owner.DeploymentSHA256, registration.OwnerFingerprint, tokenHash[:], ciphertext, actor.ProofJKT)
	if err != nil { return "", push.ErrUnavailable }
	if tx.Commit() != nil { return "", push.ErrUnavailable }
	return id, nil
}

func (postgres *PushRepository) RevokePush(ctx context.Context, actor push.Actor, binding string) error {
	tx, err := postgres.DB.BeginTx(ctx, nil)
	if err != nil { return push.ErrUnavailable }
	defer tx.Rollback()
	if err := postgres.lockReady(ctx, tx); err != nil { return err }
	user, _, err := pushActor(ctx, tx, actor)
	if err != nil { return err }
	if _, err := tx.ExecContext(ctx, `select pg_advisory_xact_lock(hashtextextended($1,711))`, actor.DeviceID); err != nil { return push.ErrUnavailable }
	result, err := tx.ExecContext(ctx, `delete from push_registrations where id=$1 and device_id=$2 and user_id=$3`, binding, actor.DeviceID, user)
	if err != nil { return push.ErrUnavailable }
	if count, _ := result.RowsAffected(); count != 1 { return push.ErrDenied }
	if tx.Commit() != nil { return push.ErrUnavailable }
	return nil
}

// A minimal session reference itself reveals session identity. Directory access
// alone or an unrelated workspace grant is insufficient: require session/follow
// for this exact opaque session (or the explicit all-session binding).
const pushSessionEligibilitySQL = `exists (select 1 from jsonb_array_elements(g.bindings) binding
	where binding->'resource'->>'kind'='session'
	and coalesce(binding->'resource'->>'id','') in ('',q.session_id)
	and binding->'actions' ? 'session/follow')`

const pushAuthoritySQL = ` from push_registrations b
	join devices d on d.id=b.device_id and d.user_id=b.user_id and d.revoked_at is null and d.key_version=b.device_key_version
	 and d.proof_key_thumbprint=b.proof_key_thumbprint
	join users u on u.id=b.user_id and u.status='active'
	join org_memberships m on m.org_id=b.org_id and m.user_id=u.id
	join runtime_grants g on g.user_id=u.id and g.runtime_id=q.runtime_id and g.revoked_at is null
	 and (g.expires_at is null or g.expires_at>clock_timestamp())
	join runtimes r on r.id=g.runtime_id and r.revoked_at is null
	join installations i on i.id=r.installation_id and i.revoked_at is null and i.id=q.installation_id and i.key_version=q.installation_key_version
	join hosts h on h.id=i.host_id and h.org_id=b.org_id and h.state='active'
	join users installation_owner on installation_owner.id=i.owner_user_id and installation_owner.status='active'
	where b.deployment_sha256=$1 and ` + runtimeEnrollmentApprovedSQL + ` and ` + pushSessionEligibilitySQL

func (postgres *PushRepository) EnqueuePush(ctx context.Context, installation push.Installation, emit push.Emit, deployment string) error {
	if !emit.Valid(time.Now()) || !installation.ExpiresAt.After(time.Now()) { return push.ErrInvalid }
	tx, err := postgres.DB.BeginTx(ctx, nil)
	if err != nil { return push.ErrUnavailable }
	defer tx.Rollback()
	if err := postgres.lockReady(ctx, tx); err != nil { return err }
	var runtime string
	err = tx.QueryRowContext(ctx, `select r.id::text from runtimes r
	 join installations i on i.id=r.installation_id and i.revoked_at is null and i.key_version=$3
	 join hosts h on h.id=i.host_id and h.state='active'
	 join users owner on owner.id=i.owner_user_id and owner.status='active'
	 where r.id=$1 and i.id=$2 and r.revoked_at is null and `+runtimeEnrollmentApprovedSQL+`
	 for share of r,i,h,owner`, emit.RuntimeID, installation.ID, installation.KeyVersion).Scan(&runtime)
	if errors.Is(err, sql.ErrNoRows) { return push.ErrDenied }
	if err != nil { return push.ErrUnavailable }
	_, err = tx.ExecContext(ctx, `with q as (select $2::uuid runtime_id,$3::uuid installation_id,$4::bigint installation_key_version,$5::text session_id)
	 insert into push_deliveries(binding_id,notification_id,installation_id,installation_key_version,runtime_id,session_id,kind,expires_at)
	 select b.id,$6,q.installation_id,q.installation_key_version,q.runtime_id,q.session_id,$7,$8 from q cross join lateral
	 (select b.id`+pushAuthoritySQL+`) b on conflict(binding_id,notification_id) do nothing`, deployment, emit.RuntimeID, installation.ID, installation.KeyVersion, emit.SessionID, emit.NotificationID, emit.Kind, emit.ExpiresAt)
	if err != nil { return push.ErrUnavailable }
	if !installation.ExpiresAt.After(time.Now()) || !emit.ExpiresAt.After(time.Now()) { return push.ErrInvalid }
	if tx.Commit() != nil { return push.ErrUnavailable }
	return nil
}

func (postgres *PushRepository) DeliverPush(ctx context.Context, deployment string, tokenCipher *push.TokenCipher, provider push.Provider) (bool, error) {
	// The transaction and network call share a bounded deadline. Authority row
	// locks serialize actual send with device/grant/runtime/installation revocation.
	ctx, cancel := context.WithTimeout(ctx, 35*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, nil)
	if err != nil { return false, push.ErrUnavailable }
	defer tx.Rollback()
	if err := postgres.lockReady(ctx, tx); err != nil { return false, err }
	if _, err := tx.ExecContext(ctx, `delete from push_deliveries where id in
	 (select id from push_deliveries where expires_at<=clock_timestamp() order by id for update skip locked limit 128)`); err != nil { return false, push.ErrUnavailable }
	var id int64
	var reference push.Reference
	err = tx.QueryRowContext(ctx, `select id,binding_id::text,notification_id::text,runtime_id::text,session_id,kind,expires_at
	 from push_deliveries where delivered_at is null and next_attempt_at<=clock_timestamp()
	 order by next_attempt_at,id for update skip locked limit 1`).Scan(&id, &reference.BindingID, &reference.Emit.NotificationID,
		&reference.Emit.RuntimeID, &reference.Emit.SessionID, &reference.Emit.Kind, &reference.Emit.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		if tx.Commit() != nil { return false, push.ErrUnavailable }
		return false, nil
	}
	if err != nil { return false, push.ErrUnavailable }
	finish := func() (bool, error) {
		if _, err := tx.ExecContext(ctx, `delete from push_deliveries where id=$1`, id); err != nil { return true, push.ErrUnavailable }
		if tx.Commit() != nil { return true, push.ErrUnavailable }
		return true, nil
	}
	if !reference.Emit.Valid(time.Now()) { return finish() }
	var ciphertext []byte
	var fingerprint string
	var grantExpiry sql.NullTime
	err = tx.QueryRowContext(ctx, `select b.account_id::text,b.org_id::text,b.deployment_sha256,b.user_id::text,b.device_id::text,
	 b.owner_fingerprint,b.token_ciphertext,b.grant_expiry
	 from push_deliveries q cross join lateral (select b.*,g.expires_at as grant_expiry`+pushAuthoritySQL+`
	 and b.id=q.binding_id for share of b,d,u,m,g,r,i,h,installation_owner) b
	 where q.id=$2`, deployment, id).Scan(&reference.Owner.AccountID, &reference.Owner.OrganizationID, &reference.Owner.DeploymentSHA256,
		&reference.Owner.UserID, &reference.Owner.DeviceID, &fingerprint, &ciphertext, &grantExpiry)
	if errors.Is(err, sql.ErrNoRows) { return finish() }
	if err != nil { return true, push.ErrUnavailable }
	if fingerprint != reference.Owner.Fingerprint() { return finish() }
	token, err := tokenCipher.Decrypt(ciphertext, reference.BindingID, fingerprint)
	if err != nil { return finish() }
	defer clear(token)
	// Provider receives the shorter authorization deadline so OAuth work cannot
	// push delivery past a grant expiry. Keep the original expiry on retries.
	sendReference := reference
	if grantExpiry.Valid && grantExpiry.Time.Before(sendReference.Emit.ExpiresAt) { sendReference.Emit.ExpiresAt = grantExpiry.Time }
	err = provider.Send(ctx, string(token), sendReference)
	if errors.Is(err, push.ErrUnregistered) {
		if _, deleteErr := tx.ExecContext(ctx, `delete from push_registrations where id=$1`, reference.BindingID); deleteErr != nil { return true, push.ErrUnavailable }
	} else if err == nil {
		if _, updateErr := tx.ExecContext(ctx, `update push_deliveries set delivered_at=clock_timestamp() where id=$1`, id); updateErr != nil { return true, push.ErrUnavailable }
	} else if errors.Is(err, push.ErrInvalid) { return finish()
	} else {
		if _, updateErr := tx.ExecContext(ctx, `update push_deliveries set next_attempt_at=clock_timestamp()+interval '30 seconds' where id=$1`, id); updateErr != nil { return true, push.ErrUnavailable }
	}
	if tx.Commit() != nil { return true, push.ErrUnavailable }
	return true, nil
}
