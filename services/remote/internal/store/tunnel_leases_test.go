package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"

	"tokenworks-mobile/services/remote/internal/testsupport"
)

const (
	tunnelLeaseTestUser         = "11111111-1111-4111-8111-111111111111"
	tunnelLeaseTestOtherUser    = "22222222-2222-4222-8222-222222222222"
	tunnelLeaseTestOrg          = "33333333-3333-4333-8333-333333333333"
	tunnelLeaseTestHost         = "44444444-4444-4444-8444-444444444444"
	tunnelLeaseTestInstallation = "55555555-5555-4555-8555-555555555555"
	tunnelLeaseTestRuntime      = "66666666-6666-4666-8666-666666666666"
	tunnelLeaseTestDevice       = "77777777-7777-4777-8777-777777777777"
	tunnelLeaseTestBindings     = `[{"resource":{"kind":"session","id":"native-session"},"actions":["session/list"]}]`
)

type tunnelLeaseFixture struct {
	ctx   context.Context
	db    *sql.DB
	store *Postgres
	actor UserIdentity
}

func newTunnelLeaseFixture(t *testing.T) tunnelLeaseFixture {
	t.Helper()
	ctx, db := testsupport.PostgresDatabase(t)
	fixture := tunnelLeaseFixture{
		ctx: ctx, db: db, store: &Postgres{DB: db},
		actor: UserIdentity{Issuer: "https://issuer.example", Subject: "lease-user"},
	}
	fixture.exec(t, `insert into users(id,issuer,subject) values
		($1,'https://issuer.example','lease-user'),($2,'https://issuer.example','installation-owner')`,
		tunnelLeaseTestUser, tunnelLeaseTestOtherUser)
	fixture.exec(t, `insert into orgs(id,slug,name) values($1,'tunnel-lease-proof','Tunnel lease proof')`, tunnelLeaseTestOrg)
	fixture.exec(t, `insert into org_memberships(org_id,user_id,role) values($1,$2,'member'),($1,$3,'member')`,
		tunnelLeaseTestOrg, tunnelLeaseTestUser, tunnelLeaseTestOtherUser)
	fixture.exec(t, `insert into hosts(id,org_id,alias,state) values($1,$2,'Lease host','active')`,
		tunnelLeaseTestHost, tunnelLeaseTestOrg)
	fixture.exec(t, `insert into devices(id,user_id,name,approval_public_jwk,approval_key_thumbprint,proof_public_jwk,proof_key_thumbprint)
		values($1,$2,'Lease phone','{}',$3,'{}',$4)`,
		tunnelLeaseTestDevice, tunnelLeaseTestUser, strings.Repeat("A", 43), strings.Repeat("B", 43))
	fixture.exec(t, `insert into installations(id,host_id,owner_user_id,public_jwk,public_key_thumbprint)
		values($1,$2,$3,'{}',$4)`,
		tunnelLeaseTestInstallation, tunnelLeaseTestHost, tunnelLeaseTestOtherUser, strings.Repeat("C", 43))
	fixture.exec(t, `insert into runtimes(id,installation_id,profile_ref,dsh_version) values($1,$2,'lease-proof','0.2.1-rc.5')`,
		tunnelLeaseTestRuntime, tunnelLeaseTestInstallation)
	fixture.exec(t, `insert into runtime_grants(runtime_id,user_id,role,bindings,granted_by) values($1,$2,'viewer',$3::jsonb,$4)`,
		tunnelLeaseTestRuntime, tunnelLeaseTestUser, tunnelLeaseTestBindings, tunnelLeaseTestOtherUser)
	return fixture
}

func (fixture tunnelLeaseFixture) exec(t *testing.T, query string, args ...any) {
	t.Helper()
	if _, err := fixture.db.ExecContext(fixture.ctx, query, args...); err != nil {
		t.Fatalf("prepare tunnel lease facts: %v", err)
	}
}

func (fixture tunnelLeaseFixture) issue(t *testing.T) TunnelLease {
	t.Helper()
	lease, err := fixture.store.IssueTunnelLease(fixture.ctx, fixture.actor, tunnelLeaseTestDevice, tunnelLeaseTestRuntime)
	if err != nil {
		t.Fatalf("issue tunnel lease: %v", err)
	}
	return lease
}

