package store

import (
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
)

func TestEnrollmentTerminalClosesExpiredOwnedRequestAndFreezesHistory(t *testing.T) {
	ctx, db, input, created := enrollmentPreviewFixture(t)
	owner := UserIdentity{Issuer: input.Issuer, Subject: input.Subject}
	if _, err := db.ObserveEnrollmentTerminal(ctx, owner, input.InstallationKeyThumbprint, input.RequestID); !errors.Is(err, ErrEnrollmentConflict) {
		t.Fatal("live pending request was closed", err)
	}
	if _, err := db.ObserveEnrollmentTerminal(ctx, owner, strings.Repeat("B", 43), input.RequestID); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("foreign installation key observed request", err)
	}
	if _, err := db.ObserveEnrollmentTerminal(ctx, UserIdentity{Issuer: input.Issuer, Subject: "target-subject"}, input.InstallationKeyThumbprint, input.RequestID); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("another account observed request", err)
	}
	if _, err := db.DB.ExecContext(ctx, `update enrollments set created_at=clock_timestamp()-interval '10 minutes',expires_at=clock_timestamp()-interval '1 minute' where id=$1`, created.EnrollmentID); err != nil {
		t.Fatal(err)
	}
	first, err := db.ObserveEnrollmentTerminal(ctx, owner, input.InstallationKeyThumbprint, input.RequestID)
	if err != nil || first.State != "expired" || first.EnrollmentID != created.EnrollmentID || first.RequestID != input.RequestID || first.Version != 1 {
		t.Fatal("expired lookup did not settle exact original request", err)
	}
	second, err := db.ObserveEnrollmentTerminal(ctx, owner, input.InstallationKeyThumbprint, input.RequestID)
	if err != nil || !second.ObservedAt.Equal(first.ObservedAt) {
		t.Fatal("terminal observation did not reconcile", err)
	}
	raw, _ := json.Marshal(first)
	if strings.Contains(string(raw), input.Code) || strings.Contains(string(raw), "code_hash") {
		t.Fatal("terminal metadata disclosed pairing secret")
	}
	var records, audits, events, grants int
	var retired bool
	if err := db.DB.QueryRowContext(ctx, `select
	 (select count(*) from enrollment_terminal_checks where enrollment_id=$1),
	 (select count(*) from audit_log where runtime_id=$2 and action='enrollment.terminal_observe'),
	 (select count(*) from control_outbox where topic='runtime.revoked' and payload->>'runtime_id'=$2::text),
	 (select count(*) from runtime_grants where runtime_id=$2),
	 (select r.revoked_at is not null and i.revoked_at is not null from runtimes r join installations i on i.id=r.installation_id where r.id=$2)`, created.EnrollmentID, created.RuntimeID).Scan(&records, &audits, &events, &grants, &retired); err != nil || records != 1 || audits != 1 || events != 1 || grants != 0 || !retired {
		t.Fatal("terminal evidence/retirement was not atomic or idempotent", err)
	}
	for _, query := range []string{`update enrollments set state='approved' where id='` + created.EnrollmentID + `'`, `delete from enrollment_terminal_checks`, `truncate enrollment_terminal_checks`} {
		if _, err := db.DB.ExecContext(ctx, query); err == nil {
			t.Fatal("closed history could be erased or reactivated")
		}
	}
	down, err := os.ReadFile("../../../../deploy/sql/049_enrollment_terminal_checks.down.sql")
	if err != nil {
		t.Fatal(err)
	}
	connection, err := db.DB.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err := connection.ExecContext(ctx, string(down)); err == nil {
		t.Fatal("terminal evidence was removed by rollback")
	}
	_, _ = connection.ExecContext(ctx, "rollback")
}

