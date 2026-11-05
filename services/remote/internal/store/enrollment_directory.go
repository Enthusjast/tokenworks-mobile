package store

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
)

var ErrEnrollmentDirectoryInvalid = errors.New("store: invalid enrollment directory request")

type EnrollmentSummary struct {
	EnrollmentID    string    `json:"enrollment_id"`
	OrganizationID  string    `json:"organization_id"`
	HostID          string    `json:"host_id"`
	HostAlias       string    `json:"host_alias"`
	InstallationID  string    `json:"installation_id"`
	RuntimeID       string    `json:"runtime_id"`
	ProfileRef      string    `json:"profile_ref"`
	OwnerUserID     string    `json:"owner_user_id"`
	ConfirmedUserID *string   `json:"confirmed_user_id"`
	UserConfirmed   bool      `json:"user_confirmed"`
	HostConfirmed   bool      `json:"host_confirmed"`
	State           string    `json:"state"`
	CreatedAt       time.Time `json:"created_at"`
	ExpiresAt       time.Time `json:"expires_at"`
}

type EnrollmentDirectory struct {
	OrganizationID string              `json:"organization_id"`
	Items          []EnrollmentSummary `json:"items"`
	NextCursor     *string             `json:"next_cursor"`
}

type enrollmentDirectoryCursor struct {
	Version        int       `json:"v"`
	Purpose        string    `json:"purpose"`
	OrganizationID string    `json:"organization_id"`
	UserID         string    `json:"user_id"`
	DeviceID       string    `json:"device_id"`
	ProofKeyJKT    string    `json:"proof_key_jkt"`
	After          time.Time `json:"after_created_at"`
	AfterID        string    `json:"after_id"`
}

func parseEnrollmentDirectoryCursor(encoded string) (enrollmentDirectoryCursor, error) {
	var cursor enrollmentDirectoryCursor
	if len(encoded) > 1024 {
		return cursor, ErrEnrollmentDirectoryInvalid
	}
	raw, err := base64.RawURLEncoding.Strict().DecodeString(encoded)
	if err != nil || len(raw) == 0 || base64.RawURLEncoding.EncodeToString(raw) != encoded || auth.ValidateJSONObject(raw) != nil {
		return cursor, ErrEnrollmentDirectoryInvalid
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&cursor) != nil || cursor.Version != 1 || cursor.Purpose != "enrollment-approval-directory-v1" ||
		!grantUUIDPattern.MatchString(cursor.OrganizationID) || !grantUUIDPattern.MatchString(cursor.UserID) || !grantUUIDPattern.MatchString(cursor.DeviceID) ||
		!grantUUIDPattern.MatchString(cursor.AfterID) || !keyThumbprintPattern.MatchString(cursor.ProofKeyJKT) || cursor.After.IsZero() {
		return cursor, ErrEnrollmentDirectoryInvalid
	}
	return cursor, nil
}

// One statement checks the current administrator and returns an oldest-first
// live page. A cursor is only an organization/account/device-bound locator.
func (postgres *Postgres) ListEnrollmentApprovals(parent context.Context, identity UserIdentity, deviceID, proofJKT, organizationID string, limit int, encoded string) (EnrollmentDirectory, error) {
	page := EnrollmentDirectory{OrganizationID: organizationID, Items: []EnrollmentSummary{}}
	if !grantUUIDPattern.MatchString(deviceID) || !grantUUIDPattern.MatchString(organizationID) || !keyThumbprintPattern.MatchString(proofJKT) || limit < 1 || limit > 100 {
		return page, ErrEnrollmentDirectoryInvalid
	}
	var user, after, id any
	if encoded != "" {
		cursor, err := parseEnrollmentDirectoryCursor(encoded)
		if err != nil {
			return page, err
		}
		if cursor.OrganizationID != organizationID || cursor.DeviceID != deviceID || cursor.ProofKeyJKT != proofJKT {
			return page, ErrAccessDenied
		}
		user, after, id = cursor.UserID, cursor.After, cursor.AfterID
	}
	ctx, cancel := context.WithTimeout(parent, 2*time.Second)
	defer cancel()
	var actor string
	var raw []byte
	err := postgres.DB.QueryRowContext(ctx, `with actor as materialized(
	 select u.id from users u join devices d on d.user_id=u.id
	 join org_memberships m on m.user_id=u.id and m.org_id=$5 and m.role='admin'
	 where u.issuer=$1 and u.subject=$2 and u.status='active' and d.id=$3 and d.proof_key_thumbprint=$4 and d.revoked_at is null
	 and ($6::uuid is null or u.id=$6))
	 select a.id::text,coalesce((select jsonb_agg(row_to_json(p) order by p.created_at,p.enrollment_id) from (
	 select e.id enrollment_id,h.org_id organization_id,h.id host_id,h.alias host_alias,i.id installation_id,r.id runtime_id,r.profile_ref,
	 i.owner_user_id,e.user_confirmed_by confirmed_user_id,e.user_confirmed_by is not null user_confirmed,
	 (e.host_confirmed_at is not null and e.user_confirmed_by is not null and e.user_confirmed_device_id is not null and e.user_confirmed_device_key_version is not null) host_confirmed,
	 e.state,e.created_at,e.expires_at from enrollments e join hosts h on h.id=e.host_id and h.org_id=$5 and h.state<>'revoked'
	 join installations i on i.id=e.installation_id and i.host_id=h.id and i.revoked_at is null
	 join runtimes r on r.id=e.runtime_id and r.installation_id=i.id and r.revoked_at is null
	 where e.admin_approval_required and e.state in('pending','user_confirmed') and e.expires_at>statement_timestamp()
	 and ($7::timestamptz is null or (e.created_at,e.id)>($7,$8::uuid))
	 order by e.created_at,e.id limit $9) p),'[]'::jsonb) from actor a`,
		identity.Issuer, identity.Subject, deviceID, proofJKT, organizationID, user, after, id, limit+1).Scan(&actor, &raw)
	if errors.Is(err, sql.ErrNoRows) {
		return page, ErrAccessDenied
	}
	if err != nil {
		return page, ErrDatabaseUnavailable
	}
	if json.Unmarshal(raw, &page.Items) != nil {
		return page, ErrDatabaseUnavailable
	}
	if len(page.Items) > limit {
		page.Items = page.Items[:limit]
		last := page.Items[len(page.Items)-1]
		raw, _ := json.Marshal(enrollmentDirectoryCursor{Version: 1, Purpose: "enrollment-approval-directory-v1", OrganizationID: organizationID, UserID: actor, DeviceID: deviceID, ProofKeyJKT: proofJKT, After: last.CreatedAt.UTC(), AfterID: last.EnrollmentID})
		next := base64.RawURLEncoding.EncodeToString(raw)
		page.NextCursor = &next
	}
	return page, nil
}
