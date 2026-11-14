package store

import (
	"strings"
	"testing"

	"tokenworks-mobile/services/remote/internal/testsupport"
)

func TestHostManagementExistingAPIsFenceProofAndRetireExactResources(t *testing.T) {
	ctx, db := testsupport.PostgresDatabase(t)
	const user = "11111111-1111-4111-8111-111111111111"
	const org = "22222222-2222-4222-8222-222222222222"
	const host = "33333333-3333-4333-8333-333333333333"
	const installation = "44444444-4444-4444-8444-444444444444"
	const runtimeA = "55555555-5555-4555-8555-555555555555"
	const runtimeB = "66666666-6666-4666-8666-666666666666"
	const runtimeC = "77777777-7777-4777-8777-777777777777"
	const device = "88888888-8888-4888-8888-888888888888"
	const secondDevice = "99999999-9999-4999-8999-999999999999"
	proof := strings.Repeat("A", 43)
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := db.ExecContext(ctx, query, args...); err != nil { t.Fatal(err) }
	}
	exec(`insert into users(id,issuer,subject) values($1,'https://issuer.example','management-user')`, user)
	exec(`insert into orgs(id,slug,name) values($1,'host-management-proof','Management proof')`, org)
	exec(`insert into org_memberships(org_id,user_id,role) values($1,$2,'admin')`, org, user)
	exec(`insert into hosts(id,org_id,alias,state) values($1,$2,'Managed host','active')`, host, org)
	for index, id := range []string{device, secondDevice} {
		exec(`insert into devices(id,user_id,name,approval_public_jwk,approval_key_thumbprint,proof_public_jwk,proof_key_thumbprint)
		 values($1,$2,'Phone','{}',$3,'{}',$4)`, id, user, strings.Repeat(string(rune('B'+index)), 43), []string{proof, strings.Repeat("D",43)}[index])
	}
	exec(`insert into installations(id,host_id,owner_user_id,public_jwk,public_key_thumbprint) values($1,$2,$3,'{}',$4)`, installation, host, user, strings.Repeat("E", 43))
	for index, runtime := range []string{runtimeA, runtimeB, runtimeC} {
		exec(`insert into runtimes(id,installation_id,profile_ref,dsh_version) values($1,$2,$3,'0.2.1-rc.5')`, runtime, installation, []string{"A","B","C"}[index])
		exec(`insert into runtime_grants(runtime_id,user_id,role,bindings,granted_by) values($1,$2,'viewer','[{"resource":{"kind":"session"},"actions":["session/list"]}]',$2)`, runtime, user)
	}
	store := &Postgres{DB: db}
	actor := UserIdentity{Issuer: "https://issuer.example", Subject: "management-user"}
	devices, err := store.ListDevices(ctx, actor, device, proof, 1, "")
	if err != nil || len(devices.Items) != 1 || devices.NextCursor == nil { t.Fatalf("device pagination: %+v %v", devices, err) }
	next, err := store.ListDevices(ctx, actor, device, proof, 1, *devices.NextCursor)
	if err != nil || len(next.Items) != 1 || devices.Items[0].ID == next.Items[0].ID { t.Fatalf("second device page: %+v %v", next, err) }
	installations, err := store.ListInstallations(ctx, actor, device, proof, 50, "")
	if err != nil || len(installations.Items) != 1 || installations.Items[0].ID != installation { t.Fatalf("installation listing: %+v %v", installations, err) }
	profiles, err := store.ListInstallationRuntimes(ctx, actor, device, proof, installation, 1, "")
	if err != nil || len(profiles.Items) != 1 || profiles.NextCursor == nil { t.Fatalf("profile pagination: %+v %v", profiles, err) }
	grants, err := store.ListRuntimeGrants(ctx, actor, device, proof, runtimeA, 50, "")
	if err != nil || len(grants.Items) != 1 || grants.Items[0].UserID != user { t.Fatalf("grant listing: %+v %v", grants, err) }
	wrong := strings.Repeat("Z", 43)
	if _, err := store.ListDevices(ctx, actor, device, wrong, 50, ""); err != ErrAccessDenied { t.Fatalf("device proof bypass: %v", err) }
	if err := store.RevokeInstallationRuntime(ctx, actor, device, wrong, installation, runtimeB); err != ErrAccessDenied { t.Fatalf("profile revoke proof bypass: %v", err) }
	if _, err := store.RevokeRuntimeGrant(ctx, actor, device, wrong, runtimeA, user); err != ErrAccessDenied { t.Fatalf("grant revoke proof bypass: %v", err) }
	if _, err := store.RevokeRuntimeGrant(ctx, actor, device, proof, runtimeA, user); err != nil { t.Fatal(err) }
	page, err := store.RuntimeDirectory(ctx, actor, device, proof, 50, "")
	if err != nil || len(page.Items) != 2 { t.Fatalf("grant revoke not reflected: %+v %v", page, err) }
	if err := store.RevokeInstallationRuntime(ctx, actor, device, proof, installation, runtimeB); err != nil { t.Fatal(err) }
	page, err = store.RuntimeDirectory(ctx, actor, device, proof, 50, "")
	if err != nil || len(page.Items) != 1 || page.Items[0].ID != runtimeC { t.Fatalf("profile retirement leaked across resources: %+v %v", page, err) }
	if err := store.RevokeInstallation(ctx, actor, device, proof, installation); err != nil { t.Fatal(err) }
	page, err = store.RuntimeDirectory(ctx, actor, device, proof, 50, "")
	if err != nil || len(page.Items) != 0 { t.Fatalf("installation retirement did not withdraw grants: %+v %v", page, err) }
	if err := store.RevokeDevice(ctx, actor.Issuer, actor.Subject, device); err != nil { t.Fatal(err) }
	if _, err := store.ListInstallations(ctx, actor, device, proof, 50, ""); err != ErrAccessDenied { t.Fatalf("retired phone retained management access: %v", err) }
}
