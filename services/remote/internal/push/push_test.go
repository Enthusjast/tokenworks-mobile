package push

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
	"time"
)

func fixtureReference() Reference {
	return Reference{BindingID: "11111111-1111-4111-8111-111111111111", Owner: Owner{
		AccountID: "22222222-2222-4222-8222-222222222222", OrganizationID: "33333333-3333-4333-8333-333333333333",
		DeploymentSHA256: strings.Repeat("a",64), UserID: "44444444-4444-4444-8444-444444444444", DeviceID: "55555555-5555-4555-8555-555555555555"},
		Emit: Emit{NotificationID: "66666666-6666-4666-8666-666666666666", RuntimeID: "77777777-7777-4777-8777-777777777777", SessionID: "native:opaque/session", Kind: "interaction", ExpiresAt: time.Now().Add(5*time.Minute)}}
}

func TestPushReferenceValidationAndAuthenticatedEncryption(t *testing.T) {
	reference := fixtureReference()
	data, err := reference.Data(time.Now())
	if err != nil || len(data) != 13 || data["v"] != "1" || data["owner_fingerprint"] != reference.Owner.Fingerprint() { t.Fatal("invalid minimal reference",err) }
	for _, mutate := range []func(*Reference){
		func(r *Reference) { r.Emit.ExpiresAt=time.Now().Add(-time.Second) },
		func(r *Reference) { r.Emit.ExpiresAt=time.Now().Add(16*time.Minute) },
		func(r *Reference) { r.Emit.RuntimeID="not-a-uuid" },
		func(r *Reference) { r.Emit.SessionID="bad session" },
		func(r *Reference) { r.Emit.SessionID=strings.Repeat("s",257) },
		func(r *Reference) { r.Emit.Kind="approval_body" },
		func(r *Reference) { r.Owner.DeviceID="foreign" },
	} {
		invalid := reference; mutate(&invalid)
		if _,err:=invalid.Data(time.Now()); !errors.Is(err,ErrInvalid) { t.Fatal("malformed reference accepted") }
	}
	cipher,err:=NewTokenCipher(make([]byte,32)); if err!=nil { t.Fatal(err) }
	encrypted,err:=cipher.Encrypt("fixture-provider-token",reference.BindingID,reference.Owner.Fingerprint()); if err!=nil { t.Fatal(err) }
	if strings.Contains(string(encrypted),"fixture-provider-token") { t.Fatal("plaintext token at rest") }
	plain,err:=cipher.Decrypt(encrypted,reference.BindingID,reference.Owner.Fingerprint()); if err!=nil || string(plain)!="fixture-provider-token" { t.Fatal("token roundtrip",err) }; clear(plain)
	if _,err:=cipher.Decrypt(encrypted,reference.Emit.NotificationID,reference.Owner.Fingerprint()); err==nil { t.Fatal("foreign binding decrypted token") }
	if _,err:=cipher.Decrypt(encrypted,reference.BindingID,strings.Repeat("f",64)); err==nil { t.Fatal("foreign owner decrypted token") }
	encrypted[len(encrypted)-1]^=1
	if _,err:=cipher.Decrypt(encrypted,reference.BindingID,reference.Owner.Fingerprint()); err==nil { t.Fatal("tampered ciphertext accepted") }
}

