package store

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"errors"
	"os"
	"sync"
	"testing"
	"time"

	"dsh-mobile-ustc/services/remote/internal/auth"
	"dsh-mobile-ustc/services/remote/internal/releases"
	"dsh-mobile-ustc/services/remote/internal/testsupport"
)

const releaseRecoveryEpoch = "88888888-8888-4888-8888-888888888888"

func releasePolicyRecovery(t *testing.T, ctx context.Context, db *Postgres, users []RecoveryUser) {
	t.Helper()
	const operation = "77777777-7777-4777-8777-777777777777"
	if _, err := db.QuarantineRecovery(ctx, operation, OriginalDatabaseEpoch, releaseRecoveryEpoch); err != nil {
		t.Fatal(err)
	}
	authority := RecoveryAuthority{Version: 1, Complete: true, RecoveryID: operation, DatabaseEpoch: releaseRecoveryEpoch, Issuer: "https://id.example.edu", CapturedAt: time.Now().UTC(), ExpiresAt: time.Now().Add(time.Minute).UTC(), Users: users}
	if _, err := db.ReleaseRecovery(ctx, releaseRecoveryEpoch, authority.Issuer, authority); err != nil {
		t.Fatal(err)
	}
}
func releaseRecoveryPhone(t *testing.T, ctx context.Context, db *Postgres) (string, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	approval, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	public, err := integrationP256JWK(key)
	if err != nil {
		t.Fatal(err)
	}
	approved, err := integrationP256JWK(approval)
	if err != nil {
		t.Fatal(err)
	}
	jkt, err := auth.PublicP256JWKThumbprintJSON(public)
	if err != nil {
		t.Fatal(err)
	}
	approvalJKT, err := auth.PublicP256JWKThumbprintJSON(approved)
	if err != nil {
		t.Fatal(err)
	}
	id, err := db.RegisterDevice(ctx, DeviceRegistration{Issuer: "https://id.example.edu", Subject: "owner-subject", Name: "New recovery phone", ProofPublicJWK: public, ProofKeyThumbprint: jkt, ApprovalPublicJWK: approved, ApprovalKeyThumbprint: approvalJKT})
	if err != nil {
		t.Fatal(err)
	}
	return id, jkt
}
func TestAppReleaseRestoreCannotReviveWithdrawnPolicy(t *testing.T) {
	ctx, source := newPostgresIntegrationDatabase(t)
	_ = seedGrantFixture(t, ctx, source.DB)
	base := releaseRegistryFixture(t, func(config map[string]any) {
		config["publications"].([]any)[0].(map[string]any)["organization_ids"] = []string{"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}
	})
	ref, _ := base.Policy()
	if err := source.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, base); err != nil {
		t.Fatal(err)
	}
	restoredCtx, restoredDB, _ := testsupport.RestoredPostgresDatabase(t, source.DB)
	restored := &Postgres{DB: restoredDB}
	// This trusted withdrawal occurred after the backup, and is absent in restore.
	withdrawn := releaseRegistryFixture(t, func(config map[string]any) { config["publications"] = []any{} })
	if _, err := source.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, "11111111-1111-4111-8111-111111111111", ref.SHA256, withdrawn); err != nil {
		t.Fatal(err)
	}
	if _, err := restored.NewRecoveryGuard(restoredCtx, releaseRecoveryEpoch); !errors.Is(err, ErrRecoveryUnavailable) {
		t.Fatal("restored old epoch was served as current", err)
	}
	releasePolicyRecovery(t, restoredCtx, restored, []RecoveryUser{{Subject: "owner-subject", Status: "active", Memberships: []RecoveryMembership{{OrganizationID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", Role: "member"}}}})
	status, err := restored.AppReleasePolicyStatus(restoredCtx, releaseRecoveryEpoch, ref.PackageName)
	if err != nil || !status.Configured || status.Current || status.ValidatedEpoch != OriginalDatabaseEpoch || status.PolicySHA256 != ref.SHA256 {
		t.Fatal("restore automatically accepted old policy", err)
	}
	if err = restored.RegisterAppReleaseCatalog(restoredCtx, releaseRecoveryEpoch, base); !errors.Is(err, ErrAppReleasePolicyConflict) {
		t.Fatal("ordinary startup revalidated restored policy", err)
	}
	device, proof := releaseRecoveryPhone(t, restoredCtx, restored)
	read := func(ref releases.PolicyRef) error {
		_, err := restored.AppReleaseAccess(restoredCtx, UserIdentity{Issuer: "https://id.example.edu", Subject: "owner-subject"}, device, proof, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", time.Now().Add(time.Minute), ref)
		return err
	}
	if err = read(ref); !errors.Is(err, ErrAppReleasePolicyStale) {
		t.Fatal("new valid phone could read stale recommendations", err)
	}
	if _, err = restored.ConfirmAppReleasePolicy(restoredCtx, releaseRecoveryEpoch, "22222222-2222-4222-8222-222222222222", ref.SHA256, withdrawn); !errors.Is(err, ErrDatabaseConfig) {
		t.Fatal("confirmation silently replaced policy", err)
	}
	if _, err = restored.ReplaceAppReleasePolicy(restoredCtx, releaseRecoveryEpoch, "33333333-3333-4333-8333-333333333333", ref.SHA256, withdrawn); err != nil {
		t.Fatal(err)
	}
	current, _ := withdrawn.Policy()
	if err = read(current); err != nil {
		t.Fatal("explicit current withdrawal unavailable", err)
	}
	if err = read(ref); !errors.Is(err, ErrAppReleasePolicyStale) {
		t.Fatal("withdrawn release became visible", err)
	}
	status, err = restored.AppReleasePolicyStatus(restoredCtx, releaseRecoveryEpoch, ref.PackageName)
	if err != nil || !status.Current || status.ValidatedEpoch != releaseRecoveryEpoch || status.Revision != 2 {
		t.Fatal("replacement did not establish current epoch", err)
	}
}
func TestAppReleaseConfirmationIsIdempotentAndDoesNotReplayOldPolicy(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	base := releaseRegistryFixture(t, nil)
	ref, _ := base.Policy()
	if err := db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, base); err != nil {
		t.Fatal(err)
	}
	releasePolicyRecovery(t, ctx, db, []RecoveryUser{})
	const id = "11111111-1111-4111-8111-111111111111"
	first, err := db.ConfirmAppReleasePolicy(ctx, releaseRecoveryEpoch, id, ref.SHA256, base)
	if err != nil || first.Revision != 1 {
		t.Fatal(err)
	}
	retry, err := db.ConfirmAppReleasePolicy(ctx, releaseRecoveryEpoch, id, ref.SHA256, base)
	if err != nil || retry != first {
		t.Fatal("lost output could not reconcile confirmation", err)
	}
	if err = db.RegisterAppReleaseCatalog(ctx, releaseRecoveryEpoch, base); err != nil {
		t.Fatal(err)
	}
	status, err := db.AppReleasePolicyStatus(ctx, releaseRecoveryEpoch, ref.PackageName)
	if err != nil || !status.Current || status.Revision != 1 {
		t.Fatal("confirmation rewrote policy", err)
	}
	withdrawn := releaseRegistryFixture(t, func(config map[string]any) { config["publications"] = []any{} })
	if _, err = db.ReplaceAppReleasePolicy(ctx, releaseRecoveryEpoch, "22222222-2222-4222-8222-222222222222", ref.SHA256, withdrawn); err != nil {
		t.Fatal(err)
	}
	retry, err = db.ConfirmAppReleasePolicy(ctx, releaseRecoveryEpoch, id, ref.SHA256, base)
	if err != nil || retry != first {
		t.Fatal("historical receipt no longer readable", err)
	}
	current, _ := withdrawn.Policy()
	status, err = db.AppReleasePolicyStatus(ctx, releaseRecoveryEpoch, ref.PackageName)
	if err != nil || status.PolicySHA256 != current.SHA256 || status.Revision != 2 {
		t.Fatal("old confirmation restored old policy", err)
	}
	if _, err = db.ConfirmAppReleasePolicy(ctx, releaseRecoveryEpoch, id, current.SHA256, withdrawn); !errors.Is(err, ErrAppReleasePolicyConflict) {
		t.Fatal("operation ID rebound", err)
	}
	var receipts, audits int
	if err = db.DB.QueryRowContext(ctx, `select (select count(*) from app_release_policy_confirmations),(select count(*) from audit_log where action='app.release.policy.confirm')`).Scan(&receipts, &audits); err != nil || receipts != 1 || audits != 1 {
		t.Fatal("confirmation duplicated facts", receipts, audits, err)
	}
	for _, sql := range []string{`update app_release_policy_confirmations set revision=revision+1`, `delete from app_release_policy_confirmations`, `truncate app_release_policy_confirmations`} {
		if _, err = db.DB.ExecContext(ctx, sql); err == nil {
			t.Fatal("confirmation was mutable", sql)
		}
	}
	down, err := os.ReadFile("../../../../deploy/sql/052_app_release_recovery_confirmation.down.sql")
	if err != nil {
		t.Fatal(err)
	}
	connection, err := db.DB.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err = connection.ExecContext(ctx, string(down)); err == nil {
		t.Fatal("rollback deleted recovery acceptance")
	}
	_, _ = connection.ExecContext(ctx, "rollback")
}
func TestAppReleaseConfirmationAuditFailureAndConcurrentAttempts(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	base := releaseRegistryFixture(t, nil)
	ref, _ := base.Policy()
	if err := db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, base); err != nil {
		t.Fatal(err)
	}
	releasePolicyRecovery(t, ctx, db, []RecoveryUser{})
	if _, err := db.DB.ExecContext(ctx, `create function block_policy_confirmation_audit() returns trigger language plpgsql as $$begin
 if new.action='app.release.policy.confirm' then return null; end if; return new; end$$;
 create trigger block_policy_confirmation_audit before insert on audit_log for each row execute function block_policy_confirmation_audit()`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ConfirmAppReleasePolicy(ctx, releaseRecoveryEpoch, "11111111-1111-4111-8111-111111111111", ref.SHA256, base); !errors.Is(err, ErrDatabaseUnavailable) {
		t.Fatal("missing audit admitted confirmation", err)
	}
	status, err := db.AppReleasePolicyStatus(ctx, releaseRecoveryEpoch, ref.PackageName)
	if err != nil || status.Current {
		t.Fatal("audit failure opened policy", err)
	}
	if _, err = db.DB.ExecContext(ctx, `update app_release_policies set validated_epoch='88888888-8888-4888-8888-888888888888'`); err == nil {
		t.Fatal("direct epoch update bypassed confirmation")
	}
	if _, err = db.DB.ExecContext(ctx, `drop trigger block_policy_confirmation_audit on audit_log`); err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	results := make(chan error, 2)
	var workers sync.WaitGroup
	for _, id := range []string{"11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"} {
		workers.Go(func() {
			<-start
			_, err := db.ConfirmAppReleasePolicy(ctx, releaseRecoveryEpoch, id, ref.SHA256, base)
			results <- err
		})
	}
	close(start)
	workers.Wait()
	close(results)
	success, conflicts := 0, 0
	for err := range results {
		if err == nil {
			success++
		} else if errors.Is(err, ErrAppReleasePolicyConflict) {
			conflicts++
		} else {
			t.Fatal(err)
		}
	}
	if success != 1 || conflicts != 1 {
		t.Fatal("concurrent confirmation was not exclusive", success, conflicts)
	}
}
func TestAppReleaseConfirmationMigrationDoesNotAcceptOldEpoch(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	base := releaseRegistryFixture(t, nil)
	ref, _ := base.Policy()
	if err := db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, base); err != nil {
		t.Fatal(err)
	}
	down, err := os.ReadFile("../../../../deploy/sql/052_app_release_recovery_confirmation.down.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.DB.ExecContext(ctx, string(down)); err != nil {
		t.Fatal(err)
	}
	releasePolicyRecovery(t, ctx, db, []RecoveryUser{})
	up, err := os.ReadFile("../../../../deploy/sql/052_app_release_recovery_confirmation.up.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.DB.ExecContext(ctx, string(up)); err != nil {
		t.Fatal(err)
	}
	status, err := db.AppReleasePolicyStatus(ctx, releaseRecoveryEpoch, ref.PackageName)
	if err != nil || status.Current || status.ValidatedEpoch != OriginalDatabaseEpoch {
		t.Fatal("migration blessed restored policy", err)
	}
	if _, err = db.ConfirmAppReleasePolicy(ctx, releaseRecoveryEpoch, "11111111-1111-4111-8111-111111111111", ref.SHA256, base); err != nil {
		t.Fatal(err)
	}
}
