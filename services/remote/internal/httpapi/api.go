package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/relay"
	"tokenworks-mobile/services/remote/internal/releases"
	"tokenworks-mobile/services/remote/internal/store"
)

const maxJSONRequestBytes = 16 << 10

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

type Repository interface {
	Ready(context.Context) error
	AccountIdentity(context.Context, store.UserIdentity) (store.AccountIdentity, error)
	AppReleaseAccess(context.Context, store.UserIdentity, string, string, string, time.Time, releases.PolicyRef) (string, error)
	CurrentDevice(context.Context, store.UserIdentity, string) (store.CurrentDevice, error)
	ListDevices(context.Context, store.UserIdentity, string, string, int, string) (store.DeviceDirectory, error)
	ListInstallations(context.Context, store.UserIdentity, string, string, int, string) (store.InstallationDirectory, error)
	ListInstallationRuntimes(context.Context, store.UserIdentity, string, string, string, int, string) (store.InstallationRuntimeDirectory, error)
	RevokeInstallationRuntime(context.Context, store.UserIdentity, string, string, string, string) error
	ListRuntimeGrants(context.Context, store.UserIdentity, string, string, string, int, string) (store.RuntimeGrantDirectory, error)
	RevokeInstallation(context.Context, store.UserIdentity, string, string, string) error
	ActiveDevice(context.Context, store.UserIdentity, string) (string, error)
	CreateEnrollment(context.Context, store.EnrollmentInput) (store.EnrollmentCreated, error)
	GetEnrollment(context.Context, store.UserIdentity, string) (store.EnrollmentInfo, error)
	PreviewEnrollment(context.Context, store.UserIdentity, string, string) (store.EnrollmentInfo, error)
	ConfirmEnrollmentUser(context.Context, store.UserIdentity, string, string, string, string) (store.EnrollmentConfirmation, error)
	ConfirmEnrollmentHost(context.Context, store.UserIdentity, string, string, string, string) (store.EnrollmentConfirmation, error)
	CreateHostCredentialChallenge(context.Context, store.UserIdentity, string) (store.HostCredentialChallenge, error)
	ExchangeHostCredential(context.Context, store.UserIdentity, string, string, string) (store.HostConnectionCredential, error)
	LookupHostConnectionCredential(context.Context, string) (store.HostCredentialIdentity, error)
	RegisterDevice(context.Context, store.DeviceRegistration) (string, error)
	RevokeDevice(context.Context, string, string, string) error
	PutRuntimeGrant(context.Context, store.UserIdentity, string, string, string, string, store.RuntimeGrantInput) (int64, error)
	RevokeRuntimeGrant(context.Context, store.UserIdentity, string, string, string, string) (int64, error)
	IssueTunnelLease(context.Context, store.UserIdentity, string, string) (store.TunnelLease, error)
	InspectTunnelLease(context.Context, string, string, string) (store.TunnelLeaseIdentity, error)
	VerifyTunnelLease(context.Context, store.UserIdentity, string, string, string) (store.TunnelLeaseIdentity, error)
	ListHosts(context.Context, store.UserIdentity) ([]store.Host, error)
	HostDirectory(context.Context, store.UserIdentity, string, string, int, string) (store.HostDirectory, error)
	RuntimeDirectory(context.Context, store.UserIdentity, string, string, int, string) (store.RuntimeDirectory, error)
	ListRuntimes(context.Context, store.UserIdentity) ([]store.Runtime, error)
	AttachRuntimeConnection(context.Context, store.RuntimeConnectionInput) (store.RuntimeConnection, error)
	TouchRuntimeConnection(context.Context, string, string, string, string) error
	DetachRuntimeConnection(context.Context, string, string, string, string) error
}

type API struct {
	database    Repository
	identity    *auth.Authenticator
	hostClients map[string]struct{}
	realtime    *relay.Server
	releases    *releases.Catalog
}

type Handler struct {
	router   http.Handler
	realtime *relay.Server
	api      *API
}

func (handler *Handler) ServeHTTP(response http.ResponseWriter, request *http.Request) {
	handler.router.ServeHTTP(response, request)
}

