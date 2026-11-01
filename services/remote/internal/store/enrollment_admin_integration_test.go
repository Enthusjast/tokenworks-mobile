package store

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
	"time"
)

const enrollmentAdminDevice = "44444444-4444-4444-8444-444444444444"
const enrollmentPhoneDevice = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
const enrollmentPhoneUser = "66666666-6666-4666-8666-666666666666"

func adminEnrollmentFixture(t *testing.T) (context.Context, *Postgres, EnrollmentInput, EnrollmentCreated, UserIdentity, string) {
	t.Helper()
	ctx, db, input, created := enrollmentPreviewFixture(t)
	if _, err := db.DB.ExecContext(ctx, `update orgs set enrollment_admin_approval_required=true where id=$1`, input.OrganizationID); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `update org_memberships set role='admin' where user_id='22222222-2222-4222-8222-222222222222'`); err != nil {
		t.Fatal(err)
	}
	var proof string
	if err := db.DB.QueryRowContext(ctx, `select proof_key_thumbprint from devices where id=$1`, enrollmentAdminDevice).Scan(&proof); err != nil {
		t.Fatal(err)
	}
	owner := UserIdentity{Issuer: input.Issuer, Subject: input.Subject}
	if _, err := db.ConfirmEnrollmentUser(ctx, UserIdentity{Issuer: input.Issuer, Subject: "target-subject"}, enrollmentPhoneDevice, "proof-jkt-target", created.EnrollmentID, input.Code); err != nil {
		t.Fatal(err)
	}
	confirmation, err := db.ConfirmEnrollmentHost(ctx, owner, input.InstallationKeyThumbprint, created.EnrollmentID, input.Code, enrollmentPhoneUser)
	if err != nil || confirmation.State != "user_confirmed" || !confirmation.UserConfirmed || !confirmation.HostConfirmed {
		t.Fatal("local confirmation bypassed administrator policy", err)
	}
	return ctx, db, input, created, owner, proof
}

func TestEnrollmentAdminGateActivationAndIdempotentEvidence(t *testing.T) {
	ctx, db, input, created, admin, proof := adminEnrollmentFixture(t)
	var grants int
	if err := db.DB.QueryRowContext(ctx, `select count(*) from runtime_grants where runtime_id=$1`, created.RuntimeID).Scan(&grants); err != nil || grants != 0 {
		t.Fatal("pending administrator review created grants", err)
	}
	if _, err := db.CreateHostCredentialChallenge(ctx, admin, input.InstallationKeyThumbprint); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("pending installation received credentials", err)
	}
	if _, err := db.AttachRuntimeConnection(ctx, enrollmentConnection(created)); !errors.Is(err, ErrHostCredentialInvalid) {
		t.Fatal("pending runtime attached", err)
	}
	phone := UserIdentity{Issuer: input.Issuer, Subject: "target-subject"}
	if _, err := db.DB.ExecContext(ctx, `insert into org_memberships(org_id,user_id,role) values('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',$1,'admin')`, enrollmentPhoneUser); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ReviewEnrollmentAdmin(ctx, phone, enrollmentPhoneDevice, "proof-jkt-target", created.EnrollmentID); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("ordinary confirmer obtained administrator metadata", err)
	}
	review, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil || !review.Required || review.ConfirmedUser == nil || !review.ConfirmedUser.Eligible || !review.Owner.Eligible || len(review.ReviewSHA256) != 64 {
		t.Fatal("administrator review incomplete", err)
	}
	raw, _ := json.Marshal(review)
	if strings.Contains(string(raw), input.Code) {
		t.Fatal("administrator review disclosed pairing code")
	}
	if _, err := db.ApproveEnrollmentAdmin(ctx, phone, enrollmentPhoneDevice, "proof-jkt-target", created.EnrollmentID, review.ReviewSHA256); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("administrator from another organization approved enrollment", err)
	}
	if _, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, "wrong-proof", created.EnrollmentID); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("administrator device proof was not rechecked", err)
	}
	if _, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, strings.Repeat("0", 64)); !errors.Is(err, ErrEnrollmentConflict) {
		t.Fatal("unreviewed digest activated enrollment", err)
	}
	for range 2 {
		result, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256)
		if err != nil || result.State != "approved" {
			t.Fatal("administrator approval/retry failed", err)
		}
	}
	var audits, events int
	if err := db.DB.QueryRowContext(ctx, `select
	 (select count(*) from runtime_grants where runtime_id=$1),
	 (select count(*) from audit_log where runtime_id=$1 and action='enrollment.admin_approve'),
	 (select count(*) from control_outbox where topic='enrollment.state_changed' and payload->>'runtime_id'=$1::text and payload->>'state'='approved')`, created.RuntimeID).Scan(&grants, &audits, &events); err != nil || grants != 2 || audits != 1 || events != 1 {
		t.Fatal("approval was not atomic/idempotent", grants, audits, events, err)
	}
	if _, err := db.CreateHostCredentialChallenge(ctx, admin, input.InstallationKeyThumbprint); err != nil {
		t.Fatal("approved installation cannot acquire credentials", err)
	}
	if _, err := db.AttachRuntimeConnection(ctx, enrollmentConnection(created)); err != nil {
		t.Fatal("approved runtime cannot attach", err)
	}
	if _, err := db.DB.ExecContext(ctx, `update org_memberships set role='member' where user_id='22222222-2222-4222-8222-222222222222'`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("historical approval retained lost admin authority", err)
	}
}

