package store

import (
	"errors"
	"os"
	"testing"
	"time"
)

func TestEnrollmentAdminDenialIsTerminalAndReconcilesAfterRetirementAndExpiry(t *testing.T) {
	ctx, db, input, created, admin, proof := adminEnrollmentFixture(t)
	var expires time.Time
	if err := db.DB.QueryRowContext(ctx, `update enrollments set expires_at=clock_timestamp()+interval '1 second' where id=$1 returning expires_at`, created.EnrollmentID).Scan(&expires); err != nil {
		t.Fatal(err)
	}
	review, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil {
		t.Fatal(err)
	}
	denied, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256)
	if err != nil || denied.State != "denied" || denied.DeviceID != enrollmentAdminDevice || denied.ReviewSHA256 != review.ReviewSHA256 {
		t.Fatal("administrator denial failed", err)
	}
	waitForGrantDatabaseExpiry(t, ctx, db, expires)
	retry, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256)
	if err != nil || retry != denied {
		t.Fatal("retired/expired denial did not reconcile exactly", err)
	}
	current, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil || current.Denial == nil || *current.Denial != denied {
		t.Fatal("current admin could not reconcile terminal evidence", err)
	}
	if _, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256); !errors.Is(err, ErrEnrollmentConflict) {
		t.Fatal("denied enrollment was approved", err)
	}
	if _, err := db.CreateHostCredentialChallenge(ctx, admin, input.InstallationKeyThumbprint); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("denied installation obtained credentials", err)
	}
	var grants, receipts, audits, events int
	var retired bool
	if err := db.DB.QueryRowContext(ctx, `select
	 (select count(*) from runtime_grants where runtime_id=$1),
	 (select count(*) from enrollment_admin_denials where enrollment_id=$2),
	 (select count(*) from audit_log where runtime_id=$1 and action='enrollment.admin_deny'),
	 (select count(*) from control_outbox where topic='enrollment.state_changed' and payload->>'runtime_id'=$1::text and payload->>'state'='denied'),
	 (select r.revoked_at is not null and i.revoked_at is not null and h.state='revoked' from runtimes r join installations i on i.id=r.installation_id join hosts h on h.id=i.host_id where r.id=$1)`, created.RuntimeID, created.EnrollmentID).Scan(&grants, &receipts, &audits, &events, &retired); err != nil || grants != 0 || receipts != 1 || audits != 1 || events != 1 || !retired {
		t.Fatal("denial authority/evidence was not atomic and idempotent", err)
	}
	for _, query := range []string{
		`update enrollment_admin_denials set denied_at=denied_at+interval '1 second'`,
		`delete from enrollment_admin_denials`,
		`truncate enrollment_admin_denials`,
		`update enrollments set state='pending' where id='` + created.EnrollmentID + `'`,
	} {
		if _, err := db.DB.ExecContext(ctx, query); err == nil {
			t.Fatal("denial evidence could be erased or revived")
		}
	}
	down, err := os.ReadFile("../../../../deploy/sql/048_enrollment_admin_denials.down.sql")
	if err != nil {
		t.Fatal(err)
	}
	connection, err := db.DB.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err := connection.ExecContext(ctx, string(down)); err == nil {
		t.Fatal("rollback deleted denial evidence")
	}
	_, _ = connection.ExecContext(ctx, "rollback")
}

func TestEnrollmentAdminDenialPreservesApprovedSiblingAndFreshReenrollment(t *testing.T) {
	ctx, db, input, first := pendingActiveHostFixture(t)
	admin := UserIdentity{Issuer: input.Issuer, Subject: input.Subject}
	var proof string
	if err := db.DB.QueryRowContext(ctx, `select proof_key_thumbprint from devices where id=$1`, enrollmentAdminDevice).Scan(&proof); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ConfirmEnrollmentUser(ctx, admin, enrollmentAdminDevice, proof, first.EnrollmentID, input.Code); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ConfirmEnrollmentHost(ctx, admin, input.InstallationKeyThumbprint, first.EnrollmentID, input.Code, "22222222-2222-4222-8222-222222222222"); err != nil {
		t.Fatal(err)
	}
	connection := enrollmentConnection(first)
	if _, err := db.AttachRuntimeConnection(ctx, connection); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `update orgs set enrollment_admin_approval_required=true where id=$1`, input.OrganizationID); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `update org_memberships set role='admin' where user_id='22222222-2222-4222-8222-222222222222'`); err != nil {
		t.Fatal(err)
	}
	input.ProfileRef = "refused-profile"
	input.RequestID = approvalTestUUID(t)
	input.Code = validEnrollmentInput(t).Code
	sibling, err := db.CreateEnrollment(ctx, input)
	if err != nil {
		t.Fatal(err)
	}
	review, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, sibling.EnrollmentID)
	if err != nil {
		t.Fatal(err)
	}
	if review.ConfirmedUser != nil {
		t.Fatal("new request unexpectedly confirmed")
	}
	if _, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, sibling.EnrollmentID, review.ReviewSHA256); err != nil {
		t.Fatal("early administrator refusal failed", err)
	}
	if _, err := db.CreateHostCredentialChallenge(ctx, admin, input.InstallationKeyThumbprint); err != nil {
		t.Fatal("denial retired an approved sibling installation", err)
	}
	lease := RealtimeConnectionLease{Role: "installation", InstallationID: &first.InstallationID, RuntimeID: first.RuntimeID, RuntimeEpoch: &connection.RuntimeEpoch, ConnectionID: connection.ConnectionID, KeyVersion: 1}
	invalid, err := db.InvalidRealtimeConnectionLeases(ctx, []RealtimeConnectionLease{lease})
	if err != nil || len(invalid) != 0 {
		t.Fatal("approved sibling connection was invalidated", err)
	}
	input.RequestID = approvalTestUUID(t)
	input.Code = validEnrollmentInput(t).Code
	replacement, err := db.CreateEnrollment(ctx, input)
	if err != nil || replacement.RuntimeID == sibling.RuntimeID {
		t.Fatal("denied profile did not release its retired name", err)
	}
	if _, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, sibling.EnrollmentID, review.ReviewSHA256); err != nil {
		t.Fatal("historical denial retry failed", err)
	}
	var active bool
	var grants int
	if err := db.DB.QueryRowContext(ctx, `select revoked_at is null,(select count(*) from runtime_grants where runtime_id=$1) from runtimes where id=$1`, replacement.RuntimeID).Scan(&active, &grants); err != nil || !active || grants != 0 {
		t.Fatal("historical denial affected a replacement or moved grants", err)
	}
}