func (handler *Handler) Drain(ctx context.Context) error {
	return handler.realtime.Drain(ctx)
}

func (handler *Handler) ApplyControlEvent(ctx context.Context, event store.ControlEvent) error {
	return handler.realtime.ApplyControlEvent(ctx, event)
}

func (handler *Handler) RevalidateConnections(ctx context.Context) error {
	return handler.realtime.RevalidateConnections(ctx)
}

func (handler *Handler) ConfigureRouting(config relay.RoutingConfig) error {
	return handler.realtime.ConfigureRouting(config)
}

func (handler *Handler) PrivateRelayHandler(next http.Handler) http.Handler {
	return handler.realtime.PrivateRelayHandler(next)
}

type deviceContextKey struct{}

type installationContextKey struct{}

type Config struct {
	HostClientIDs []string
	Releases      *releases.Catalog
}

type deviceRequest struct {
	Name              string          `json:"name"`
	ApprovalPublicJWK json.RawMessage `json:"approval_public_jwk"`
	ApprovalProof     string          `json:"approval_proof"`
	ProofPublicJWK    json.RawMessage `json:"proof_public_jwk"`
}

type enrollmentRequest struct {
	RequestID             string          `json:"request_id"`
	OrganizationID        string          `json:"organization_id"`
	HostID                string          `json:"host_id,omitempty"`
	Alias                 string          `json:"alias"`
	ProfileRef            string          `json:"profile_ref"`
	DSHVersion            string          `json:"dsh_version"`
	ApplicationVersion    string          `json:"application_version"`
	PluginVersion         string          `json:"plugin_version"`
	ProtocolVersion       int             `json:"protocol_version"`
	InstallationPublicJWK json.RawMessage `json:"installation_public_jwk"`
	Code                  string          `json:"code"`
	WorkspaceRefs         *[]string       `json:"workspace_refs"`
}

type enrollmentCodeRequest struct {
	Code string `json:"code"`
}

type enrollmentHostConfirmationRequest struct {
	Code            string `json:"code"`
	ConfirmedUserID string `json:"confirmed_user_id"`
}

type hostCredentialExchangeRequest struct {
	ChallengeID string `json:"challenge_id"`
	Proof       string `json:"proof"`
}

