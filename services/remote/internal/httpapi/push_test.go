package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
)

func TestPushRoutesRequireFreshProof(t *testing.T) {
	identity,err:=auth.New(auth.Config{Issuer:"https://issuer.example",Audience:"remote",JWKSURL:"https://issuer.example/jwks",
		AllowedClients:[]string{"phone","host"},RequiredScopes:[]string{"remote"},PublicOrigin:"https://remote.example",MaxTokenLifetime:time.Hour})
	if err!=nil { t.Fatal(err) }
	handler:=New(&store.Postgres{},identity,Config{HostClientIDs:[]string{"host"}})
	for _,test:=range []struct{method,path,authorization string}{
		{"POST","/v1/push/registrations",""},
		{"DELETE","/v1/push/registrations/11111111-1111-4111-8111-111111111111",""},
		{"POST","/v1/push/references",""},
		{"POST","/v1/push/registrations","DPoP malformed-expired-token"},
		{"POST","/v1/push/references","TokenWorks-Mobile-Host not-the-installation-auth-scheme"},
	} {
		request:=httptest.NewRequest(test.method,test.path,strings.NewReader(`{}`))
		if test.authorization!="" { request.Header.Set("Authorization",test.authorization) }
		response:=httptest.NewRecorder();handler.ServeHTTP(response,request)
		if response.Code!=http.StatusUnauthorized { t.Fatalf("%s %s bypassed authentication: %d",test.method,test.path,response.Code) }
	}
}

func TestPushAbsentConfigurationTruthfullyUnsupported(t *testing.T) {
	api:=&API{}
	for _,handler:=range []http.HandlerFunc{api.registerPush,api.revokePush,api.emitPush} {
		response:=httptest.NewRecorder();handler(response,httptest.NewRequest("POST","/v1/push/references",strings.NewReader(`{}`)))
		if response.Code!=http.StatusNotImplemented || !strings.Contains(response.Body.String(),"push.unsupported") || response.Header().Get("Cache-Control")!="no-store" { t.Fatal("unconfigured provider claimed support") }
	}
}

func TestPushStrictInputRejectsSensitivePayloadFields(t *testing.T) {
	for _,body:=range []string{
		`{"notification_id":"11111111-1111-4111-8111-111111111111","body":"secret"}`,
		`{"runtime_id":"11111111-1111-4111-8111-111111111111","tool_args":{}}`,
		`{"kind":"interaction","kind":"completed"}`,
		`{"prompt":"secret","audio":"secret"}`,
	} {
		request:=httptest.NewRequest("POST","/v1/push/references",strings.NewReader(body));request.Header.Set("Content-Type","application/json")
		var input struct { NotificationID string `json:"notification_id"`;RuntimeID string `json:"runtime_id"`;Kind string `json:"kind"` }
		if decodeBody(httptest.NewRecorder(),request,&input) { t.Fatal("sensitive or duplicate input accepted") }
	}
}
