package store

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/push"
	"tokenworks-mobile/services/remote/internal/testsupport"
)

type pushFixtureProvider struct {
	tokens []string
	references []push.Reference
	err error
}
func (provider *pushFixtureProvider) Send(_ context.Context, token string, reference push.Reference) error {
	provider.tokens=append(provider.tokens,token)
	provider.references=append(provider.references,reference)
	return provider.err
}

func TestPushDurableOwnerGrantRotationExpiryRevocation(t *testing.T) {
	ctx,db:=testsupport.PostgresDatabase(t)
	const user="11111111-1111-4111-8111-111111111111"
	const org="22222222-2222-4222-8222-222222222222"
	const host="33333333-3333-4333-8333-333333333333"
	const installationID="44444444-4444-4444-8444-444444444444"
	const runtimeID="55555555-5555-4555-8555-555555555555"
	const device="66666666-6666-4666-8666-666666666666"
	const account="77777777-7777-4777-8777-777777777777"
	const foreignOrg="88888888-8888-4888-8888-888888888888"
	deployment:=strings.Repeat("a",64)
	proof:=strings.Repeat("A",43)
	exec:=func(query string,args ...any) { t.Helper(); if _,err:=db.ExecContext(ctx,query,args...);err!=nil { t.Fatal(err) } }
	exec(`insert into users(id,issuer,subject) values($1,'https://push.example','push-user')`,user)
	exec(`insert into orgs(id,slug,name) values($1,'push-proof','Push proof'),($2,'push-foreign','Foreign proof')`,org,foreignOrg)
	exec(`insert into org_memberships(org_id,user_id,role) values($1,$2,'member')`,org,user)
	exec(`insert into hosts(id,org_id,alias,state) values($1,$2,'Push host','active')`,host,org)
	exec(`insert into devices(id,user_id,name,approval_public_jwk,approval_key_thumbprint,proof_public_jwk,proof_key_thumbprint)
	 values($1,$2,'Push phone','{}',$3,'{}',$4)`,device,user,strings.Repeat("B",43),proof)
	exec(`insert into installations(id,host_id,owner_user_id,public_jwk,public_key_thumbprint) values($1,$2,$3,'{}',$4)`,installationID,host,user,strings.Repeat("C",43))
	exec(`insert into runtimes(id,installation_id,profile_ref) values($1,$2,'native-push')`,runtimeID,installationID)
	exec(`insert into runtime_grants(runtime_id,user_id,role,bindings,granted_by)
	 values($1,$2,'viewer','[{"resource":{"kind":"session","id":"native:session"},"actions":["session/follow"]}]',$2)`,runtimeID,user)
	guard,err:=(&Postgres{DB:db}).NewRecoveryGuard(ctx,OriginalDatabaseEpoch);if err!=nil { t.Fatal(err) }
	repository:=guard.PushRepository()
	cipher,err:=push.NewTokenCipher(make([]byte,32)); if err!=nil { t.Fatal(err) }
	provider:=&pushFixtureProvider{}
	service,err:=push.New(repository,deployment,cipher,provider); if err!=nil { t.Fatal(err) }
	actor:=push.Actor{Issuer:"https://push.example",Subject:"push-user",DeviceID:device,ProofJKT:proof}
	owner:=push.Owner{AccountID:account,OrganizationID:org,DeploymentSHA256:deployment,UserID:user,DeviceID:device}
	registration:=push.Registration{Provider:"fcm",Token:"fixture-token-v1",AccountID:account,OrganizationID:org,DeploymentSHA256:deployment,OwnerFingerprint:owner.Fingerprint()}
	binding,err:=service.Register(ctx,actor,registration); if err!=nil { t.Fatal(err) }
	if same,err:=service.Register(ctx,actor,registration);err!=nil || same!=binding { t.Fatal("registration not idempotent",err) }
	var ciphertext []byte
	if err:=db.QueryRowContext(ctx,`select token_ciphertext from push_registrations where id=$1`,binding).Scan(&ciphertext);err!=nil || strings.Contains(string(ciphertext),registration.Token) { t.Fatal("token not encrypted",err) }
	bad:=registration; bad.OwnerFingerprint=strings.Repeat("f",64)
	if _,err:=service.Register(ctx,actor,bad);!errors.Is(err,push.ErrDenied) { t.Fatal("foreign owner accepted",err) }
	bad=registration;bad.OrganizationID=foreignOrg
	foreignOwner:=owner;foreignOwner.OrganizationID=foreignOrg;bad.OwnerFingerprint=foreignOwner.Fingerprint()
	if _,err:=service.Register(ctx,actor,bad);!errors.Is(err,push.ErrDenied) { t.Fatal("foreign org accepted",err) }
	bad=registration;bad.DeploymentSHA256=strings.Repeat("b",64)
	if _,err:=service.Register(ctx,actor,bad);!errors.Is(err,push.ErrInvalid) { t.Fatal("foreign deployment accepted",err) }
	bad=registration;bad.Token="not a provider token"
	if _,err:=service.Register(ctx,actor,bad);!errors.Is(err,push.ErrInvalid) { t.Fatal("malformed provider token accepted",err) }
	wrongActor:=actor;wrongActor.ProofJKT=strings.Repeat("Z",43)
	if err:=service.Revoke(ctx,wrongActor,binding);!errors.Is(err,push.ErrDenied) { t.Fatal("foreign proof revoked binding",err) }
	installation:=push.Installation{ID:installationID,KeyVersion:1,ExpiresAt:time.Now().Add(5*time.Minute)}
	sequence:=0
	newEmit:=func() push.Emit { sequence++;return push.Emit{NotificationID:fmt.Sprintf("%08x-aaaa-4aaa-8aaa-aaaaaaaaaaaa",sequence),RuntimeID:runtimeID,SessionID:"native:session",Kind:"interaction",ExpiresAt:time.Now().Add(5*time.Minute)} }
	enqueue:=func(emit push.Emit) { t.Helper();if err:=service.Emit(ctx,installation,emit);err!=nil { t.Fatal(err) } }
	deliver:=func() { t.Helper();if _,err:=repository.DeliverPush(ctx,deployment,cipher,provider);err!=nil { t.Fatal(err) } }
	count:=func() int { t.Helper();var n int;if err:=db.QueryRowContext(ctx,`select count(*) from push_deliveries where delivered_at is null`).Scan(&n);err!=nil { t.Fatal(err) };return n }
	enqueue(newEmit())
	exec(`update runtime_grants set revoked_at=clock_timestamp() where runtime_id=$1`,runtimeID)
	deliver();if len(provider.tokens)!=0 || count()!=0 { t.Fatal("revoked grant sent or retained") }
	exec(`update runtime_grants set revoked_at=null where runtime_id=$1`,runtimeID)
	enqueue(newEmit());exec(`update runtime_grants set expires_at=clock_timestamp()-interval '1 second' where runtime_id=$1`,runtimeID)
	deliver();if len(provider.tokens)!=0 || count()!=0 { t.Fatal("expired grant sent") }
	exec(`update runtime_grants set expires_at=null where runtime_id=$1`,runtimeID)
	foreignSession:=newEmit();foreignSession.SessionID="foreign-session";enqueue(foreignSession)
	if count()!=0 { t.Fatal("ungranted session leaked reference") }
	enqueue(newEmit());exec(`update push_deliveries set expires_at=clock_timestamp()-interval '1 second'`)
	deliver();if len(provider.tokens)!=0 || count()!=0 { t.Fatal("expired reference sent") }
	enqueue(newEmit())
	registration.Token="fixture-token-v2"
	rotated,err:=service.Register(ctx,actor,registration);if err!=nil || rotated==binding || count()!=0 { t.Fatal("rotation failed to replace and cancel",err) }
	deliver();if len(provider.tokens)!=0 { t.Fatal("old token delivered after rotation") }
	fresh:=newEmit();enqueue(fresh);deliver()
	if len(provider.tokens)!=1 || provider.tokens[0]!="fixture-token-v2" || provider.references[0].BindingID!=rotated || provider.references[0].Owner!=owner { t.Fatal("wrong rotated/full-owner delivery") }
	enqueue(fresh);deliver();if len(provider.tokens)!=1 { t.Fatal("duplicate durable notification sent twice") }
	if err:=service.Revoke(ctx,actor,binding);!errors.Is(err,push.ErrDenied) { t.Fatal("old binding revived") }
	enqueue(newEmit());if err:=service.Revoke(ctx,actor,rotated);err!=nil { t.Fatal(err) };deliver()
	if len(provider.tokens)!=1 || count()!=0 { t.Fatal("revoked binding delivered") }
	binding,err=service.Register(ctx,actor,registration);if err!=nil { t.Fatal(err) }
	enqueue(newEmit());exec(`update devices set revoked_at=clock_timestamp() where id=$1`,device);deliver()
	if len(provider.tokens)!=1 { t.Fatal("revoked device received push") }
	if _,err:=service.Register(ctx,actor,registration);!errors.Is(err,push.ErrDenied) { t.Fatal("revoked device registered",err) }
	exec(`update devices set revoked_at=null where id=$1`,device)
	enqueue(newEmit());exec(`update devices set proof_key_thumbprint=$2 where id=$1`,device,strings.Repeat("Z",43));deliver()
	if len(provider.tokens)!=1 || count()!=0 { t.Fatal("changed device proof received old binding references") }
	exec(`update devices set proof_key_thumbprint=$2 where id=$1`,device,proof)
	enqueue(newEmit());exec(`update installations set key_version=2 where id=$1`,installationID);deliver()
	if len(provider.tokens)!=1 { t.Fatal("old installation key emitted after rotation") }
	if err:=service.Emit(ctx,installation,newEmit());!errors.Is(err,push.ErrDenied) { t.Fatal("stale installation proof emitted",err) }
	installation.KeyVersion=2
	enqueue(newEmit());provider.err=push.ErrUnavailable;deliver()
	if count()!=1 { t.Fatal("provider failure not durable") }
	calls:=len(provider.tokens);deliver();if len(provider.tokens)!=calls { t.Fatal("retry ignored durable backoff") }
	exec(`update push_deliveries set next_attempt_at=clock_timestamp() where delivered_at is null`)
	provider.err=push.ErrUnregistered;deliver()
	var n int
	if err:=db.QueryRowContext(ctx,`select count(*) from push_registrations where id=$1`,binding).Scan(&n);err!=nil || n!=0 || count()!=0 { t.Fatal("unregistered provider token retained",err) }
}
