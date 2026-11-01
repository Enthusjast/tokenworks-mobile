package store

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"dsh-mobile-ustc/services/remote/internal/releases"
)

func releaseRegistryFixture(t *testing.T, change func(map[string]any)) *releases.Catalog {
	t.Helper()
	raw, err := os.ReadFile("../../../../deploy/android/releases.example.json")
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]any
	if json.Unmarshal(raw, &config) != nil {
		t.Fatal("invalid fixture")
	}
	if change != nil {
		change(config)
	}
	raw, err = json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	catalog, err := releases.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	return catalog
}
func registryRelease(config map[string]any) map[string]any {
	return config["publications"].([]any)[0].(map[string]any)["release"].(map[string]any)
}
func registryCounts(t *testing.T, ctx context.Context, db *Postgres, want int) {
	t.Helper()
	var artifacts, audits int
	if err := db.DB.QueryRowContext(ctx, `select (select count(*) from app_release_artifacts),
  (select count(*) from audit_log where action='app.release.register')`).Scan(&artifacts, &audits); err != nil || artifacts != want || audits != want {
		t.Fatal("artifact/audit facts differ", artifacts, audits, err)
	}
}

func TestAppReleaseRegistryPinsArtifactAcrossRestartWithdrawalAndPolicyChanges(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	base := releaseRegistryFixture(t, nil)
	register := func(catalog *releases.Catalog) error {
		return (&Postgres{DB: db.DB}).RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, catalog)
	}
	if err := register(base); err != nil {
		t.Fatal(err)
	}
	if err := register(base); err != nil {
		t.Fatal(err)
	}
	changed := releaseRegistryFixture(t, func(config map[string]any) {
		config["download_origins"] = []string{"https://new.example.invalid"}
		config["supported_client_versions"].(map[string]any)["maximum"] = 3
		publication := config["publications"].([]any)[0].(map[string]any)
		publication["organization_ids"] = []string{"22222222-2222-4222-8222-222222222222"}
		release := registryRelease(config)
		release["download_url"] = "https://new.example.invalid/PRIVATE_URL.apk"
		release["release_notes"] = "PRIVATE_RELEASE_NOTES"
		release["rollout_basis_points"] = 0
	})
	if err := register(changed); !errors.Is(err, ErrAppReleasePolicyConflict) {
		t.Fatal("startup silently changed publication policy", err)
	}
	basePolicy, _ := base.Policy()
	if _, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, "11111111-1111-4111-8111-111111111111", basePolicy.SHA256, changed); err != nil {
		t.Fatal(err)
	}
	withdrawn := releaseRegistryFixture(t, func(config map[string]any) { config["publications"] = []any{} })
	changedPolicy, _ := changed.Policy()
	if _, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, "22222222-2222-4222-8222-222222222222", changedPolicy.SHA256, withdrawn); err != nil {
		t.Fatal(err)
	}
	if err := register(withdrawn); err != nil {
		t.Fatal(err)
	}
	for field, value := range map[string]any{"release_id": "88888888-8888-4888-8888-888888888888", "sha256": strings.Repeat("c", 64), "size_bytes": 1,
		"signing_cert_sha256": strings.Repeat("d", 64), "version_name": "changed", "minimum_android_api": 27, "maximum_protocol_version": 2} {
		catalog := releaseRegistryFixture(t, func(config map[string]any) { registryRelease(config)[field] = value })
		if err := register(catalog); !errors.Is(err, ErrAppReleaseConflict) {
			t.Fatalf("%s rebound an artifact: %v", field, err)
		}
	}
	for _, change := range []func(map[string]any){
		func(config map[string]any) {
			config["supported_client_versions"].(map[string]any)["maximum"] = 3
			registryRelease(config)["version_code"] = 3
		},
		func(config map[string]any) {
			config["package_name"] = "cn.edu.other"
			registryRelease(config)["package_name"] = "cn.edu.other"
		},
	} {
		if err := register(releaseRegistryFixture(t, change)); !errors.Is(err, ErrAppReleaseConflict) {
			t.Fatal("release ID rebound to a different package/version", err)
		}
	}
	withdrawnPolicy, _ := withdrawn.Policy()
	if _, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, "33333333-3333-4333-8333-333333333333", withdrawnPolicy.SHA256, base); err != nil {
		t.Fatal(err)
	}
	if err := register(base); err != nil {
		t.Fatal(err)
	}
	registryCounts(t, ctx, db, 1)
	var dump string
	if err := db.DB.QueryRowContext(ctx, `select coalesce(jsonb_agg(metadata)::text,'') from audit_log where action='app.release.register'`).Scan(&dump); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(dump, "PRIVATE_") || strings.Contains(dump, "download_url") || strings.Contains(dump, "organization_ids") {
		t.Fatal("publication content entered registry audit")
	}
}