func New(database Repository, identity *auth.Authenticator, configs ...Config) *Handler {
	api := &API{database: database, identity: identity, hostClients: make(map[string]struct{})}
	if len(configs) > 0 {
		for _, id := range configs[0].HostClientIDs {
			if strings.TrimSpace(id) != "" {
				api.hostClients[id] = struct{}{}
			}
		}
		api.releases = configs[0].Releases
	}
	realtime := relay.New(database)
	api.realtime = realtime
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", api.health)
	mux.HandleFunc("GET /readyz", api.ready)
	mux.Handle("GET /v1/me", api.authenticated(http.HandlerFunc(api.me)))
	if api.releases != nil {
		mux.Handle("GET /v1/app/releases/latest", api.protected(http.HandlerFunc(api.appRelease)))
	}
	mux.Handle("POST /v1/devices", api.phoneAuthenticated(http.HandlerFunc(api.registerDevice)))
	mux.Handle("GET /v1/devices", api.protected(http.HandlerFunc(api.listDevices)))
	mux.Handle("GET /v1/installations", api.protected(http.HandlerFunc(api.listInstallations)))
	mux.Handle("GET /v1/installations/{id}/runtimes", api.protected(http.HandlerFunc(api.listInstallationRuntimes)))
	mux.Handle("DELETE /v1/installations/{id}/runtimes/{runtime_id}", api.protected(http.HandlerFunc(api.revokeInstallationRuntime)))
	mux.Handle("DELETE /v1/installations/{id}", api.protected(http.HandlerFunc(api.revokeInstallation)))
	mux.Handle("GET /v1/devices/current", api.phoneAuthenticated(http.HandlerFunc(api.currentDevice)))
	mux.Handle("POST /v1/enrollments", api.hostAuthenticated(http.HandlerFunc(api.createEnrollment)))
	mux.Handle("POST /v1/enrollments/host-terminal", api.hostAuthenticated(http.HandlerFunc(api.observeEnrollmentTerminal)))
	mux.Handle("GET /v1/enrollments", api.protected(http.HandlerFunc(api.listEnrollmentApprovals)))
	mux.Handle("GET /v1/enrollments/{id}", api.authenticated(http.HandlerFunc(api.getEnrollment)))
	mux.Handle("GET /v1/enrollments/{id}/host-review", api.hostAuthenticated(http.HandlerFunc(api.reviewEnrollmentHost)))
	mux.Handle("POST /v1/enrollments/{id}/preview", api.protected(http.HandlerFunc(api.previewEnrollment)))
	mux.Handle("POST /v1/enrollments/{id}/confirm", api.protected(http.HandlerFunc(api.confirmEnrollmentUser)))
	mux.Handle("POST /v1/enrollments/{id}/host-confirm", api.hostAuthenticated(http.HandlerFunc(api.confirmEnrollmentHost)))
	mux.Handle("GET /v1/enrollments/{id}/admin-review", api.protected(http.HandlerFunc(api.reviewEnrollmentAdmin)))
	mux.Handle("POST /v1/enrollments/{id}/admin-approve", api.protected(http.HandlerFunc(api.approveEnrollmentAdmin)))
	mux.Handle("POST /v1/enrollments/{id}/admin-deny", api.protected(http.HandlerFunc(api.denyEnrollmentAdmin)))
	mux.Handle("POST /v1/host-credentials/challenges", api.hostAuthenticated(http.HandlerFunc(api.createHostCredentialChallenge)))
	mux.Handle("POST /v1/host-credentials", api.hostAuthenticated(http.HandlerFunc(api.exchangeHostCredential)))
	mux.Handle("DELETE /v1/devices/{id}", api.protected(http.HandlerFunc(api.revokeDevice)))
	mux.Handle("GET /v1/hosts", api.protected(http.HandlerFunc(api.listHosts)))
	mux.Handle("GET /v1/host-directory", api.protected(http.HandlerFunc(api.hostDirectory)))
	mux.Handle("GET /v1/runtime-directory", api.protected(http.HandlerFunc(api.runtimeDirectory)))
	mux.Handle("GET /v1/runtimes", api.protected(http.HandlerFunc(api.listRuntimes)))
	mux.Handle("GET /v1/runtimes/{id}/grants", api.protected(http.HandlerFunc(api.listRuntimeGrants)))
	mux.Handle("PUT /v1/runtimes/{id}/grants/{user_id}", api.protected(http.HandlerFunc(api.putRuntimeGrant)))
	mux.Handle("DELETE /v1/runtimes/{id}/grants/{user_id}", api.protected(http.HandlerFunc(api.revokeRuntimeGrant)))
	mux.Handle("POST /v2/runtimes/{id}/leases", api.protected(http.HandlerFunc(api.issueTunnelLease)))
	mux.Handle("GET /v2/tunnel-leases/{lease_id}", api.installationAuthenticated(http.HandlerFunc(api.inspectTunnelLease)))
	mux.Handle("GET /v2/runtimes/{id}/tunnel", api.installationAuthenticated(http.HandlerFunc(api.hostTunnel)))
	mux.Handle("/api/", api.phoneAuthenticated(http.HandlerFunc(api.deviceAPI)))
	return &Handler{router: routerWithJSONErrors(mux), realtime: realtime, api: api}
}

func (api *API) protected(next http.Handler) http.Handler {
	return api.phoneAuthenticated(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		principal, _ := auth.PrincipalFromContext(request.Context())
		deviceID, err := api.database.ActiveDevice(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, principal.ProofKeyJKT)
		if err != nil {
			api.writeStoreError(response, err)
			return
		}
		ctx := context.WithValue(request.Context(), deviceContextKey{}, deviceID)
		next.ServeHTTP(response, request.WithContext(ctx))
	}))
}

func (api *API) authenticated(next http.Handler) http.Handler {
	return api.identity.Middleware(next)
}

