package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"sort"
	"strings"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/requestlog"
	"tokenworks-mobile/services/remote/internal/store"
)

func decodeRuntimeGrantBody(response http.ResponseWriter, request *http.Request) (store.RuntimeGrantInput, bool) {
	var envelope struct {
		Role      string            `json:"role"`
		Bindings  []store.GrantBinding `json:"bindings"`
		ExpiresAt *time.Time        `json:"expires_at,omitempty"`
	}
	if !decodeBody(response, request, &envelope) || envelope.Bindings == nil {
		return store.RuntimeGrantInput{}, false
	}
	return store.RuntimeGrantInput{Role: envelope.Role, Bindings: envelope.Bindings, ExpiresAt: envelope.ExpiresAt}, true
}

func decodeBody(response http.ResponseWriter, request *http.Request, target any) bool {
	if !strings.EqualFold(strings.TrimSpace(strings.Split(request.Header.Get("Content-Type"), ";")[0]), "application/json") {
		return false
	}
	body, err := io.ReadAll(http.MaxBytesReader(response, request.Body, maxJSONRequestBytes))
	if err != nil || auth.ValidateJSONObject(body) != nil {
		return false
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return false
	}
	var trailing any
	return errors.Is(decoder.Decode(&trailing), io.EOF)
}

func (api *API) writeStoreError(response http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, store.ErrIdentityNotProvisioned), errors.Is(err, store.ErrDeviceNotFound):
		writeError(response, http.StatusForbidden, "access.denied")
	case errors.Is(err, store.ErrDeviceRegistrationConflict):
		writeError(response, http.StatusConflict, "device.registration_conflict")
	case errors.Is(err, store.ErrDeviceDirectoryInvalid):
		writeError(response, http.StatusBadRequest, "protocol.invalid")
	case errors.Is(err, store.ErrInstallationManagementInvalid):
		writeError(response, http.StatusBadRequest, "protocol.invalid")
	case errors.Is(err, store.ErrGrantDirectoryInvalid):
		writeError(response, http.StatusBadRequest, "protocol.invalid")
	case errors.Is(err, store.ErrEnrollmentDirectoryInvalid):
		writeError(response, http.StatusBadRequest, "protocol.invalid")
	case errors.Is(err, store.ErrResourceDirectoryInvalid):
		writeError(response, http.StatusBadRequest, "protocol.invalid")
	case errors.Is(err, store.ErrAccessDenied), errors.Is(err, store.ErrGrantNotFound):
		writeError(response, http.StatusForbidden, "access.denied")
	case errors.Is(err, store.ErrHostCredentialInvalid):
		writeError(response, http.StatusUnauthorized, "host_credential.invalid")
	case errors.Is(err, store.ErrHostChallengeLimit), errors.Is(err, store.ErrHostCredentialLimit):
		writeError(response, http.StatusTooManyRequests, "rate.limited")
	case errors.Is(err, store.ErrEnrollmentInvalid):
		writeError(response, http.StatusForbidden, "access.denied")
	case errors.Is(err, store.ErrEnrollmentConflict):
		writeError(response, http.StatusConflict, "enrollment.request_conflict")
	case errors.Is(err, store.ErrEnrollmentLimit):
		writeError(response, http.StatusTooManyRequests, "rate.limited")
	case errors.Is(err, store.ErrGrantInvalid):
		writeError(response, http.StatusBadRequest, "grant.invalid")
	case errors.Is(err, store.ErrTunnelLeaseInvalid):
		writeError(response, http.StatusUnauthorized, "lease.invalid")
	case errors.Is(err, store.ErrRuntimeConnectionStale):
		writeError(response, http.StatusServiceUnavailable, "runtime.offline")
	case errors.Is(err, store.ErrDatabaseUnavailable):
		writeError(response, http.StatusServiceUnavailable, "internal")
	default:
		writeError(response, http.StatusInternalServerError, "internal")
	}
}

func writeData(response http.ResponseWriter, status int, data any) {
	requestlog.Data(response, status, data)
}

func writeError(response http.ResponseWriter, status int, code string) {
	requestlog.Error(response, status, code)
}

func sortedScopes(scopes map[string]struct{}) []string {
	items := make([]string, 0, len(scopes))
	for scope := range scopes {
		items = append(items, scope)
	}
	sort.Strings(items)
	return items
}
