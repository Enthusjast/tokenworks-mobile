package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
)

func TestCarrierRequiresFreshProofAndRemovesPlaintextAPI(t *testing.T) {
	identity, err := auth.New(auth.Config{Issuer:"https://issuer.example", Audience:"remote", JWKSURL:"https://issuer.example/jwks",
		AllowedClients:[]string{"phone","host"}, RequiredScopes:[]string{"remote"}, PublicOrigin:"https://remote.example", MaxTokenLifetime:time.Hour})
	if err != nil { t.Fatal(err) }
	handler := New(&store.Postgres{}, identity, Config{HostClientIDs:[]string{"host"}})
	for _, authorization := range []string{"", "DPoP malformed-expired-token"} {
		request := httptest.NewRequest("GET", "/v2/runtimes/aaaaaaaa-1111-4111-8111-111111111111/carrier", nil)
		request.Header.Set("Authorization", authorization)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusUnauthorized { t.Fatalf("carrier bypassed authentication: %d", response.Code) }
	}
	for _, method := range []string{"GET","POST","DELETE"} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(method,"/api/session/list",nil))
		if response.Code != http.StatusNotFound { t.Fatalf("plaintext native route survived: %s %d",method,response.Code) }
	}
}

type leaseInspectionRepository struct {
	Repository
	lease store.TunnelLeaseIdentity
}
func (repository *leaseInspectionRepository) InspectTunnelLease(context.Context,string,string,string)(store.TunnelLeaseIdentity,error) {
	return repository.lease,nil
}

func TestLeaseIntrospectionPreservesRoleAndInstallationOwnership(t *testing.T) {
	for _, owner := range []bool{false,true} {
		lease := store.TunnelLeaseIdentity{Role:"owner",OrganizationID:"aaaaaaaa-1111-4111-8111-111111111111",IsInstallationOwner:owner,DeviceID:"bbbbbbbb-2222-4222-8222-222222222222",ExpiresAt:time.Now().Add(time.Minute)}
		api := &API{database:&leaseInspectionRepository{lease:lease}}
		request := httptest.NewRequest("GET","/v2/tunnel-leases/dddddddd-4444-4444-8444-444444444444",nil)
		request.SetPathValue("lease_id","dddddddd-4444-4444-8444-444444444444")
		request.Header.Set("Proxy-Authorization","Bearer "+strings.Repeat("a",43))
		request = request.WithContext(context.WithValue(request.Context(),installationContextKey{},store.HostCredentialIdentity{InstallationID:"eeeeeeee-5555-4555-8555-555555555555"}))
		response := httptest.NewRecorder()
		api.inspectTunnelLease(response,request)
		var result struct { Data struct { Role string `json:"role"`; OrganizationID string `json:"organization_id"`; Owner bool `json:"is_installation_owner"` } `json:"data"` }
		if err := json.Unmarshal(response.Body.Bytes(),&result); err != nil { t.Fatal(err) }
		if response.Code != http.StatusOK || result.Data.Role != "owner" || result.Data.OrganizationID != lease.OrganizationID || result.Data.Owner != owner { t.Fatalf("introspection changed authoritative role/ownership/organization: %s",response.Body.String()) }
	}
}