func TestEnrollmentTerminalSupportsDeniedAndCancelledButRejectsApproved(t *testing.T) {
	for _, state := range []string{"denied", "cancelled", "approved"} {
		t.Run(state, func(t *testing.T) {
			ctx, db, input, created, owner, proof := adminEnrollmentFixture(t)
			if state == "cancelled" {
				if _, err := db.DB.ExecContext(ctx, `update enrollments set state='cancelled' where id=$1`, created.EnrollmentID); err != nil {
					t.Fatal(err)
				}
			} else {
				review, err := db.ReviewEnrollmentAdmin(ctx, owner, enrollmentAdminDevice, proof, created.EnrollmentID)
				if err != nil {
					t.Fatal(err)
				}
				if state == "denied" {
					if _, err := db.DenyEnrollmentAdmin(ctx, owner, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256); err != nil {
						t.Fatal(err)
					}
				} else {
					if _, err := db.ApproveEnrollmentAdmin(ctx, owner, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256); err != nil {
						t.Fatal(err)
					}
				}
			}
			result, err := db.ObserveEnrollmentTerminal(ctx, owner, input.InstallationKeyThumbprint, input.RequestID)
			if state == "approved" {
				if !errors.Is(err, ErrEnrollmentConflict) {
					t.Fatal("approved request authorized terminal cleanup", err)
				}
			} else if err != nil || result.State != state {
				t.Fatal("terminal state did not reconcile after resource retirement", state, err)
			}
		})
	}
}

func TestEnrollmentTerminalWaitsForInflightApprovalWithoutLosingItsAuthority(t *testing.T) {
	ctx, db, input, created := enrollmentPreviewFixture(t)
	owner := UserIdentity{Issuer: input.Issuer, Subject: input.Subject}
	if _, err := db.ConfirmEnrollmentUser(ctx, UserIdentity{Issuer: input.Issuer, Subject: "target-subject"}, enrollmentPhoneDevice, "proof-jkt-target", created.EnrollmentID, input.Code); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `create function hold_terminal_host_audit() returns trigger language plpgsql as $$ begin if new.action='enrollment.host_confirm' then perform pg_advisory_xact_lock(426997);end if;return new;end $$;
	 create trigger hold_terminal_host_audit before insert on audit_log for each row execute function hold_terminal_host_audit()`); err != nil {
		t.Fatal(err)
	}
	gate, err := db.DB.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer gate.Rollback()
	if _, err := gate.ExecContext(ctx, `select pg_advisory_xact_lock(426997)`); err != nil {
		t.Fatal(err)
	}
	confirmation := make(chan error, 1)
	go func() {
		_, err := db.ConfirmEnrollmentHost(ctx, owner, input.InstallationKeyThumbprint, created.EnrollmentID, input.Code, enrollmentPhoneUser)
		confirmation <- err
	}()
	waitForGrantLock(t, ctx, db, "insert into audit_log")
	observation := make(chan error, 1)
	go func() {
		_, err := db.ObserveEnrollmentTerminal(ctx, owner, input.InstallationKeyThumbprint, input.RequestID)
		observation <- err
	}()
	waitForGrantLock(t, ctx, db, "select e.id::text from enrollments e")
	if err := gate.Commit(); err != nil {
		t.Fatal(err)
	}
	if err := <-confirmation; err != nil {
		t.Fatal("inflight timely approval was interrupted", err)
	}
	if err := <-observation; err == nil {
		t.Fatal("waiting observer declared a live approval terminal")
	}
	if _, err := db.ObserveEnrollmentTerminal(ctx, owner, input.InstallationKeyThumbprint, input.RequestID); !errors.Is(err, ErrEnrollmentConflict) {
		t.Fatal("approved request did not remain protected", err)
	}
	var records, grants int
	if err := db.DB.QueryRowContext(ctx, `select (select count(*) from enrollment_terminal_checks where enrollment_id=$1),(select count(*) from runtime_grants where runtime_id=$2)`, created.EnrollmentID, created.RuntimeID).Scan(&records, &grants); err != nil || records != 0 || grants != 2 {
		t.Fatal("terminal observer lost or rewrote approval authority", err)
	}
}
