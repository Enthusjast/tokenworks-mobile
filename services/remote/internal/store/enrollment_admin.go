package store

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"
)

// EnrollmentAdminReview is a separate admin-only response. Participant JSON
// remains unchanged; an enrollment with both confirmations can still be pending.
type EnrollmentAdminReview struct {
	EnrollmentID              string                 `json:"enrollment_id"`
	OrganizationID            string                 `json:"organization_id"`
	HostID                    string                 `json:"host_id"`
	HostAlias                 string                 `json:"host_alias"`
	InstallationID            string                 `json:"installation_id"`
	InstallationKeyJKT        string                 `json:"installation_key_jkt"`
	RuntimeID                 string                 `json:"runtime_id"`
	ProfileRef                string                 `json:"profile_ref"`
	RequestSHA256             string                 `json:"request_sha256"`
	Owner                     EnrollmentReviewUser   `json:"owner"`
	ConfirmedUser             *EnrollmentReviewUser  `json:"confirmed_user"`
	ConfirmedDeviceID         *string                `json:"confirmed_device_id"`
	ConfirmedDeviceKeyVersion *int64                 `json:"confirmed_device_key_version"`
	UserConfirmedAt           *time.Time             `json:"user_confirmed_at"`
	HostConfirmedAt           *time.Time             `json:"host_confirmed_at"`
	Required                  bool                   `json:"admin_approval_required"`
	State                     string                 `json:"state"`
	ExpiresAt                 time.Time              `json:"expires_at"`
	Workspaces                []EnrollmentWorkspace  `json:"workspaces"`
	ApprovedReviewSHA256      string                 `json:"approved_review_sha256,omitempty"`
	ReviewSHA256              string                 `json:"review_sha256,omitempty"`
	Denial                    *EnrollmentAdminDenial `json:"denial,omitempty"`
}

func (postgres *Postgres) lockEnrollmentAdmin(ctx context.Context, tx *sql.Tx, identity UserIdentity, deviceID, proofJKT, enrollmentID string) (enrollmentRow, string, int64, error) {
	if !grantUUIDPattern.MatchString(enrollmentID) || !grantUUIDPattern.MatchString(deviceID) {
		return enrollmentRow{}, "", 0, ErrEnrollmentInvalid
	}
	actor, err := postgres.userID(ctx, tx, identity.Issuer, identity.Subject)
	if err != nil {
		return enrollmentRow{}, "", 0, err
	}
	row, err := loadEnrollmentForUpdate(ctx, tx, enrollmentID)
	if errors.Is(err, sql.ErrNoRows) {
		return row, "", 0, ErrAccessDenied
	}
	if err != nil {
		return row, "", 0, ErrDatabaseUnavailable
	}
	var version int64
	err = tx.QueryRowContext(ctx, `select d.key_version from devices d
	 join org_memberships m on m.user_id=d.user_id and m.org_id=$4 and m.role='admin'
	 where d.id=$1 and d.user_id=$2 and d.proof_key_thumbprint=$3 and d.revoked_at is null
	 for share of d,m`, deviceID, actor, proofJKT, row.OrgID).Scan(&version)
	if errors.Is(err, sql.ErrNoRows) {
		return row, "", 0, ErrAccessDenied
	}
	if err != nil {
		return row, "", 0, ErrDatabaseUnavailable
	}
	if row.HostState == "revoked" || row.InstallationRevoked.Valid || row.RuntimeRevoked.Valid {
		// Current administrators may reconcile a recorded terminal denial even
		// after that same transaction retired its resources. This grants no use.
		if row.State != "denied" {
			return row, "", 0, ErrAccessDenied
		}
		var recorded bool
		if err := tx.QueryRowContext(ctx, `select exists(select 1 from enrollment_admin_denials where enrollment_id=$1)`, row.ID).Scan(&recorded); err != nil {
			return row, "", 0, ErrDatabaseUnavailable
		}
		if !recorded {
			return row, "", 0, ErrAccessDenied
		}
	}
	return row, actor, version, nil
}

func enrollmentParticipant(ctx context.Context, tx *sql.Tx, row enrollmentRow, id string) (EnrollmentReviewUser, error) {
	value := EnrollmentReviewUser{UserID: id}
	if err := tx.QueryRowContext(ctx, `select issuer,subject from users where id=$1`, id).Scan(&value.Issuer, &value.Subject); err != nil {
		return value, ErrDatabaseUnavailable
	}
	_, err := activeOrganizationUser(ctx, tx, row.OrgID, id)
	if err != nil && !errors.Is(err, ErrAccessDenied) {
		return value, err
	}
	value.Eligible = err == nil
	return value, nil
}