func TestAppReleaseRegistryAtomicConflictAndAuditFailure(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	if err := db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, releaseRegistryFixture(t, nil)); err != nil {
		t.Fatal(err)
	}
	newer := func(config map[string]any) {
		config["supported_client_versions"].(map[string]any)["maximum"] = 3
		release := registryRelease(config)
		release["release_id"] = "88888888-8888-4888-8888-888888888888"
		release["version_code"] = 3
	}
	mixed := releaseRegistryFixture(t, func(config map[string]any) {
		original := config["publications"].([]any)[0].(map[string]any)
		raw, _ := json.Marshal(original)
		var next map[string]any
		_ = json.Unmarshal(raw, &next)
		registryRelease(config)["sha256"] = strings.Repeat("c", 64)
		next["release"].(map[string]any)["release_id"] = "88888888-8888-4888-8888-888888888888"
		next["release"].(map[string]any)["version_code"] = 3
		config["publications"] = []any{original, next}
		config["supported_client_versions"].(map[string]any)["maximum"] = 3
	})
	if err := db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, mixed); !errors.Is(err, ErrAppReleaseConflict) {
		t.Fatal("mixed catalog accepted", err)
	}
	registryCounts(t, ctx, db, 1)
	if _, err := db.DB.ExecContext(ctx, `create function suppress_release_audit() returns trigger language plpgsql as $$ begin
  if new.action='app.release.register' then return null; end if; return new; end $$;
  create trigger suppress_release_audit before insert on audit_log for each row execute function suppress_release_audit()`); err != nil {
		t.Fatal(err)
	}
	next := releaseRegistryFixture(t, newer)
	basePolicy, _ := releaseRegistryFixture(t, nil).Policy()
	if _, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, "11111111-1111-4111-8111-111111111111", basePolicy.SHA256, next); !errors.Is(err, ErrDatabaseUnavailable) {
		t.Fatal("missing audit did not abort registration", err)
	}
	registryCounts(t, ctx, db, 1)
	if _, err := db.DB.ExecContext(ctx, `drop trigger suppress_release_audit on audit_log`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ReplaceAppReleasePolicy(ctx, OriginalDatabaseEpoch, "11111111-1111-4111-8111-111111111111", basePolicy.SHA256, next); err != nil {
		t.Fatal(err)
	}
	registryCounts(t, ctx, db, 2)
}

func TestAppReleaseRegistryConcurrentConflictingNodesChooseOneIdentity(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	catalogs := []*releases.Catalog{releaseRegistryFixture(t, nil), releaseRegistryFixture(t, func(config map[string]any) { registryRelease(config)["sha256"] = strings.Repeat("c", 64) })}
	start := make(chan struct{})
	results := make(chan error, 2)
	var workers sync.WaitGroup
	for _, catalog := range catalogs {
		workers.Go(func() {
			<-start
			results <- (&Postgres{DB: db.DB}).RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, catalog)
		})
	}
	close(start)
	workers.Wait()
	close(results)
	successes, conflicts := 0, 0
	for err := range results {
		if err == nil {
			successes++
		} else if errors.Is(err, ErrAppReleaseConflict) {
			conflicts++
		} else {
			t.Fatal(err)
		}
	}
	if successes != 1 || conflicts != 1 {
		t.Fatal("conflicting nodes both registered", successes, conflicts)
	}
	registryCounts(t, ctx, db, 1)
}

func TestAppReleaseRegistryRecoveryFenceAndIrreversibleIdentity(t *testing.T) {
	ctx, db := newPostgresIntegrationDatabase(t)
	catalog := releaseRegistryFixture(t, nil)
	if err := db.RegisterAppReleaseCatalog(ctx, "88888888-8888-4888-8888-888888888888", catalog); !errors.Is(err, ErrRecoveryUnavailable) {
		t.Fatal("wrong deployment epoch accepted", err)
	}
	registryCounts(t, ctx, db, 0)
	if err := db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, catalog); err != nil {
		t.Fatal(err)
	}
	for _, query := range []string{`update app_release_artifacts set sha256=repeat('c',64)`, `delete from app_release_artifacts`, `truncate app_release_artifacts`} {
		if _, err := db.DB.ExecContext(ctx, query); err == nil {
			t.Fatal("immutable artifact changed", query)
		}
	}
	down, err := os.ReadFile("../../../../deploy/sql/050_app_release_artifacts.down.sql")
	if err != nil {
		t.Fatal(err)
	}
	connection, err := db.DB.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	if _, err = connection.ExecContext(ctx, string(down)); err == nil {
		t.Fatal("rollback deleted identity history")
	}
	if _, err = connection.ExecContext(ctx, "rollback"); err != nil {
		t.Fatal(err)
	}
	registryCounts(t, ctx, db, 1)

	// The normal quarantine/release flow must retain previous artifact identities.
	epoch, operation := "88888888-8888-4888-8888-888888888888", "77777777-7777-4777-8777-777777777777"
	if _, err = db.QuarantineRecovery(ctx, operation, OriginalDatabaseEpoch, epoch); err != nil {
		t.Fatal(err)
	}
	if err = db.RegisterAppReleaseCatalog(ctx, epoch, catalog); !errors.Is(err, ErrRecoveryUnavailable) {
		t.Fatal("quarantine admitted publication", err)
	}
	authority := RecoveryAuthority{Version: 1, Complete: true, RecoveryID: operation, DatabaseEpoch: epoch, Issuer: "https://id.example.edu", CapturedAt: time.Now().UTC(), ExpiresAt: time.Now().Add(time.Minute).UTC(), Users: []RecoveryUser{}}
	if _, err = db.ReleaseRecovery(ctx, epoch, authority.Issuer, authority); err != nil {
		t.Fatal(err)
	}
	if err = db.RegisterAppReleaseCatalog(ctx, OriginalDatabaseEpoch, catalog); !errors.Is(err, ErrRecoveryUnavailable) {
		t.Fatal("old epoch reused registry", err)
	}
	if err = db.RegisterAppReleaseCatalog(ctx, epoch, catalog); err != nil {
		t.Fatal("new epoch lost old identity", err)
	}

	registryCounts(t, ctx, db, 1)
}