func TestEnrollmentAdminDenialRejectsStaleReviewAndCompetesWithApproval(t *testing.T) {
	ctx, db, _, created, admin, proof := adminEnrollmentFixture(t)
	review, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `update devices set key_version=key_version+1 where id=$1`, enrollmentPhoneDevice); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256); !errors.Is(err, ErrEnrollmentConflict) {
		t.Fatal("stale phone key review authorized denial", err)
	}
	fresh, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, "wrong-proof", created.EnrollmentID, fresh.ReviewSHA256); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("wrong administrator proof authorized denial", err)
	}
	// A separate fresh request tests concurrent opposite decisions.
	ctx, db, _, created, admin, proof = adminEnrollmentFixture(t)
	review, err = db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil {
		t.Fatal(err)
	}
	result := make(chan error, 2)
	go func() {
		_, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256)
		result <- err
	}()
	go func() {
		_, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256)
		result <- err
	}()
	successes := 0
	for range 2 {
		if <-result == nil {
			successes++
		}
	}
	if successes != 1 {
		t.Fatal("opposite administrator decisions did not have one winner", successes)
	}
	var state string
	var grants, denials int
	if err := db.DB.QueryRowContext(ctx, `select state,(select count(*) from runtime_grants where runtime_id=$2),(select count(*) from enrollment_admin_denials where enrollment_id=$1) from enrollments where id=$1`, created.EnrollmentID, created.RuntimeID).Scan(&state, &grants, &denials); err != nil {
		t.Fatal(err)
	}
	if state == "approved" {
		if grants != 2 || denials != 0 {
			t.Fatal("approval was mixed with denial")
		}
	} else if state == "denied" {
		if grants != 0 || denials != 1 {
			t.Fatal("denial was mixed with grants")
		}
	} else {
		t.Fatal("decision had no terminal state")
	}
}

func TestEnrollmentAdminDenialExpiryRollsBackRetirementAndEvidence(t *testing.T) {
	ctx, db, _, created, admin, proof := adminEnrollmentFixture(t)
	var expires time.Time
	if err := db.DB.QueryRowContext(ctx, `update enrollments set expires_at=clock_timestamp()+interval '700 milliseconds' where id=$1 returning expires_at`, created.EnrollmentID).Scan(&expires); err != nil {
		t.Fatal(err)
	}
	review, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `create function hold_denial_audit() returns trigger language plpgsql as $$ begin if new.action='enrollment.admin_deny' then perform pg_advisory_xact_lock(426985);end if;return new;end $$;
	 create trigger hold_denial_audit before insert on audit_log for each row execute function hold_denial_audit()`); err != nil {
		t.Fatal(err)
	}
	gate, err := db.DB.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer gate.Rollback()
	if _, err := gate.ExecContext(ctx, `select pg_advisory_xact_lock(426985)`); err != nil {
		t.Fatal(err)
	}
	result := make(chan error, 1)
	go func() {
		_, err := db.DenyEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256)
		result <- err
	}()
	waitForGrantLock(t, ctx, db, "insert into audit_log")
	waitForGrantDatabaseExpiry(t, ctx, db, expires)
	if err := gate.Commit(); err != nil {
		t.Fatal(err)
	}
	if err := <-result; !errors.Is(err, ErrEnrollmentInvalid) {
		t.Fatal("late denial escaped expiry", err)
	}
	var state string
	var receipts, audits int
	var active bool
	if err := db.DB.QueryRowContext(ctx, `select e.state,(select count(*) from enrollment_admin_denials where enrollment_id=e.id),
	 (select count(*) from audit_log where runtime_id=e.runtime_id and action='enrollment.admin_deny'),r.revoked_at is null and i.revoked_at is null and h.state='pending'
	 from enrollments e join runtimes r on r.id=e.runtime_id join installations i on i.id=e.installation_id join hosts h on h.id=e.host_id where e.id=$1`, created.EnrollmentID).Scan(&state, &receipts, &audits, &active); err != nil || state != "user_confirmed" || receipts != 0 || audits != 0 || !active {
		t.Fatal("expired denial left retirement/evidence", err)
	}
}