func TestPushFCMHTTPFixtureSignedOAuthAndDataOnly(t *testing.T) {
	key,err:=rsa.GenerateKey(rand.Reader,2048); if err!=nil { t.Fatal(err) }
	der,err:=x509.MarshalPKCS8PrivateKey(key); if err!=nil { t.Fatal(err) }
	credentials,_:=json.Marshal(map[string]string{"type":"service_account","project_id":"fixture-project","private_key_id":"fixture-key",
		"private_key":string(pem.EncodeToMemory(&pem.Block{Type:"PRIVATE KEY",Bytes:der})),"client_email":"fixture@fixture-project.iam.gserviceaccount.com","token_uri":"https://oauth2.googleapis.com/token"})
	provider,err:=NewFCM(credentials); if err!=nil { t.Fatal(err) }
	reference:=fixtureReference()
	want,_:=reference.Data(time.Now())
	oauthCalls,sendCalls:=0,0
	unregistered:=false
	expireDuringOAuth:=false
	var fixtureURL string
	fixture:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
		if r.Method!="POST" { t.Error("provider HTTP method"); w.WriteHeader(400); return }
		switch r.URL.Path {
		case "/token":
			oauthCalls++
			if r.ParseForm()!=nil || r.Form.Get("grant_type")!="urn:ietf:params:oauth:grant-type:jwt-bearer" { t.Error("OAuth assertion grant") }
			parts:=strings.Split(r.Form.Get("assertion"),".")
			if len(parts)!=3 { t.Error("not a signed assertion"); w.WriteHeader(400); return }
			headerBytes,_:=base64.RawURLEncoding.DecodeString(parts[0]); claimsBytes,_:=base64.RawURLEncoding.DecodeString(parts[1]); signature,_:=base64.RawURLEncoding.DecodeString(parts[2])
			var header map[string]string; var claims map[string]any
			if json.Unmarshal(headerBytes,&header)!=nil || json.Unmarshal(claimsBytes,&claims)!=nil { t.Error("assertion JSON") }
			digest:=sha256.Sum256([]byte(parts[0]+"."+parts[1]))
			if rsa.VerifyPKCS1v15(&key.PublicKey,crypto.SHA256,digest[:],signature)!=nil || header["alg"]!="RS256" || header["kid"]!="fixture-key" { t.Error("assertion signature") }
			if claims["aud"]!=fixtureURL+"/token" || claims["iss"]!=provider.email || claims["scope"]!="https://www.googleapis.com/auth/firebase.messaging" || claims["exp"].(float64)-claims["iat"].(float64)!=3600 { t.Error("assertion scope/time") }
			if expireDuringOAuth { time.Sleep(50*time.Millisecond) }
			io.WriteString(w,`{"access_token":"fixture-oauth-token","token_type":"Bearer","expires_in":3600}`)
		case "/send":
			sendCalls++
			if r.Header.Get("Authorization")!="Bearer fixture-oauth-token" { t.Error("missing real OAuth access token") }
			var envelope map[string]json.RawMessage
			if json.NewDecoder(r.Body).Decode(&envelope)!=nil || len(envelope)!=1 { t.Error("provider envelope") }
			var message struct { Token string `json:"token"`; Data map[string]string `json:"data"`; Android map[string]string `json:"android"` }
			var messageKeys map[string]json.RawMessage
			json.Unmarshal(envelope["message"],&messageKeys); json.Unmarshal(envelope["message"],&message)
			if len(messageKeys)!=3 || message.Token!="fixture-provider-token" || !reflect.DeepEqual(message.Data,want) || len(message.Android)!=2 || message.Android["priority"]!="HIGH" { t.Error("payload was not strict data-only minimal reference") }
			if unregistered { w.WriteHeader(404); io.WriteString(w,`{"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}`); return }
			io.WriteString(w,`{"name":"projects/fixture-project/messages/synthetic"}`)
		default: t.Error("unexpected provider path"); w.WriteHeader(404)
		}
	}))
	defer fixture.Close()
	fixtureURL=fixture.URL
	provider.tokenURL=fixture.URL+"/token"; provider.sendURL=fixture.URL+"/send"
	if err:=provider.Send(context.Background(),"fixture-provider-token",reference); err!=nil { t.Fatal(err) }
	if err:=provider.Send(context.Background(),"fixture-provider-token",reference); err!=nil { t.Fatal(err) }
	if oauthCalls!=1 || sendCalls!=2 { t.Fatal("OAuth cache or HTTP v1 send not exercised") }
	unregistered=true
	if err:=provider.Send(context.Background(),"fixture-provider-token",reference); !errors.Is(err,ErrUnregistered) { t.Fatal("provider revocation not classified",err) }
	reference.Emit.ExpiresAt=time.Now().Add(-time.Second)
	if err:=provider.Send(context.Background(),"fixture-provider-token",reference); !errors.Is(err,ErrInvalid) || sendCalls!=3 { t.Fatal("expired reference reached provider") }
	provider.accessToken=""
	expireDuringOAuth=true
	reference.Emit.ExpiresAt=time.Now().Add(20*time.Millisecond)
	if err:=provider.Send(context.Background(),"fixture-provider-token",reference); !errors.Is(err,ErrInvalid) || sendCalls!=3 { t.Fatal("reference expired during OAuth reached provider") }
}