func enrollmentAdminReview(ctx context.Context, tx *sql.Tx, row enrollmentRow) (EnrollmentAdminReview, error) {
	value := EnrollmentAdminReview{EnrollmentID: row.ID, OrganizationID: row.OrgID, HostID: row.HostID,
		InstallationID: row.InstallationID, InstallationKeyJKT: row.InstallationKeyJKT, RuntimeID: row.RuntimeID,
		Required: row.AdminApprovalRequired, State: row.State, ExpiresAt: row.ExpiresAt.UTC()}
	var requestHash []byte
	if err := tx.QueryRowContext(ctx, `select h.alias,r.profile_ref,e.request_sha256 from enrollments e
	 join hosts h on h.id=e.host_id join runtimes r on r.id=e.runtime_id where e.id=$1`, row.ID).
		Scan(&value.HostAlias, &value.ProfileRef, &requestHash); err != nil {
		return value, ErrDatabaseUnavailable
	}
	value.RequestSHA256 = hex.EncodeToString(requestHash)
	var err error
	value.Owner, err = enrollmentParticipant(ctx, tx, row, row.InstallationOwnerID)
	if err != nil {
		return value, err
	}
	if row.UserConfirmedBy.Valid {
		phone, err := enrollmentParticipant(ctx, tx, row, row.UserConfirmedBy.String)
		if err != nil {
			return value, err
		}
		var device string
		err = tx.QueryRowContext(ctx, `select id::text from devices where id=$1 and user_id=$2 and key_version=$3 and revoked_at is null for share`,
			nullStringValue(row.UserConfirmedDeviceID), row.UserConfirmedBy.String, row.UserConfirmedDeviceKeyVersion).Scan(&device)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return value, ErrDatabaseUnavailable
		}
		phone.Eligible = phone.Eligible && err == nil
		value.ConfirmedUser = &phone
	}
	if row.UserConfirmedDeviceID.Valid {
		value.ConfirmedDeviceID = &row.UserConfirmedDeviceID.String
	}
	if row.UserConfirmedDeviceKeyVersion.Valid {
		value.ConfirmedDeviceKeyVersion = &row.UserConfirmedDeviceKeyVersion.Int64
	}
	if row.UserConfirmedAt.Valid {
		at := row.UserConfirmedAt.Time.UTC()
		value.UserConfirmedAt = &at
	}
	if row.HostConfirmedAt.Valid {
		at := row.HostConfirmedAt.Time.UTC()
		value.HostConfirmedAt = &at
	}
	value.Workspaces, err = enrollmentWorkspaces(ctx, tx, row.RuntimeID)
	if err != nil {
		return value, err
	}
	if row.State == "pending" || row.State == "user_confirmed" {
		if err := checkPendingEnrollmentDeadline(ctx, tx, row.ExpiresAt); errors.Is(err, ErrEnrollmentInvalid) {
			value.State = "expired"
		} else if err != nil {
			return value, err
		}
	}
	if len(row.AdminReviewSHA256) != 0 {
		value.ApprovedReviewSHA256 = hex.EncodeToString(row.AdminReviewSHA256)
	}
	if row.State == "denied" {
		denial, err := readEnrollmentAdminDenial(ctx, tx, row.ID)
		if err != nil && !errors.Is(err, errEnrollmentDenialNotFound) {
			return value, err
		}
		if err == nil {
			value.Denial = &denial
		}
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return value, ErrDatabaseUnavailable
	}
	digest := sha256.Sum256(append([]byte("dsh-enrollment-admin-review-v1\x00"), raw...))
	value.ReviewSHA256 = hex.EncodeToString(digest[:])
	return value, nil
}