func (fixture tunnelLeaseFixture) assertFacts(t *testing.T, lease TunnelLease, role string, ownsInstallation bool) {
	t.Helper()
	inspected, err := fixture.store.InspectTunnelLease(fixture.ctx, tunnelLeaseTestInstallation, lease.ID, lease.Secret)
	if err != nil {
		t.Fatalf("inspect tunnel lease: %v", err)
	}
	if inspected.LeaseID != lease.ID || inspected.UserID != tunnelLeaseTestUser || inspected.DeviceID != tunnelLeaseTestDevice ||
		inspected.RuntimeID != tunnelLeaseTestRuntime || inspected.InstallationID != tunnelLeaseTestInstallation || inspected.OrganizationID != tunnelLeaseTestOrg ||
		inspected.GrantVersion != lease.GrantVersion || !inspected.ExpiresAt.Equal(lease.ExpiresAt) {
		t.Fatalf("lease identity changed: %+v", inspected)
	}
	if inspected.Role != role || inspected.IsInstallationOwner != ownsInstallation {
		t.Fatalf("authorization facts: role=%q owner=%t, want role=%q owner=%t", inspected.Role, inspected.IsInstallationOwner, role, ownsInstallation)
	}
	wantBindings := []GrantBinding{{Resource: GrantResource{Kind: "session", ID: "native-session"}, Actions: []string{"session/list"}}}
	if !reflect.DeepEqual(inspected.Bindings, wantBindings) {
		t.Fatalf("native bindings changed: %+v", inspected.Bindings)
	}
	verified, err := fixture.store.VerifyTunnelLease(fixture.ctx, fixture.actor, tunnelLeaseTestDevice, tunnelLeaseTestRuntime, lease.Secret)
	if err != nil {
		t.Fatalf("verify tunnel lease: %v", err)
	}
	if !reflect.DeepEqual(verified, inspected) {
		t.Fatalf("phone admission and installation inspection disagree: verify=%+v inspect=%+v", verified, inspected)
	}
	raw, err := json.Marshal(inspected)
	if err != nil {
		t.Fatal(err)
	}
	var wire map[string]json.RawMessage
	if err := json.Unmarshal(raw, &wire); err != nil {
		t.Fatal(err)
	}
	wantOwner := "false"
	if ownsInstallation {
		wantOwner = "true"
	}
	if string(wire["role"]) != `"`+role+`"` || string(wire["is_installation_owner"]) != wantOwner || string(wire["organization_id"]) != `"`+tunnelLeaseTestOrg+`"` {
		t.Fatalf("wire response lost independent authoritative facts: %s", raw)
	}
}

func (fixture tunnelLeaseFixture) assertInvalid(t *testing.T, lease TunnelLease) {
	t.Helper()
	if _, err := fixture.store.InspectTunnelLease(fixture.ctx, tunnelLeaseTestInstallation, lease.ID, lease.Secret); !errors.Is(err, ErrTunnelLeaseInvalid) {
		t.Fatalf("installation inspection accepted stale authorization: %v", err)
	}
	if _, err := fixture.store.VerifyTunnelLease(fixture.ctx, fixture.actor, tunnelLeaseTestDevice, tunnelLeaseTestRuntime, lease.Secret); !errors.Is(err, ErrTunnelLeaseInvalid) {
		t.Fatalf("phone admission accepted stale authorization: %v", err)
	}
}

func TestTunnelLeaseRoleAndInstallationOwnershipAreIndependent(t *testing.T) {
	fixture := newTunnelLeaseFixture(t)
	for _, role := range []string{"viewer", "operator", "owner"} {
		for _, ownership := range []struct {
			name  string
			user  string
			owned bool
		}{
			{name: "non-owner", user: tunnelLeaseTestOtherUser, owned: false},
			{name: "actual-owner", user: tunnelLeaseTestUser, owned: true},
		} {
			t.Run(role+"/"+ownership.name, func(t *testing.T) {
				fixture.exec(t, `update runtime_grants set role=$1 where runtime_id=$2 and user_id=$3`, role, tunnelLeaseTestRuntime, tunnelLeaseTestUser)
				fixture.exec(t, `update installations set owner_user_id=$1 where id=$2`, ownership.user, tunnelLeaseTestInstallation)
				fixture.assertFacts(t, fixture.issue(t), role, ownership.owned)
			})
		}
	}
}

