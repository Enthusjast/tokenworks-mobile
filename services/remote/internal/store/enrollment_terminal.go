package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

type EnrollmentTerminal struct {
	Version            int                   `json:"v"`
	EnrollmentID       string                `json:"enrollment_id"`
	RequestID          string                `json:"request_id"`
	OrganizationID     string                `json:"organization_id"`
	HostID             string                `json:"host_id"`
	HostAlias          string                `json:"host_alias"`
	InstallationID     string                `json:"installation_id"`
	InstallationKeyJKT string                `json:"installation_key_jkt"`
	RuntimeID          string                `json:"runtime_id"`
	ProfileRef         string                `json:"profile_ref"`
	DSHVersion         string                `json:"dsh_version"`
	ApplicationVersion string                `json:"application_version"`
	PluginVersion      string                `json:"plugin_version"`
	ProtocolVersion    int                   `json:"protocol_version"`
	State              string                `json:"state"`
	ExpiresAt          time.Time             `json:"expires_at"`
	ObservedAt         time.Time             `json:"observed_at"`
	Workspaces         []EnrollmentWorkspace `json:"workspaces"`
}

// ObserveEnrollmentTerminal closes only an already terminal or expired owned
// request. The locked negative fact is durable before Host deletes local data.
// Lookup by the stable request ID also reconciles a lost create response.
func (postgres *Postgres) ObserveEnrollmentTerminal(parent context.Context, identity UserIdentity, keyJKT, requestID string) (EnrollmentTerminal, error) {
	if !grantUUIDPattern.MatchString(requestID) || !keyThumbprintPattern.MatchString(keyJKT) {
		return EnrollmentTerminal{}, ErrEnrollmentInvalid
	}
	ctx, cancel := context.WithTimeout(parent, 5*time.Second)
	defer cancel()
	tx, err := postgres.DB.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelSerializable})
	if err != nil {
		return EnrollmentTerminal{}, ErrDatabaseUnavailable
	}
	defer tx.Rollback()
	actor, err := postgres.userID(ctx, tx, identity.Issuer, identity.Subject)
	if err != nil {
		return EnrollmentTerminal{}, err
	}
	var id string
	err = tx.QueryRowContext(ctx, `select e.id::text from enrollments e join installations i on i.id=e.installation_id
	 where e.request_id=$1 and e.requested_by=$2 and i.owner_user_id=$2 and i.public_key_thumbprint=$3 for update of e`, requestID, actor, keyJKT).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return EnrollmentTerminal{}, ErrAccessDenied
	}
	if err != nil {
		return EnrollmentTerminal{}, ErrDatabaseUnavailable
	}
	row, err := loadEnrollmentForUpdate(ctx, tx, id)
	if err != nil {
		return EnrollmentTerminal{}, ErrDatabaseUnavailable
	}
	if row.RequestedBy != actor || row.InstallationOwnerID != actor || row.InstallationKeyJKT != keyJKT {
		return EnrollmentTerminal{}, ErrAccessDenied
	}
	if _, err := activeOrganizationUser(ctx, tx, row.OrgID, actor); err != nil {
		return EnrollmentTerminal{}, err
	}
	var observed time.Time
	var terminalState string
	var observedOwner, observedKey string
	err = tx.QueryRowContext(ctx, `select state,observed_at,owner_user_id::text,installation_key_thumbprint from enrollment_terminal_checks where enrollment_id=$1`, id).Scan(&terminalState, &observed, &observedOwner, &observedKey)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return EnrollmentTerminal{}, ErrDatabaseUnavailable
	}
	if err == nil {
		if observedOwner != actor || observedKey != keyJKT {
			return EnrollmentTerminal{}, ErrEnrollmentConflict
		}
		if row.State != terminalState || !row.RuntimeRevoked.Valid {
			return EnrollmentTerminal{}, ErrDatabaseUnavailable
		}
	} else {
		if row.State == "pending" || row.State == "user_confirmed" {
			err := checkPendingEnrollmentDeadline(ctx, tx, row.ExpiresAt)
			if err == nil {
				return EnrollmentTerminal{}, ErrEnrollmentConflict
			}
			if !errors.Is(err, ErrEnrollmentInvalid) {
				return EnrollmentTerminal{}, err
			}
			if _, err := tx.ExecContext(ctx, `update enrollments set state='expired',decided_at=clock_timestamp() where id=$1`, id); err != nil {
				return EnrollmentTerminal{}, ErrDatabaseUnavailable
			}
			row.State = "expired"
		}
		if row.State != "denied" && row.State != "expired" && row.State != "cancelled" {
			return EnrollmentTerminal{}, ErrEnrollmentConflict
		}
		if err := retireUnapprovedEnrollment(ctx, tx, row); err != nil {
			return EnrollmentTerminal{}, err
		}
		if err := tx.QueryRowContext(ctx, `insert into enrollment_terminal_checks(enrollment_id,state,owner_user_id,installation_key_thumbprint)
		 values($1,$2,$3,$4) returning observed_at`, id, row.State, actor, keyJKT).Scan(&observed); err != nil {
			return EnrollmentTerminal{}, ErrDatabaseUnavailable
		}
		metadata, _ := json.Marshal(map[string]string{"enrollment_id": id, "request_id": requestID, "installation_id": row.InstallationID, "runtime_id": row.RuntimeID, "state": row.State})
		if _, err := tx.ExecContext(ctx, `insert into audit_log(org_id,actor_type,actor_id,runtime_id,action,result,metadata)
		 values($1,'installation',$2,$3,'enrollment.terminal_observe','success',$4::jsonb)`, row.OrgID, row.InstallationID, row.RuntimeID, string(metadata)); err != nil {
			return EnrollmentTerminal{}, ErrDatabaseUnavailable
		}
		if _, err := tx.ExecContext(ctx, `insert into control_outbox(org_id,topic,payload) values($1,'runtime.revoked',$2::jsonb)`, row.OrgID, string(metadata)); err != nil {
			return EnrollmentTerminal{}, ErrDatabaseUnavailable
		}
	}
	result := EnrollmentTerminal{Version: 1, EnrollmentID: id, RequestID: requestID, OrganizationID: row.OrgID, HostID: row.HostID,
		InstallationID: row.InstallationID, InstallationKeyJKT: keyJKT, RuntimeID: row.RuntimeID, State: row.State, ExpiresAt: row.ExpiresAt.UTC(), ObservedAt: observed.UTC()}
	err = tx.QueryRowContext(ctx, `select h.alias,r.profile_ref,coalesce(r.dsh_version,''),coalesce(r.application_version,''),coalesce(r.plugin_version,''),r.protocol_version
	 from hosts h join installations i on i.host_id=h.id join runtimes r on r.installation_id=i.id where r.id=$1`, row.RuntimeID).
		Scan(&result.HostAlias, &result.ProfileRef, &result.DSHVersion, &result.ApplicationVersion, &result.PluginVersion, &result.ProtocolVersion)
	if err != nil {
		return result, ErrDatabaseUnavailable
	}
	result.Workspaces, err = enrollmentWorkspaces(ctx, tx, row.RuntimeID)
	if err != nil {
		return result, err
	}
	if err := tx.Commit(); err != nil {
		return EnrollmentTerminal{}, ErrDatabaseUnavailable
	}
	return result, nil
}