func TestEnrollmentAdminGateRejectsOldNodesAndCannotBeRelaxedForPendingRequests(t *testing.T) {
	ctx, db, input, created, _, _ := adminEnrollmentFixture(t)
	input.RequestID = approvalTestUUID(t)
	input.ProfileRef = "after-policy"
	input.Code = validEnrollmentInput(t).Code
	after, err := db.CreateEnrollment(ctx, input)
	if err != nil {
		t.Fatal(err)
	}
	var captured bool
	if err := db.DB.QueryRowContext(ctx, `select admin_approval_required from enrollments where id=$1`, after.EnrollmentID).Scan(&captured); err != nil || !captured {
		t.Fatal("new enrollment did not capture database policy", err)
	}
	// Simulate an older node's final UPDATE, which has no admin columns.
	if _, err := db.DB.ExecContext(ctx, `update enrollments set state='approved',decided_at=clock_timestamp() where id=$1`, created.EnrollmentID); err == nil {
		t.Fatal("legacy node bypassed database administrator gate")
	}
	if _, err := db.DB.ExecContext(ctx, `update orgs set enrollment_admin_approval_required=false where id=$1`, input.OrganizationID); err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `update enrollments set admin_approval_required=false where id=$1`, created.EnrollmentID); err != nil {
		t.Fatal(err)
	}
	var required bool
	if err := db.DB.QueryRowContext(ctx, `select admin_approval_required from enrollments where id=$1`, created.EnrollmentID).Scan(&required); err != nil || !required {
		t.Fatal("policy relaxation released an existing request", err)
	}
	down, err := os.ReadFile("../../../../deploy/sql/047_enrollment_admin_approval.down.sql")
	if err != nil {
		t.Fatal(err)
	}
	connection, err := db.DB.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err := connection.ExecContext(ctx, string(down)); err == nil {
		t.Fatal("rollback removed latched policy evidence")
	}
	_, _ = connection.ExecContext(ctx, "rollback")
}

func TestEnrollmentAdminReviewCannotSurvivePhoneKeyReconfirmation(t *testing.T) {
	ctx, db, input, created, admin, proof := adminEnrollmentFixture(t)
	old, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.DB.ExecContext(ctx, `update devices set key_version=key_version+1 where id=$1`, enrollmentPhoneDevice); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, old.ReviewSHA256); !errors.Is(err, ErrAccessDenied) {
		t.Fatal("rotated confirming key remained eligible", err)
	}
	if _, err := db.ConfirmEnrollmentUser(ctx, UserIdentity{Issuer: input.Issuer, Subject: "target-subject"}, enrollmentPhoneDevice, "proof-jkt-target", created.EnrollmentID, input.Code); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, old.ReviewSHA256); !errors.Is(err, ErrEnrollmentInvalid) {
		t.Fatal("old host consent survived reconfirmation", err)
	}
	if _, err := db.ConfirmEnrollmentHost(ctx, admin, input.InstallationKeyThumbprint, created.EnrollmentID, input.Code, enrollmentPhoneUser); err != nil {
		t.Fatal(err)
	}
	fresh, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
	if err != nil || fresh.ReviewSHA256 == old.ReviewSHA256 {
		t.Fatal("review failed to bind device/key/consent generation", err)
	}
	if _, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, old.ReviewSHA256); !errors.Is(err, ErrEnrollmentConflict) {
		t.Fatal("stale review activated new confirmation", err)
	}
	if _, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, fresh.ReviewSHA256); err != nil {
		t.Fatal(err)
	}
}

