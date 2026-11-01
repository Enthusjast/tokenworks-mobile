package store

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"
)

var errEnrollmentDenialNotFound = errors.New("store: enrollment denial not found")

type EnrollmentAdminDenial struct {
	EnrollmentID     string    `json:"enrollment_id"`
	HostID           string    `json:"host_id"`
	InstallationID   string    `json:"installation_id"`
	RuntimeID        string    `json:"runtime_id"`
	State            string    `json:"state"`
	DeniedBy         string    `json:"denied_by"`
	DeviceID         string    `json:"device_id"`
	DeviceKeyVersion int64     `json:"device_key_version"`
	ReviewSHA256     string    `json:"review_sha256"`
	DeniedAt         time.Time `json:"denied_at"`
}

func readEnrollmentAdminDenial(ctx context.Context, tx *sql.Tx, id string) (EnrollmentAdminDenial, error) {
	var result EnrollmentAdminDenial
	var digest []byte
	err := tx.QueryRowContext(ctx, `select e.id::text,e.host_id::text,e.installation_id::text,e.runtime_id::text,e.state,
	 d.denied_by::text,d.device_id::text,d.device_key_version,d.review_sha256,d.denied_at
	 from enrollment_admin_denials d join enrollments e on e.id=d.enrollment_id where d.enrollment_id=$1`, id).
		Scan(&result.EnrollmentID, &result.HostID, &result.InstallationID, &result.RuntimeID, &result.State, &result.DeniedBy, &result.DeviceID, &result.DeviceKeyVersion, &digest, &result.DeniedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return result, errEnrollmentDenialNotFound
	}
	if err != nil || len(digest) != 32 || result.State != "denied" {
		return result, ErrDatabaseUnavailable
	}
	result.ReviewSHA256 = hex.EncodeToString(digest)
	result.DeniedAt = result.DeniedAt.UTC()
	return result, nil
}