// Review authority is rechecked on every read; an ID or digest carries no grant.
func (postgres *Postgres) ReviewEnrollmentAdmin(parent context.Context, identity UserIdentity, deviceID, proofJKT, enrollmentID string) (EnrollmentAdminReview, error) {
	ctx, cancel := context.WithTimeout(parent, 3*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return EnrollmentAdminReview{}, ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	row, _, _, err := postgres.lockEnrollmentAdmin(ctx, tx, identity, deviceID, proofJKT, enrollmentID)
	if err != nil {
		return EnrollmentAdminReview{}, err
	}
	value, err := enrollmentAdminReview(ctx, tx, row)
	if err != nil {
		return value, err
	}
	if err := tx.Commit(); err != nil {
		return EnrollmentAdminReview{}, ErrDatabaseUnavailable
	}
	return value, nil
}

// ApproveEnrollmentAdmin is an additional explicit decision after phone/local
// consent. The digest pins the exact reviewed participants, device/key and scope.
func (postgres *Postgres) ApproveEnrollmentAdmin(parent context.Context, identity UserIdentity, deviceID, proofJKT, enrollmentID, expectedReview string) (EnrollmentConfirmation, error) {
	if !validSHA256Hex(expectedReview) {
		return EnrollmentConfirmation{}, ErrEnrollmentInvalid
	}
	ctx, cancel := context.WithTimeout(parent, 5*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return EnrollmentConfirmation{}, ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	row, actor, version, err := postgres.lockEnrollmentAdmin(ctx, tx, identity, deviceID, proofJKT, enrollmentID)
	if err != nil {
		return EnrollmentConfirmation{}, err
	}
	if row.State == "denied" {
		return EnrollmentConfirmation{}, ErrEnrollmentConflict
	}
	if row.State == "approved" {
		expected, _ := hex.DecodeString(expectedReview)
		if !row.AdminApprovedBy.Valid || row.AdminApprovedBy.String != actor || subtle.ConstantTimeCompare(expected, row.AdminReviewSHA256) != 1 {
			return EnrollmentConfirmation{}, ErrEnrollmentConflict
		}
		if err := tx.Commit(); err != nil {
			return EnrollmentConfirmation{}, ErrDatabaseUnavailable
		}
		return enrollmentConfirmation(row), nil
	}
	if row.State != "user_confirmed" || !row.AdminApprovalRequired || !row.UserConfirmedBy.Valid || !row.HostConfirmedAt.Valid {
		return EnrollmentConfirmation{}, ErrEnrollmentInvalid
	}
	if err := checkPendingEnrollmentDeadline(ctx, tx, row.ExpiresAt); err != nil {
		return EnrollmentConfirmation{}, err
	}
	review, err := enrollmentAdminReview(ctx, tx, row)
	if err != nil {
		return EnrollmentConfirmation{}, err
	}
	if !review.Owner.Eligible || review.ConfirmedUser == nil || !review.ConfirmedUser.Eligible {
		return EnrollmentConfirmation{}, ErrAccessDenied
	}
	if review.ReviewSHA256 != expectedReview {
		return EnrollmentConfirmation{}, ErrEnrollmentConflict
	}
	if err := checkPendingEnrollmentDeadline(ctx, tx, row.ExpiresAt); err != nil {
		return EnrollmentConfirmation{}, err
	}
	digest, _ := hex.DecodeString(expectedReview)
	if _, err := tx.ExecContext(ctx, `update enrollments set state='approved',decided_by=$2,decided_at=clock_timestamp(),
	 admin_approved_by=$2,admin_approved_device_id=$3,admin_approved_device_key_version=$4,
	 admin_approved_at=clock_timestamp(),admin_review_sha256=$5 where id=$1`, row.ID, actor, deviceID, version, digest); err != nil {
		return EnrollmentConfirmation{}, ErrDatabaseUnavailable
	}
	if err := activateEnrollment(ctx, tx, row, actor, deviceID); err != nil {
		return EnrollmentConfirmation{}, err
	}
	metadata, _ := json.Marshal(map[string]string{"enrollment_id": row.ID, "host_id": row.HostID, "installation_id": row.InstallationID, "runtime_id": row.RuntimeID, "state": "approved", "review_sha256": expectedReview})
	if _, err := tx.ExecContext(ctx, `insert into audit_log(org_id,actor_type,actor_id,device_id,runtime_id,action,result,metadata)
	 values($1,'device',$2::text,$2::uuid,$3,'enrollment.admin_approve','success',$4::jsonb)`, row.OrgID, deviceID, row.RuntimeID, string(metadata)); err != nil {
		return EnrollmentConfirmation{}, ErrDatabaseUnavailable
	}
	if _, err := tx.ExecContext(ctx, `insert into control_outbox(org_id,topic,payload) values($1,'enrollment.state_changed',$2::jsonb)`, row.OrgID, string(metadata)); err != nil {
		return EnrollmentConfirmation{}, ErrDatabaseUnavailable
	}
	// Recheck after all key/member, grant, audit and outbox waits; expiry must
	// roll back every new authority, including the administrator's decision.
	if err := checkPendingEnrollmentDeadline(ctx, tx, row.ExpiresAt); err != nil {
		return EnrollmentConfirmation{}, err
	}
	if err := tx.Commit(); err != nil {
		return EnrollmentConfirmation{}, ErrDatabaseUnavailable
	}
	row.State = "approved"
	return enrollmentConfirmation(row), nil
}