func TestEnrollmentAdminIdentityPolicyOmissionPreservesGate(t *testing.T) {
	ctx, db, _, created := enrollmentPreviewFixture(t)
	required, optional := true, false
	for revision, policy := range []*bool{&required, nil, &optional} {
		snapshot := identitySnapshotFixture(t, int64(revision+1))
		snapshot.Organizations[0].EnrollmentAdminApprovalRequired = policy
		if _, err := db.ApplyIdentitySnapshot(ctx, snapshot.DatabaseEpoch, snapshot.Issuer, snapshot); err != nil {
			t.Fatal("identity policy update failed", revision, err)
		}
		var current, pending bool
		if err := db.DB.QueryRowContext(ctx, `select o.enrollment_admin_approval_required,e.admin_approval_required from orgs o join hosts h on h.org_id=o.id join enrollments e on e.host_id=h.id where e.id=$1`, created.EnrollmentID).Scan(&current, &pending); err != nil || current != (revision < 2) || !pending {
			t.Fatal("omitted policy or relaxation weakened pending gate", revision, current, pending, err)
		}
	}
}

func TestEnrollmentAdminApprovalExpiryRollsBackDeviceAndAuditWaits(t *testing.T) {
	for _, operation := range []string{"admin-device", "audit"} {
		t.Run(operation, func(t *testing.T) {
			ctx, db, _, created, admin, proof := adminEnrollmentFixture(t)
			var expires time.Time
			if err := db.DB.QueryRowContext(ctx, `update enrollments set expires_at=clock_timestamp()+interval '700 milliseconds' where id=$1 returning expires_at`, created.EnrollmentID).Scan(&expires); err != nil {
				t.Fatal(err)
			}
			review, err := db.ReviewEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID)
			if err != nil {
				t.Fatal(err)
			}
			if operation == "audit" {
				if _, err := db.DB.ExecContext(ctx, `create function hold_admin_audit() returns trigger language plpgsql as $$ begin if new.action='enrollment.admin_approve' then perform pg_advisory_xact_lock(426973);end if;return new;end $$;
				 create trigger hold_admin_audit before insert on audit_log for each row execute function hold_admin_audit()`); err != nil {
					t.Fatal(err)
				}
			}
			gate, err := db.DB.BeginTx(ctx, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer gate.Rollback()
			query, fragment := `select pg_advisory_xact_lock(426973)`, "insert into audit_log"
			if operation == "admin-device" {
				query = `select 1 from devices where id='` + enrollmentAdminDevice + `' for update`
				fragment = "select d.key_version from devices d"
			}
			if _, err := gate.ExecContext(ctx, query); err != nil {
				t.Fatal(err)
			}
			result := make(chan error, 1)
			go func() {
				_, err := db.ApproveEnrollmentAdmin(ctx, admin, enrollmentAdminDevice, proof, created.EnrollmentID, review.ReviewSHA256)
				result <- err
			}()
			waitForGrantLock(t, ctx, db, fragment)
			waitForGrantDatabaseExpiry(t, ctx, db, expires)
			if err := gate.Commit(); err != nil {
				t.Fatal(err)
			}
			if err := <-result; !errors.Is(err, ErrEnrollmentInvalid) {
				t.Fatal("late administrator approval survived a wait", err)
			}
			var state string
			var grants, audits int
			if err := db.DB.QueryRowContext(ctx, `select state,(select count(*) from runtime_grants where runtime_id=$2),(select count(*) from audit_log where runtime_id=$2 and action='enrollment.admin_approve') from enrollments where id=$1`, created.EnrollmentID, created.RuntimeID).Scan(&state, &grants, &audits); err != nil || state != "user_confirmed" || grants != 0 || audits != 0 {
				t.Fatal("expired approval left authority/evidence", state, grants, audits, err)
			}
		})
	}
}