// DenyEnrollmentAdmin pins the same explicit review as approval. It can stop a
// required request before either participant confirms, without granting access.
func (postgres *Postgres) DenyEnrollmentAdmin(parent context.Context, identity UserIdentity, deviceID, proofJKT, enrollmentID, expectedReview string) (EnrollmentAdminDenial, error) {
	if !validSHA256Hex(expectedReview) {
		return EnrollmentAdminDenial{}, ErrEnrollmentInvalid
	}
	ctx, cancel := context.WithTimeout(parent, 5*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return EnrollmentAdminDenial{}, ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	row, actor, version, err := postgres.lockEnrollmentAdmin(ctx, tx, identity, deviceID, proofJKT, enrollmentID)
	if err != nil {
		return EnrollmentAdminDenial{}, err
	}
	if row.State == "denied" {
		receipt, err := readEnrollmentAdminDenial(ctx, tx, row.ID)
		if errors.Is(err, errEnrollmentDenialNotFound) {
			return receipt, ErrEnrollmentConflict
		}
		if err != nil {
			return receipt, err
		}
		if receipt.DeniedBy != actor || subtle.ConstantTimeCompare([]byte(receipt.ReviewSHA256), []byte(expectedReview)) != 1 {
			return receipt, ErrEnrollmentConflict
		}
		if err := tx.Commit(); err != nil {
			return EnrollmentAdminDenial{}, ErrDatabaseUnavailable
		}
		return receipt, nil
	}
	if row.State == "approved" {
		return EnrollmentAdminDenial{}, ErrEnrollmentConflict
	}
	if !row.AdminApprovalRequired || row.State != "pending" && row.State != "user_confirmed" {
		return EnrollmentAdminDenial{}, ErrEnrollmentInvalid
	}
	if err := checkPendingEnrollmentDeadline(ctx, tx, row.ExpiresAt); err != nil {
		return EnrollmentAdminDenial{}, err
	}
	review, err := enrollmentAdminReview(ctx, tx, row)
	if err != nil {
		return EnrollmentAdminDenial{}, err
	}
	if review.ReviewSHA256 != expectedReview {
		return EnrollmentAdminDenial{}, ErrEnrollmentConflict
	}
	if err := checkPendingEnrollmentDeadline(ctx, tx, row.ExpiresAt); err != nil {
		return EnrollmentAdminDenial{}, err
	}
	if _, err := tx.ExecContext(ctx, `update enrollments set state='denied',decided_by=$2,decided_at=clock_timestamp() where id=$1`, row.ID, actor); err != nil {
		return EnrollmentAdminDenial{}, ErrDatabaseUnavailable
	}
	digest, _ := hex.DecodeString(expectedReview)
	if _, err := tx.ExecContext(ctx, `insert into enrollment_admin_denials(enrollment_id,denied_by,device_id,device_key_version,review_sha256)
	 values($1,$2,$3,$4,$5)`, row.ID, actor, deviceID, version, digest); err != nil {
		return EnrollmentAdminDenial{}, ErrDatabaseUnavailable
	}
	if err := retireDeniedEnrollment(ctx, tx, row); err != nil {
		return EnrollmentAdminDenial{}, err
	}
	metadata, _ := json.Marshal(map[string]string{"enrollment_id": row.ID, "host_id": row.HostID, "installation_id": row.InstallationID, "runtime_id": row.RuntimeID, "state": "denied", "review_sha256": expectedReview})
	if _, err := tx.ExecContext(ctx, `insert into audit_log(org_id,actor_type,actor_id,device_id,runtime_id,action,result,metadata)
	 values($1,'device',$2::text,$2::uuid,$3,'enrollment.admin_deny','success',$4::jsonb)`, row.OrgID, deviceID, row.RuntimeID, string(metadata)); err != nil {
		return EnrollmentAdminDenial{}, ErrDatabaseUnavailable
	}
	for _, topic := range []string{"enrollment.state_changed", "runtime.revoked"} {
		if _, err := tx.ExecContext(ctx, `insert into control_outbox(org_id,topic,payload) values($1,$2,$3::jsonb)`, row.OrgID, topic, string(metadata)); err != nil {
			return EnrollmentAdminDenial{}, ErrDatabaseUnavailable
		}
	}
	receipt, err := readEnrollmentAdminDenial(ctx, tx, row.ID)
	if err != nil {
		return receipt, err
	}
	// Resource retirement, evidence and outbox writes can wait too. A late
	// decision rolls back all of them, rather than extending the pairing code.
	if err := checkPendingEnrollmentDeadline(ctx, tx, row.ExpiresAt); err != nil {
		return EnrollmentAdminDenial{}, err
	}
	if err := tx.Commit(); err != nil {
		return EnrollmentAdminDenial{}, ErrDatabaseUnavailable
	}
	return receipt, nil
}

func retireDeniedEnrollment(ctx context.Context, tx *sql.Tx, row enrollmentRow) error {
	if _, err := tx.ExecContext(ctx, `update runtimes set revoked_at=clock_timestamp() where id=$1 and revoked_at is null`, row.RuntimeID); err != nil {
		return ErrDatabaseUnavailable
	}
	if _, err := tx.ExecContext(ctx, `update installations i set revoked_at=clock_timestamp() where i.id=$1 and i.revoked_at is null
	 and not exists(select 1 from runtimes r where r.installation_id=i.id and r.revoked_at is null)`, row.InstallationID); err != nil {
		return ErrDatabaseUnavailable
	}
	for _, query := range []string{
		`update host_connection_credentials c set revoked_at=clock_timestamp() from installations i where c.installation_id=i.id and i.id=$1 and i.revoked_at is not null and c.revoked_at is null`,
		`update installation_challenges c set consumed_at=clock_timestamp() from installations i where c.installation_id=i.id and i.id=$1 and i.revoked_at is not null and c.consumed_at is null`,
	} {
		if _, err := tx.ExecContext(ctx, query, row.InstallationID); err != nil {
			return ErrDatabaseUnavailable
		}
	}
	if _, err := tx.ExecContext(ctx, `update hosts h set state='revoked' where h.id=$1 and h.state='pending'
	 and not exists(select 1 from installations i where i.host_id=h.id and i.revoked_at is null)`, row.HostID); err != nil {
		return ErrDatabaseUnavailable
	}
	return nil
}