func (api *API) phoneAuthenticated(next http.Handler) http.Handler {
	return api.authenticated(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		principal, _ := auth.PrincipalFromContext(request.Context())
		if _, host := api.hostClients[principal.ClientID]; host {
			writeError(response, http.StatusForbidden, "access.denied")
			return
		}
		next.ServeHTTP(response, request)
	}))
}

func (api *API) hostAuthenticated(next http.Handler) http.Handler {
	return api.authenticated(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		principal, _ := auth.PrincipalFromContext(request.Context())
		if _, ok := api.hostClients[principal.ClientID]; !ok {
			writeError(response, http.StatusForbidden, "access.denied")
			return
		}
		next.ServeHTTP(response, request)
	}))
}

// installationAuthenticated admits the opaque installation credential with a
// standard DPoP proof bound to the request method and URL.
func (api *API) installationAuthenticated(next http.Handler) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		values := request.Header.Values("Authorization")
		if len(values) != 1 {
			writeError(response, http.StatusUnauthorized, "host_credential.invalid")
			return
		}
		scheme, credential, found := strings.Cut(values[0], " ")
		if !found || !strings.EqualFold(scheme, "DPoP") || credential == "" || strings.TrimSpace(credential) != credential {
			writeError(response, http.StatusUnauthorized, "host_credential.invalid")
			return
		}
		identity, err := api.database.LookupHostConnectionCredential(request.Context(), credential)
		if err != nil {
			api.writeStoreError(response, err)
			return
		}
		proofs := request.Header.Values("DPoP")
		if len(proofs) != 1 {
			writeError(response, http.StatusUnauthorized, "auth.proof_invalid")
			return
		}
		nonce, proofErr := api.identity.InstallationDPoP(request, credential, identity.PublicJWK, proofs[0])
		if nonce != "" {
			response.Header().Set("DPoP-Nonce", nonce)
			response.Header().Set("Cache-Control", "no-store")
		}
		if proofErr != nil {
			if errors.Is(proofErr, auth.ErrProofStateUnavailable) {
				writeError(response, http.StatusServiceUnavailable, "internal")
				return
			}
			response.Header().Set("WWW-Authenticate", `DPoP error="use_dpop_nonce"`)
			writeError(response, http.StatusUnauthorized, "auth.proof_invalid")
			return
		}
		if !identity.ExpiresAt.After(time.Now()) {
			writeError(response, http.StatusUnauthorized, "host_credential.invalid")
			return
		}
		next.ServeHTTP(response, request.WithContext(context.WithValue(request.Context(), installationContextKey{}, identity)))
	})
}

func singleHeader(request *http.Request, name string) string {
	values := request.Header.Values(name)
	if len(values) != 1 {
		return ""
	}
	return values[0]
}

func installationFrom(request *http.Request) (store.HostCredentialIdentity, bool) {
	identity, ok := request.Context().Value(installationContextKey{}).(store.HostCredentialIdentity)
	return identity, ok
}

func (api *API) issueTunnelLease(response http.ResponseWriter, request *http.Request) {
	runtimeID := request.PathValue("id")
	if !uuidPattern.MatchString(runtimeID) || request.URL.RawQuery != "" {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	deviceID, _ := request.Context().Value(deviceContextKey{}).(string)
	lease, err := api.database.IssueTunnelLease(request.Context(),
		store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, deviceID, runtimeID)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusCreated, map[string]any{
		"access_token": lease.Secret, "token_type": "Bearer", "expires_in": 300, "lease_id": lease.ID,
	})
}

