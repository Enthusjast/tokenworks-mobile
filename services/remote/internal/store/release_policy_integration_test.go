package store

import (
	"dsh-mobile-ustc/services/remote/internal/releases"
	"encoding/json"
	"errors"
	"os"
	"sync"
	"testing"
	"time"
)

func TestAppReleasePolicyCASReceiptsAndStaleNodes(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	base := releaseRegistryFixture(t, nil)
	ref, _ := base.Policy()
	status, err := db.AppReleasePolicyStatus(ctx, OriginalDatabaseEpoch, ref.PackageName)
	if err != nil || status.Configured {
		t.Fatal("unconfigured policy", err)
	}
	if err = db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, base); err != nil {
		t.Fatal(err)
	}
	status, err = db.AppReleasePolicyStatus(ctx, OriginalDatabaseEpoch, ref.PackageName)
	if err != nil || status.Revision != 1 || status.PolicySHA256 != ref.SHA256 {
		t.Fatal("initial policy not pinned", err)
	}
	proof := seedGrantFixture(t, ctx, db.DB)
	read := func(catalogRef string) error {
		_, err := db.AppReleaseAccess(ctx, UserIdentity{Issuer: "https://id.example.edu", Subject: "owner-subject"}, "44444444-4444-4444-8444-444444444444", proof, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", time.Now().Add(time.Minute), releases.PolicyRef{PackageName: ref.PackageName, SHA256: catalogRef})
		return err
	}
	if err = read(ref.SHA256); err != nil {
		t.Fatal(err)
	}
	withdrawn := releaseRegistryFixture(t, func(config map[string]any) { config["publications"] = []any{} })
	empty, _ := withdrawn.Policy()
	const operation = "11111111-1111-4111-8111-111111111111"
	receipt, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, operation, ref.SHA256, withdrawn)
	if err != nil || receipt.Revision != 2 {
		t.Fatal(err)
	}
	if err = read(ref.SHA256); !errors.Is(err, ErrAppReleasePolicyStale) {
		t.Fatal("old node still admitted publication", err)
	}
	if err = read(empty.SHA256); err != nil {
		t.Fatal("withdrawn policy unavailable", err)
	}
	if err = db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, base); !errors.Is(err, ErrAppReleasePolicyConflict) {
		t.Fatal("stale restart accepted", err)
	}
	if err = db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, withdrawn); err != nil {
		t.Fatal(err)
	}
	restored, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, "22222222-2222-4222-8222-222222222222", empty.SHA256, base)
	if err != nil || restored.Revision != 3 {
		t.Fatal(err)
	}
	retry, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, operation, ref.SHA256, withdrawn)
	if err != nil || retry != receipt {
		t.Fatal("historical retry did not reconcile receipt", err)
	}
	if err = read(ref.SHA256); err != nil {
		t.Fatal("old operation replay restored old policy", err)
	}
	if _, err = db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, operation, empty.SHA256, base); !errors.Is(err, ErrAppReleasePolicyConflict) {
		t.Fatal("operation ID rebound", err)
	}
	var changes, audits int
	if err = db.DB.QueryRowContext(ctx, `select (select count(*) from app_release_policy_changes),(select count(*) from audit_log where action='app.release.policy.replace')`).Scan(&changes, &audits); err != nil || changes != 2 || audits != 2 {
		t.Fatal("duplicate operation/audit", changes, audits, err)
	}
	epoch := "88888888-8888-4888-8888-888888888888"
	recoveryID := "77777777-7777-4777-8777-777777777777"
	if _, err = db.QuarantineRecovery(ctx, recoveryID, OriginalDatabaseEpoch, epoch); err != nil {
		t.Fatal(err)
	}
	if _, err = db.ReplaceAppReleasePolicy(ctx, epoch, operation, ref.SHA256, withdrawn); !errors.Is(err, ErrRecoveryUnavailable) {
		t.Fatal("quarantine admitted policy change", err)
	}
	authority := RecoveryAuthority{Version: 1, Complete: true, RecoveryID: recoveryID, DatabaseEpoch: epoch, Issuer: "https://id.example.edu", CapturedAt: time.Now(), ExpiresAt: time.Now().Add(time.Minute), Users: []RecoveryUser{}}
	if _, err = db.ReleaseRecovery(ctx, epoch, authority.Issuer, authority); err != nil {
		t.Fatal(err)
	}
	if _, err = db.ReplaceAppReleasePolicy(ctx, epoch, operation, ref.SHA256, withdrawn); !errors.Is(err, ErrAppReleasePolicyConflict) {
		t.Fatal("old epoch receipt accepted as current operation", err)
	}

}
func TestAppReleasePolicyAtomicAuditFailureAndConcurrentCAS(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	base := releaseRegistryFixture(t, nil)
	ref, _ := base.Policy()
	if err := db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, base); err != nil {
		t.Fatal(err)
	}
	candidate := releaseRegistryFixture(t, func(config map[string]any) {
		config["supported_client_versions"].(map[string]any)["maximum"] = 3
		release := registryRelease(config)
		release["release_notes"] = "PRIVATE_RELEASE_NOTE"
		release["release_id"] = "88888888-8888-4888-8888-888888888888"
		release["version_code"] = 3
	})
	if _, err := db.DB.ExecContext(ctx, `create function block_release_policy_audit() returns trigger language plpgsql as $$begin
 if new.action='app.release.policy.replace' then return null; end if; return new; end$$;
 create trigger block_release_policy_audit before insert on audit_log for each row execute function block_release_policy_audit()`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, "11111111-1111-4111-8111-111111111111", ref.SHA256, candidate); !errors.Is(err, ErrDatabaseUnavailable) {
		t.Fatal("missing audit accepted", err)
	}
	status, err := db.AppReleasePolicyStatus(ctx, OriginalDatabaseEpoch, ref.PackageName)
	if err != nil || status.Revision != 1 || status.PolicySHA256 != ref.SHA256 {
		t.Fatal("audit failure changed policy", err)
	}
	registryCounts(t, ctx, db, 1)
	if _, err = db.DB.ExecContext(ctx, `drop trigger block_release_policy_audit on audit_log`); err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	results := make(chan error, 2)
	var workers sync.WaitGroup
	for _, id := range []string{"11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"} {
		workers.Go(func() {
			<-start
			_, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, id, ref.SHA256, candidate)
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
		t.Fatal("CAS lost concurrency", success, conflicts)
	}
	registryCounts(t, ctx, db, 2)
	var audit string
	if err = db.DB.QueryRowContext(ctx, `select jsonb_agg(metadata)::text from audit_log where action like 'app.release.policy.%'`).Scan(&audit); err != nil {
		t.Fatal(err)
	}
	var rows []map[string]any
	if json.Unmarshal([]byte(audit), &rows) != nil {
		t.Fatal("invalid audit")
	}
	for _, row := range rows {
		if len(row) > 6 || row["release_notes"] != nil || row["download_url"] != nil {
			t.Fatal("audit persisted publication content")
		}
	}
	for _, sql := range []string{`delete from app_release_policies`, `truncate app_release_policies cascade`, `delete from app_release_policy_changes`, `truncate app_release_policy_changes`, `update app_release_policy_changes set revision=revision+1`, `update app_release_policies set policy_sha256=decode(repeat('a',64),'hex'),revision=revision+1`} {
		if _, err = db.DB.ExecContext(ctx, sql); err == nil {
			t.Fatal("policy history bypassed", sql)
		}
	}
	down, err := os.ReadFile("../../../../deploy/sql/051_app_release_policy.down.sql")
	if err != nil {
		t.Fatal(err)
	}
	connection, err := db.DB.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err = connection.ExecContext(ctx, string(down)); err == nil {
		t.Fatal("rollback erased policy history")
	}
	_, _ = connection.ExecContext(ctx, "rollback")
}

func TestAppReleasePolicyInitialAuditFailureRollsBackArtifactRegistration(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	if _, err := db.DB.ExecContext(ctx, `create function suppress_initial_release_policy_audit() returns trigger language plpgsql as $$begin
 if new.action='app.release.policy.register' then return null; end if; return new; end$$;
 create trigger suppress_initial_release_policy_audit before insert on audit_log for each row execute function suppress_initial_release_policy_audit()`); err != nil {
		t.Fatal(err)
	}
	catalog := releaseRegistryFixture(t, nil)
	ref, _ := catalog.Policy()
	if err := db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, catalog); !errors.Is(err, ErrDatabaseUnavailable) {
		t.Fatal("initial policy audit failure accepted", err)
	}
	registryCounts(t, ctx, db, 0)
	status, err := db.AppReleasePolicyStatus(ctx, OriginalDatabaseEpoch, ref.PackageName)
	if err != nil || status.Configured {
		t.Fatal("partial policy survived rollback", err)
	}
}
