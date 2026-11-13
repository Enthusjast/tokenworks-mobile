package httpapi

import (
	"context"
	"net/http"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
)

func (api *API) createEnrollment(response http.ResponseWriter, request *http.Request) {
	var input enrollmentRequest
	if !decodeBody(response, request, &input) || !uuidPattern.MatchString(input.RequestID) ||
		!uuidPattern.MatchString(input.OrganizationID) || input.WorkspaceRefs == nil ||
		input.HostID != "" && !uuidPattern.MatchString(input.HostID) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	keyThumbprint, err := auth.PublicP256JWKThumbprintJSON(input.InstallationPublicJWK)
	if err != nil {
		writeError(response, http.StatusBadRequest, "device.key_invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	if keyThumbprint != principal.ProofKeyJKT {
		writeError(response, http.StatusForbidden, "access.denied")
		return
	}
	enrollmentInput, _, _, err := store.NormalizeEnrollmentInput(store.EnrollmentInput{
		Issuer: principal.Issuer, Subject: principal.Subject,
		RequestID: input.RequestID, OrganizationID: input.OrganizationID, HostID: input.HostID, Alias: input.Alias,
		ProfileRef: input.ProfileRef, DSHVersion: input.DSHVersion,
		ApplicationVersion: input.ApplicationVersion, PluginVersion: input.PluginVersion,
		ProtocolVersion: input.ProtocolVersion, InstallationPublicJWK: input.InstallationPublicJWK,
		InstallationKeyThumbprint: keyThumbprint, Code: input.Code, WorkspaceRefs: *input.WorkspaceRefs,
	})
	if err != nil {
		writeError(response, http.StatusBadRequest, "enrollment.invalid")
		return
	}
	enrollment, err := api.database.CreateEnrollment(request.Context(), enrollmentInput)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusCreated, enrollment)
}

func (api *API) getEnrollment(response http.ResponseWriter, request *http.Request) {
	enrollmentID := request.PathValue("id")
	if !uuidPattern.MatchString(enrollmentID) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	_, hostClient := api.hostClients[principal.ClientID]
	if !hostClient {
		if _, err := api.database.ActiveDevice(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, principal.ProofKeyJKT); err != nil {
			api.writeStoreError(response, err)
			return
		}
	}
	info, err := api.database.GetEnrollment(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, enrollmentID)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	if hostClient && info.InstallationKeyJKT != principal.ProofKeyJKT {
		writeError(response, http.StatusForbidden, "access.denied")
		return
	}
	writeData(response, http.StatusOK, info)
}