func (api *API) inspectTunnelLease(response http.ResponseWriter, request *http.Request) {
	identity, ok := installationFrom(request)
	if !ok {
		writeError(response, http.StatusForbidden, "access.denied")
		return
	}
	leaseID := request.PathValue("lease_id")
	values := request.Header.Values("Proxy-Authorization")
	if !uuidPattern.MatchString(leaseID) || len(values) != 1 {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	scheme, secret, found := strings.Cut(values[0], " ")
	if !found || !strings.EqualFold(scheme, "Bearer") || len(secret) != 43 {
		writeError(response, http.StatusUnauthorized, "lease.invalid")
		return
	}
	lease, err := api.database.InspectTunnelLease(request.Context(), identity.InstallationID, leaseID, secret)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	writeData(response, http.StatusOK, map[string]any{
		"active": true, "sub": lease.UserID, "aud": lease.RuntimeID,
		"exp": lease.ExpiresAt.Unix(), "client_id": lease.DeviceID,
		"device_id": lease.DeviceID, "runtime_id": lease.RuntimeID, "installation_id": lease.InstallationID,
		"grant_version": lease.GrantVersion, "bindings": lease.Bindings,
	})
}

func (api *API) hostTunnel(response http.ResponseWriter, request *http.Request) {
	identity, ok := installationFrom(request)
	if !ok {
		writeError(response, http.StatusForbidden, "access.denied")
		return
	}
	api.realtime.ServeTunnel(request.PathValue("id"), identity, response, request)
}

// deviceAPI forwards the whole native request. The Relay never decodes it.
func (api *API) deviceAPI(response http.ResponseWriter, request *http.Request) {
	principal, _ := auth.PrincipalFromContext(request.Context())
	runtimeID := singleHeader(request, "X-TokenWorks-Runtime")
	secret := singleHeader(request, "X-TokenWorks-Lease")
	if !uuidPattern.MatchString(runtimeID) || len(secret) != 43 {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	lease, forwarded, err := api.admitAndRoute(response, request, store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, runtimeID, secret)
	if err != nil || forwarded {
		return
	}
	api.realtime.Proxy(response, request, lease, secret)
}

// forwardedDeviceAPI serves requests from another Relay node. The peer is
// already authenticated by mutual TLS; the lease still proves the device.
func (api *API) forwardedDeviceAPI(response http.ResponseWriter, request *http.Request) {
	runtimeID := singleHeader(request, "X-TokenWorks-Runtime")
	secret := singleHeader(request, "X-TokenWorks-Lease")
	if !uuidPattern.MatchString(runtimeID) || len(secret) != 43 {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	lease, err := api.realtime.AdmitPhone(request.Context(), store.UserIdentity{}, "", runtimeID, secret)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	api.realtime.Proxy(response, request, lease, secret)
}

func (api *API) admitAndRoute(response http.ResponseWriter, request *http.Request, identity store.UserIdentity, runtimeID, secret string) (store.TunnelLeaseIdentity, bool, error) {
	principal, ok := auth.PrincipalFromContext(request.Context())
	if !ok {
		writeError(response, http.StatusUnauthorized, "auth.expired")
		return store.TunnelLeaseIdentity{}, false, errors.New("auth")
	}
	deviceID, err := api.database.ActiveDevice(request.Context(), identity, principal.ProofKeyJKT)
	if err != nil {
		api.writeStoreError(response, err)
		return store.TunnelLeaseIdentity{}, false, err
	}
	lease, err := api.realtime.AdmitPhone(request.Context(), identity, deviceID, runtimeID, secret)
	if err != nil {
		api.writeStoreError(response, err)
		return store.TunnelLeaseIdentity{}, false, err
	}
	if api.realtime.OwnsRuntime(runtimeID) {
		return lease, false, nil
	}
	route, err := api.routingRoute(request.Context(), runtimeID)
	if err != nil {
		api.writeStoreError(response, err)
		return store.TunnelLeaseIdentity{}, false, err
	}
	api.realtime.ForwardNode(response, request, route)
	return store.TunnelLeaseIdentity{}, true, nil
}

// ForwardedDeviceHandler serves node-to-node requests after the Relay's mTLS
// node admission; it must not be mounted on the public listener.
func (handler *Handler) ForwardedDeviceHandler() http.Handler {
	return http.HandlerFunc(handler.api.forwardedDeviceAPI)
}

func (api *API) routingRoute(ctx context.Context, runtimeID string) (store.RuntimeRoute, error) {
	type routeDirectory interface {
		LookupRuntimeRoute(context.Context, string) (store.RuntimeRoute, error)
	}
	directory, ok := api.database.(routeDirectory)
	if !ok {
		return store.RuntimeRoute{}, store.ErrRuntimeConnectionStale
	}
	return directory.LookupRuntimeRoute(ctx, runtimeID)
}