func TestTunnelLeaseReadsCurrentRoleAndInstallationOwner(t *testing.T) {
	fixture := newTunnelLeaseFixture(t)
	lease := fixture.issue(t)
	fixture.assertFacts(t, lease, "viewer", false)
	// Keep the version fixed to prove these facts come from the current grant
	// and installation, not from issuance or a role-derived owner shortcut.
	fixture.exec(t, `update runtime_grants set role='operator' where runtime_id=$1 and user_id=$2`, tunnelLeaseTestRuntime, tunnelLeaseTestUser)
	fixture.assertFacts(t, lease, "operator", false)
	fixture.exec(t, `update runtime_grants set role='owner' where runtime_id=$1 and user_id=$2`, tunnelLeaseTestRuntime, tunnelLeaseTestUser)
	fixture.assertFacts(t, lease, "owner", false)
	fixture.exec(t, `update installations set owner_user_id=$1 where id=$2`, tunnelLeaseTestUser, tunnelLeaseTestInstallation)
	fixture.assertFacts(t, lease, "owner", true)
	fixture.exec(t, `update runtime_grants set role='viewer' where runtime_id=$1 and user_id=$2`, tunnelLeaseTestRuntime, tunnelLeaseTestUser)
	fixture.assertFacts(t, lease, "viewer", true)
	fixture.exec(t, `update installations set owner_user_id=$1 where id=$2`, tunnelLeaseTestOtherUser, tunnelLeaseTestInstallation)
	fixture.assertFacts(t, lease, "viewer", false)

	fixture.exec(t, `update runtime_grants set role='operator',grant_version=grant_version+1 where runtime_id=$1 and user_id=$2`, tunnelLeaseTestRuntime, tunnelLeaseTestUser)
	fixture.assertInvalid(t, lease)
	replacement := fixture.issue(t)
	if replacement.GrantVersion != lease.GrantVersion+1 {
		t.Fatalf("replacement lease kept old grant version: old=%d new=%d", lease.GrantVersion, replacement.GrantVersion)
	}
	fixture.assertFacts(t, replacement, "operator", false)
}

func TestTunnelLeaseCurrentAuthorizationMustRemainValid(t *testing.T) {
	fixture := newTunnelLeaseFixture(t)
	lease := fixture.issue(t)
	for _, change := range []struct {
		name    string
		query   string
		restore string
		id      string
	}{
		{name: "grant-revoked", query: `update runtime_grants set revoked_at=clock_timestamp() where runtime_id=$1`, restore: `update runtime_grants set revoked_at=null where runtime_id=$1`, id: tunnelLeaseTestRuntime},
		{name: "grant-expired", query: `update runtime_grants set expires_at=clock_timestamp()-interval '1 second' where runtime_id=$1`, restore: `update runtime_grants set expires_at=null where runtime_id=$1`, id: tunnelLeaseTestRuntime},
		{name: "runtime-revoked", query: `update runtimes set revoked_at=clock_timestamp() where id=$1`, restore: `update runtimes set revoked_at=null where id=$1`, id: tunnelLeaseTestRuntime},
		{name: "installation-revoked", query: `update installations set revoked_at=clock_timestamp() where id=$1`, restore: `update installations set revoked_at=null where id=$1`, id: tunnelLeaseTestInstallation},
		{name: "host-revoked", query: `update hosts set state='revoked' where id=$1`, restore: `update hosts set state='active' where id=$1`, id: tunnelLeaseTestHost},
		{name: "membership-removed", query: `delete from org_memberships where user_id=$1`, restore: `insert into org_memberships(org_id,user_id,role) values('` + tunnelLeaseTestOrg + `',$1,'member')`, id: tunnelLeaseTestUser},
		{name: "device-key-changed", query: `update devices set key_version=key_version+1 where id=$1`, restore: `update devices set key_version=key_version-1 where id=$1`, id: tunnelLeaseTestDevice},
	} {
		t.Run(change.name, func(t *testing.T) {
			fixture.exec(t, change.query, change.id)
			defer fixture.exec(t, change.restore, change.id)
			fixture.assertInvalid(t, lease)
		})
	}
	fixture.assertFacts(t, lease, "viewer", false)
}

func TestTunnelLeaseFactsStayBoundToInstallationAndPrincipal(t *testing.T) {
	fixture := newTunnelLeaseFixture(t)
	lease := fixture.issue(t)
	if _, err := fixture.store.InspectTunnelLease(fixture.ctx, tunnelLeaseTestHost, lease.ID, lease.Secret); !errors.Is(err, ErrTunnelLeaseInvalid) {
		t.Fatalf("another installation inspected lease facts: %v", err)
	}
	if _, err := fixture.store.InspectTunnelLease(fixture.ctx, tunnelLeaseTestInstallation, lease.ID, strings.Repeat("A", 43)); !errors.Is(err, ErrTunnelLeaseInvalid) {
		t.Fatalf("wrong secret inspected lease facts: %v", err)
	}
	other := UserIdentity{Issuer: fixture.actor.Issuer, Subject: "installation-owner"}
	if _, err := fixture.store.VerifyTunnelLease(fixture.ctx, other, tunnelLeaseTestDevice, tunnelLeaseTestRuntime, lease.Secret); !errors.Is(err, ErrTunnelLeaseInvalid) {
		t.Fatalf("actual installation owner impersonated lease user: %v", err)
	}
	fixture.assertFacts(t, lease, "viewer", false)
}
