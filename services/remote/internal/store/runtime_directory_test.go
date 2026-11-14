package store

import (
	"encoding/json"
	"strings"
	"testing"

	"tokenworks-mobile/services/remote/internal/testsupport"
)

func TestRuntimeDirectoryCarriesExactInstallationIdentityAndWithdrawsRevokedRows(t *testing.T) {
	ctx, db := testsupport.PostgresDatabase(t)
	const user = "11111111-1111-4111-8111-111111111111"
	const org = "22222222-2222-4222-8222-222222222222"
	const host = "33333333-3333-4333-8333-333333333333"
	const installA = "44444444-4444-4444-8444-444444444444"
	const installB = "55555555-5555-4555-8555-555555555555"
	const runtimeA = "66666666-6666-4666-8666-666666666666"
	const runtimeB = "77777777-7777-4777-8777-777777777777"
	const device = "88888888-8888-4888-8888-888888888888"
	proof := strings.Repeat("A", 43)
	exec := func(query string, args ...any) {
		t.Helper()
		if _, err := db.ExecContext(ctx, query, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec(`insert into users(id,issuer,subject) values($1,'https://issuer.example','directory-user')`, user)
	exec(`insert into orgs(id,slug,name) values($1,'directory-proof','Directory proof')`, org)
	exec(`insert into org_memberships(org_id,user_id,role) values($1,$2,'member')`, org, user)
	exec(`insert into hosts(id,org_id,alias,state) values($1,$2,'Same physical host','active')`, host, org)
	exec(`insert into devices(id,user_id,name,approval_public_jwk,approval_key_thumbprint,proof_public_jwk,proof_key_thumbprint)
        values($1,$2,'Public test device','{}',$3,'{}',$4)`, device, user, strings.Repeat("B", 43), proof)
	for index, installation := range []string{installA, installB} {
		exec(`insert into installations(id,host_id,owner_user_id,public_jwk,public_key_thumbprint) values($1,$2,$3,'{}',$4)`,
			installation, host, user, strings.Repeat(string(rune('C'+index)), 43))
	}
	for index, runtime := range []string{runtimeA, runtimeB} {
		exec(`insert into runtimes(id,installation_id,profile_ref,dsh_version) values($1,$2,'directory-proof','0.2.1-rc.5')`,
			runtime, []string{installA, installB}[index])
		exec(`insert into runtime_grants(runtime_id,user_id,role,bindings,granted_by)
            values($1,$2,'viewer','[{"resource":{"kind":"session"},"actions":["session/list"]}]',$2)`, runtime, user)
	}
	store := &Postgres{DB: db}
	actor := UserIdentity{Issuer: "https://issuer.example", Subject: "directory-user"}
	expected := map[string]string{runtimeA: installA, runtimeB: installB}
	check := func(items []Runtime) {
		t.Helper()
		if len(items) != len(expected) {
			t.Fatalf("directory has %d rows, want %d", len(items), len(expected))
		}
		for _, item := range items {
			if item.HostID != host || item.InstallationID != expected[item.ID] {
				t.Fatalf("runtime identity mixed across installations: %+v", item)
			}
			raw, err := json.Marshal(item)
			if err != nil {
				t.Fatal(err)
			}
			var wire map[string]json.RawMessage
			if json.Unmarshal(raw, &wire) != nil || string(wire["installation_id"]) != `"`+expected[item.ID]+`"` {
				t.Fatal("trusted installation identity disappeared from the directory response")
			}
		}
	}
	legacy, err := store.ListRuntimes(ctx, actor)
	if err != nil {
		t.Fatal(err)
	}
	check(legacy)
	page, err := store.RuntimeDirectory(ctx, actor, device, proof, 1, "")
	if err != nil {
		t.Fatal(err)
	}
	if page.NextCursor == nil {
		t.Fatal("expected a second installation page")
	}
	next, err := store.RuntimeDirectory(ctx, actor, device, proof, 1, *page.NextCursor)
	if err != nil {
		t.Fatal(err)
	}
	check(append(page.Items, next.Items...))
	if _, err := store.RuntimeDirectory(ctx, actor, device, strings.Repeat("Z", 43), 10, ""); err != ErrAccessDenied {
		t.Fatalf("another proof key could read trusted identities: %v", err)
	}
	exec(`update installations set revoked_at=clock_timestamp() where id=$1`, installA)
	delete(expected, runtimeA)
	legacy, err = store.ListRuntimes(ctx, actor)
	if err != nil {
		t.Fatal(err)
	}
	check(legacy)
	page, err = store.RuntimeDirectory(ctx, actor, device, proof, 10, "")
	if err != nil {
		t.Fatal(err)
	}
	check(page.Items)
}