func (api *API) reviewEnrollmentHost(response http.ResponseWriter, request *http.Request) {
	enrollmentID := request.PathValue("id")
	if !uuidPattern.MatchString(enrollmentID) || request.URL.RawQuery != "" {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	info, err := api.database.GetEnrollment(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, enrollmentID)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	if !info.HostReviewOwner || !info.HostReviewTargetActive || info.InstallationKeyJKT != principal.ProofKeyJKT {
		writeError(response, http.StatusForbidden, "access.denied")
		return
	}
	writeData(response, http.StatusOK, store.EnrollmentHostReview{Enrollment: info, ConfirmedUser: info.HostReviewUser})
}

func (api *API) confirmEnrollmentUser(response http.ResponseWriter, request *http.Request) {
	api.confirmEnrollment(response, request, false)
}

func (api *API) previewEnrollment(response http.ResponseWriter, request *http.Request) {
	enrollmentID := request.PathValue("id")
	if !uuidPattern.MatchString(enrollmentID) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	var input enrollmentCodeRequest
	if !decodeBody(response, request, &input) {
		writeError(response, http.StatusBadRequest, "enrollment.invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	info, err := api.database.PreviewEnrollment(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, enrollmentID, input.Code)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, info)
}

func (api *API) confirmEnrollmentHost(response http.ResponseWriter, request *http.Request) {
	api.confirmEnrollment(response, request, true)
}

func (api *API) confirmEnrollment(response http.ResponseWriter, request *http.Request, hostConfirmation bool) {
	enrollmentID := request.PathValue("id")
	if !uuidPattern.MatchString(enrollmentID) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	var code, confirmedUserID string
	if hostConfirmation {
		var input enrollmentHostConfirmationRequest
		if !decodeBody(response, request, &input) || !uuidPattern.MatchString(input.ConfirmedUserID) {
			writeError(response, http.StatusBadRequest, "enrollment.invalid")
			return
		}
		code, confirmedUserID = input.Code, input.ConfirmedUserID
	} else {
		var input enrollmentCodeRequest
		if !decodeBody(response, request, &input) {
			writeError(response, http.StatusBadRequest, "enrollment.invalid")
			return
		}
		code = input.Code
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	var confirmation store.EnrollmentConfirmation
	var err error
	if hostConfirmation {
		confirmation, err = api.database.ConfirmEnrollmentHost(request.Context(),
			store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject},
			principal.ProofKeyJKT, enrollmentID, code, confirmedUserID)
	} else {
		deviceID, _ := request.Context().Value(deviceContextKey{}).(string)
		confirmation, err = api.database.ConfirmEnrollmentUser(request.Context(),
			store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject},
			deviceID, principal.ProofKeyJKT, enrollmentID, code)
	}
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, confirmation)
}

func (api *API) createHostCredentialChallenge(response http.ResponseWriter, request *http.Request) {
	principal, _ := auth.PrincipalFromContext(request.Context())
	challenge, err := api.database.CreateHostCredentialChallenge(request.Context(),
		store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, principal.ProofKeyJKT)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusCreated, challenge)
}

func (api *API) exchangeHostCredential(response http.ResponseWriter, request *http.Request) {
	var input hostCredentialExchangeRequest
	if !decodeBody(response, request, &input) || !uuidPattern.MatchString(input.ChallengeID) || input.Proof == "" || len(input.Proof) > 8<<10 {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	credential, err := api.database.ExchangeHostCredential(request.Context(),
		store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, principal.ProofKeyJKT,
		input.ChallengeID, input.Proof)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusCreated, credential)
}

func (api *API) health(response http.ResponseWriter, _ *http.Request) {
	response.Header().Set("Cache-Control", "no-store")
	response.WriteHeader(http.StatusNoContent)
}

func (api *API) ready(response http.ResponseWriter, request *http.Request) {
	ctx, cancel := context.WithTimeout(request.Context(), time.Second)
	defer cancel()
	if err := api.database.Ready(ctx); err != nil {
		writeError(response, http.StatusServiceUnavailable, "internal")
		return
	}
	response.Header().Set("Cache-Control", "no-store")
	response.WriteHeader(http.StatusNoContent)
}

func (api *API) me(response http.ResponseWriter, request *http.Request) {
	principal, _ := auth.PrincipalFromContext(request.Context())
	account, err := api.database.AccountIdentity(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject})
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, map[string]any{
		"issuer": principal.Issuer, "subject": principal.Subject, "client_id": principal.ClientID,
		"scopes": sortedScopes(principal.Scopes), "user_id": account.UserID, "memberships": account.Memberships,
	})
}

func (api *API) currentDevice(response http.ResponseWriter, request *http.Request) {
	principal, _ := auth.PrincipalFromContext(request.Context())
	device, err := api.database.CurrentDevice(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, principal.ProofKeyJKT)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, device)
}

