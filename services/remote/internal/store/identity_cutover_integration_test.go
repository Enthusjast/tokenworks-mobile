package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/approval"
	"tokenworks-mobile/services/remote/internal/canonical"
	"tokenworks-mobile/services/remote/internal/testsupport"
	jose "github.com/go-jose/go-jose/v4"
	"github.com/jackc/pgx/v5/pgconn"
)

func tokenWorksMigration(ctx context.Context, db *sql.DB, direction string) error {
	raw, err := os.ReadFile("../../../../deploy/sql/055_tokenworks_mobile_identity." + direction + ".sql")
	if err != nil {
		return err
	}
	conn, err := db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	_, err = conn.ExecContext(ctx, string(raw))
	if err != nil {
		_, _ = conn.ExecContext(ctx, "rollback")
	}
	return err
}

func tokenWorksFunctionSnapshot(t *testing.T, ctx context.Context, db *sql.DB) string {
	t.Helper()
	var snapshot string
	if err := db.QueryRowContext(ctx, `select pg_get_functiondef('control_archive_import_payload_guard()'::regprocedure)||pg_get_functiondef('cold_history_import_payload_guard()'::regprocedure)`).Scan(&snapshot); err != nil {
		t.Fatal(err)
	}
	return snapshot
}

func TestTokenWorksIdentityCutoverFreshAndReadyGuard(t *testing.T) {
	ctx, db := testsupport.PostgresDatabase(t)
	current := tokenWorksFunctionSnapshot(t, ctx, db)
	for _, format := range []string{"tokenworks-mobile-control-archive", "tokenworks-mobile-audit-archive", "tokenworks-mobile-approval-evidence"} {
		if !strings.Contains(current, format) {
			t.Fatal("fresh migrations did not install the new archive guards")
		}
	}
	if err := tokenWorksMigration(ctx, db, "down"); err != nil {
		t.Fatal(err)
	}
	legacy := tokenWorksFunctionSnapshot(t, ctx, db)
	seedGrantFixture(t, ctx, db)
	_, err := db.ExecContext(ctx, `insert into local_accounts(user_id,username,password_hash) values('22222222-2222-4222-8222-222222222222','reserved-legacy','retained-password-hash')`)
	if err != nil {
		t.Fatal(err)
	}
	err = tokenWorksMigration(ctx, db, "up")
	var pgerr *pgconn.PgError
	if !errors.As(err, &pgerr) || pgerr.Code != "23514" || pgerr.Message != "tokenworks-mobile identity cutover requires recovery quarantine" {
		t.Fatal("ready identities were not precisely rejected", err)
	}
	if tokenWorksFunctionSnapshot(t, ctx, db) != legacy {
		t.Fatal("rejected migration partially replaced archive guards")
	}
	var unchanged bool
	if err := db.QueryRowContext(ctx, `select phase='ready' and epoch=$1::uuid and (select status='active' from users where id='22222222-2222-4222-8222-222222222222') from remote_recovery_state where slot=1`, OriginalDatabaseEpoch).Scan(&unchanged); err != nil || !unchanged {
		t.Fatal("rejected migration changed identity or recovery state", err)
	}
}

// Insert through the real guards, in the same transaction as the immutable
// identity row. These are inert history documents, never live authority.
func tokenWorksImportPayload(ctx context.Context, db *sql.DB, kind, format, epoch, compact string, expired bool) (string, error) {
	id, domain, exportID, objectID := "", "", "", ""
	if err := db.QueryRowContext(ctx, `select gen_random_uuid()::text,gen_random_uuid()::text,gen_random_uuid()::text,gen_random_uuid()::text`).Scan(&id, &domain, &exportID, &objectID); err != nil {
		return "", err
	}
	retained := time.Now().UTC().Add(time.Hour).Truncate(time.Microsecond)
	if expired {
		retained = time.Now().UTC().Add(-time.Hour).Truncate(time.Microsecond)
	}
	payload := map[string]any{"format": format, "version": 1, "domain_id": domain, "export_id": exportID, "operation_epoch": OriginalDatabaseEpoch, "key_id": "fixture-key"}
	count := 0
	if kind == "approval" {
		count = 1
		payload["proof"] = map[string]any{"approval_id": objectID, "source_epoch": OriginalDatabaseEpoch, "retain_until": retained, "decision_jws": compact}
	} else {
		payload["batch"] = map[string]any{"id": objectID, "database_epoch": OriginalDatabaseEpoch, "retain_until": retained}
		if kind == "control" {
			payload["events"] = []any{}
		} else {
			payload["entries"] = []any{}
		}
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(raw)
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if kind == "control" {
		_, err = tx.ExecContext(ctx, `insert into control_archive_imports(id,domain_id,export_id,batch_id,batch_epoch,source_epoch,target_epoch,key_id,key_fingerprint,artifact_sha256,artifact_size,payload_sha256,event_count,acknowledgement_count,retain_until,imported_at)
		 values($1,$2,$3,$4,$5,$5,$6,'fixture-key',repeat('a',64),repeat('b',64),76,$7,0,0,$8,clock_timestamp())`, id, domain, exportID, objectID, OriginalDatabaseEpoch, epoch, hex.EncodeToString(digest[:]), retained)
	} else {
		_, err = tx.ExecContext(ctx, `insert into cold_history_imports(id,kind,domain_id,export_id,object_id,source_epoch,operation_epoch,target_epoch,key_id,key_fingerprint,artifact_sha256,artifact_size,payload_sha256,entry_count,retain_until,imported_at)
		 values($1,$2,$3,$4,$5,$6,$6,$7,'fixture-key',repeat('a',64),repeat('b',64),76,$8,$9,$10,clock_timestamp())`, id, kind, domain, exportID, objectID, OriginalDatabaseEpoch, epoch, hex.EncodeToString(digest[:]), count, retained)
	}
	if err != nil {
		return "", err
	}
	table := "cold_history_import_payloads"
	if kind == "control" {
		table = "control_archive_import_payloads"
	}
	if _, err = tx.ExecContext(ctx, "insert into "+table+"(import_id,payload) values($1,$2::jsonb)", id, string(raw)); err != nil {
		return "", err
	}
	return id, tx.Commit()
}

func tokenWorksHistorySnapshot(t *testing.T, ctx context.Context, db *sql.DB) string {
	t.Helper()
	var snapshot string
	if err := db.QueryRowContext(ctx, `select coalesce(jsonb_agg(row_to_json(h) order by id)::text,'[]') from (
	 select i.id,p.payload,i.payload_sha256,i.artifact_sha256,i.key_fingerprint from control_archive_imports i join control_archive_import_payloads p on p.import_id=i.id
	 union all select i.id,p.payload,i.payload_sha256,i.artifact_sha256,i.key_fingerprint from cold_history_imports i join cold_history_import_payloads p on p.import_id=i.id) h`).Scan(&snapshot); err != nil {
		t.Fatal(err)
	}
	return snapshot
}

func TestTokenWorksIdentityCutoverQuarantinePreservesLegacyHistoryAndRevocation(t *testing.T) {
	f := newApprovalFixture(t)
	ctx, db := f.ctx, f.database.DB
	f.register(t, f.request)
	ticket, err := f.database.IssueRealtimeTicket(ctx, UserIdentity{Issuer: f.principal.Issuer, Subject: f.principal.Subject}, f.ticket.DeviceID, f.ticket.RuntimeID, f.ticket.Scope)
	if err != nil {
		t.Fatal(err)
	}
	if err := tokenWorksMigration(ctx, db, "down"); err != nil {
		t.Fatal(err)
	}
	legacyFunctions := tokenWorksFunctionSnapshot(t, ctx, db)
	_, err = db.ExecContext(ctx, `insert into users(id,issuer,subject) values('99999999-9999-4999-8999-999999999999','urn:dsh-remote:local-account:v1','legacy-local');
	 insert into local_accounts(user_id,username,password_hash) values('99999999-9999-4999-8999-999999999999','reserved-legacy','retained-password-hash');
	 insert into account_sessions(token_hash,csrf_hash,user_id,password_version,database_epoch,expires_at) values(decode(repeat('11',32),'hex'),decode(repeat('22',32),'hex'),'99999999-9999-4999-8999-999999999999',1,'`+OriginalDatabaseEpoch+`',clock_timestamp()+interval '1 hour')`)
	if err != nil {
		t.Fatal(err)
	}
	// Produce an actual legacy signature, then retain its bytes without ever
	// asking the new verifier to treat it as current authority.
	parts := strings.Split(f.signed(t, f.request, "allow"), ".")
	body, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		t.Fatal(err)
	}
	body = []byte(strings.Replace(string(body), approval.DecisionDomain, "dsh-mobile-ustc/approval-decision/v1", 1))
	body, err = canonical.Object(body)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := jose.NewSigner(jose.SigningKey{Algorithm: jose.ES256, Key: jose.JSONWebKey{Key: f.key, KeyID: "dsh-approval/" + f.ticket.DeviceID + "/1"}}, (&jose.SignerOptions{}).WithType("dsh-approval-decision+jws"))
	if err != nil {
		t.Fatal(err)
	}
	signed, err := signer.Sign(body)
	if err != nil {
		t.Fatal(err)
	}
	compact, err := signed.CompactSerialize()
	if err != nil {
		t.Fatal(err)
	}
	formats := map[string]string{"control": "dsh-control-archive", "audit": "dsh-audit-archive", "approval": "dsh-approval-evidence"}
	retained := map[string]string{}
	expired := map[string]string{}
	for kind, format := range formats {
		retained[kind], err = tokenWorksImportPayload(ctx, db, kind, format, OriginalDatabaseEpoch, compact, false)
		if err != nil {
			t.Fatal("legacy history fixture", kind, err)
		}
		expired[kind], err = tokenWorksImportPayload(ctx, db, kind, format, OriginalDatabaseEpoch, compact, true)
		if err != nil {
			t.Fatal(err)
		}
	}
	before := tokenWorksHistorySnapshot(t, ctx, db)
	operation, epoch := approvalTestUUID(t), approvalTestUUID(t)
	if _, err := f.database.QuarantineRecovery(ctx, operation, OriginalDatabaseEpoch, epoch); err != nil {
		t.Fatal("established quarantine failed", err)
	}
	if err := tokenWorksMigration(ctx, db, "up"); err != nil {
		t.Fatal("quarantined cutover rejected", err)
	}
	if tokenWorksHistorySnapshot(t, ctx, db) != before {
		t.Fatal("cutover rewrote legacy payload, signature or digest")
	}
	authority := RecoveryAuthority{Version: 1, Complete: true, RecoveryID: operation, DatabaseEpoch: epoch, Issuer: f.principal.Issuer,
		CapturedAt: time.Now().UTC(), ExpiresAt: time.Now().Add(5 * time.Minute).UTC(), Users: []RecoveryUser{}}
	if _, err := f.database.ReleaseRecovery(ctx, epoch, authority.Issuer, authority); err != nil {
		t.Fatal(err)
	}
	assertRevoked := func() {
		t.Helper()
		var safe bool
		if err := db.QueryRowContext(ctx, `select
		 not exists(select 1 from users where status='active') and not exists(select 1 from devices where revoked_at is null)
		 and not exists(select 1 from installations where revoked_at is null) and not exists(select 1 from runtime_grants where revoked_at is null)
		 and not exists(select 1 from realtime_tickets where consumed_at is null) and not exists(select 1 from approvals where state in ('pending','decision_received','allowed'))
		 and not exists(select 1 from account_sessions) and exists(select 1 from users u join local_accounts a on a.user_id=u.id
		 where u.issuer='urn:dsh-remote:local-account:v1' and u.status='suspended' and a.username='reserved-legacy' and a.password_hash='retained-password-hash')
		 and exists(select 1 from remote_recovery_state where phase='ready' and epoch=$1::uuid and recovery_id=$2::uuid)`, epoch, operation).Scan(&safe); err != nil || !safe {
			t.Fatal("cutover or rollback revived authority or migrated old account identity", err)
		}
		if _, err := f.database.PrepareRealtimeTicket(ctx, ticket.Token); err == nil {
			t.Fatal("old realtime ticket revived")
		}
	}
	assertRevoked()
	if tokenWorksHistorySnapshot(t, ctx, db) != before {
		t.Fatal("release changed legacy history")
	}
	for kind, format := range formats {
		newFormat := strings.Replace(format, "dsh-", "tokenworks-mobile-", 1)
		if _, err := tokenWorksImportPayload(ctx, db, kind, newFormat, epoch, compact, false); err != nil {
			t.Fatal("new INSERT format rejected", kind, err)
		}
		if _, err := tokenWorksImportPayload(ctx, db, kind, format, epoch, compact, false); err == nil {
			t.Fatal("legacy INSERT format accepted", kind)
		}
		table := "cold_history_import_payloads"
		action := "cold.history.import.delete"
		if kind == "control" {
			table, action = "control_archive_import_payloads", "control.archive.import.delete"
		}
		for _, statement := range []string{"update " + table + " set payload=payload where import_id=$1", "delete from " + table + " where import_id=$1"} {
			if _, err := db.ExecContext(ctx, statement, retained[kind]); err == nil {
				t.Fatal("legacy retention or immutability bypassed", kind)
			}
		}
		if _, err := db.ExecContext(ctx, "delete from "+table+" where import_id=$1", expired[kind]); err != nil {
			t.Fatal("legacy expired payload deletion rejected", kind, err)
		}
		var audited bool
		if err := db.QueryRowContext(ctx, `select exists(select 1 from audit_log where action=$1 and metadata->>'import_id'=$2)`, action, expired[kind]).Scan(&audited); err != nil || !audited {
			t.Fatal("legacy deletion lost its audit", kind, err)
		}
	}
	beforeDown := tokenWorksHistorySnapshot(t, ctx, db)
	if err := tokenWorksMigration(ctx, db, "down"); err != nil {
		t.Fatal(err)
	}
	assertRevoked()
	if tokenWorksHistorySnapshot(t, ctx, db) != beforeDown || tokenWorksFunctionSnapshot(t, ctx, db) != legacyFunctions {
		t.Fatal("down changed history or failed to restore the historical INSERT guards")
	}
	for kind, format := range formats {
		if _, err := tokenWorksImportPayload(ctx, db, kind, strings.Replace(format, "dsh-", "tokenworks-mobile-", 1), epoch, compact, false); err == nil {
			t.Fatal("rolled-back guard admitted new-format payload", kind)
		}
		if _, err := tokenWorksImportPayload(ctx, db, kind, format, epoch, compact, false); err != nil {
			t.Fatal("down did not restore the legacy INSERT format", kind, err)
		}
	}
	assertRevoked()
}