func (api *API) registerDevice(response http.ResponseWriter, request *http.Request) {
	var input deviceRequest
	if !decodeBody(response, request, &input) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	input.Name = strings.TrimSpace(input.Name)
	if input.Name == "" || utf8.RuneCountInString(input.Name) > 160 || strings.IndexFunc(input.Name, unicode.IsControl) >= 0 {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	approvalThumbprint, err := auth.PublicP256JWKThumbprintJSON(input.ApprovalPublicJWK)
	if err != nil {
		writeError(response, http.StatusBadRequest, "device.key_invalid")
		return
	}
	proofThumbprint, err := auth.PublicP256JWKThumbprintJSON(input.ProofPublicJWK)
	if err != nil {
		writeError(response, http.StatusBadRequest, "device.key_invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	if proofThumbprint != principal.ProofKeyJKT {
		writeError(response, http.StatusForbidden, "access.denied")
		return
	}
	if proofThumbprint == approvalThumbprint {
		writeError(response, http.StatusBadRequest, "device.key_invalid")
		return
	}
	if err := api.identity.VerifyDeviceRegistrationProof(input.ApprovalProof, input.ApprovalPublicJWK, principal, input.Name, proofThumbprint); err != nil {
		writeError(response, http.StatusBadRequest, "device.proof_invalid")
		return
	}
	id, err := api.database.RegisterDevice(request.Context(), store.DeviceRegistration{
		Issuer: principal.Issuer, Subject: principal.Subject, Name: input.Name,
		ApprovalPublicJWK: input.ApprovalPublicJWK, ApprovalKeyThumbprint: approvalThumbprint,
		ProofPublicJWK: input.ProofPublicJWK, ProofKeyThumbprint: proofThumbprint,
	})
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusCreated, map[string]any{"device_id": id, "key_version": 1})
}

func (api *API) revokeDevice(response http.ResponseWriter, request *http.Request) {
	id := request.PathValue("id")
	if !uuidPattern.MatchString(id) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	if err := api.database.RevokeDevice(request.Context(), principal.Issuer, principal.Subject, id); err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, map[string]any{"revoked": true})
}

func (api *API) listHosts(response http.ResponseWriter, request *http.Request) {
	principal, _ := auth.PrincipalFromContext(request.Context())
	items, err := api.database.ListHosts(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject})
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, map[string]any{"items": items})
}

func (api *API) listRuntimes(response http.ResponseWriter, request *http.Request) {
	principal, _ := auth.PrincipalFromContext(request.Context())
	items, err := api.database.ListRuntimes(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject})
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, map[string]any{"items": items})
}

func (api *API) putRuntimeGrant(response http.ResponseWriter, request *http.Request) {
	runtimeID := request.PathValue("id")
	targetUserID := request.PathValue("user_id")
	if !uuidPattern.MatchString(runtimeID) || !uuidPattern.MatchString(targetUserID) || request.URL.RawQuery != "" {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	input, ok := decodeRuntimeGrantBody(response, request)
	if !ok {
		writeError(response, http.StatusBadRequest, "grant.invalid")
		return
	}
	input, err := store.NormalizeRuntimeGrantInput(input, time.Now())
	if err != nil {
		writeError(response, http.StatusBadRequest, "grant.invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	deviceID, _ := request.Context().Value(deviceContextKey{}).(string)
	version, err := api.database.PutRuntimeGrant(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, deviceID, principal.ProofKeyJKT, runtimeID, targetUserID, input)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, map[string]any{"grant_version": version})
}

func (api *API) revokeRuntimeGrant(response http.ResponseWriter, request *http.Request) {
	runtimeID := request.PathValue("id")
	targetUserID := request.PathValue("user_id")
	if !uuidPattern.MatchString(runtimeID) || !uuidPattern.MatchString(targetUserID) || request.URL.RawQuery != "" || request.ContentLength != 0 || len(request.TransferEncoding) != 0 {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	principal, _ := auth.PrincipalFromContext(request.Context())
	deviceID, _ := request.Context().Value(deviceContextKey{}).(string)
	version, err := api.database.RevokeRuntimeGrant(request.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, deviceID, principal.ProofKeyJKT, runtimeID, targetUserID)
	if err != nil {
		api.writeStoreError(response, err)
		return
	}
	writeData(response, http.StatusOK, map[string]any{"revoked": true, "grant_version": version})
}

