package httpapi

import (
	"errors"
	"net/http"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/push"
)

func pushActorFrom(request *http.Request) push.Actor {
	principal, _ := auth.PrincipalFromContext(request.Context())
	device, _ := request.Context().Value(deviceContextKey{}).(string)
	return push.Actor{Issuer: principal.Issuer, Subject: principal.Subject, DeviceID: device, ProofJKT: principal.ProofKeyJKT}
}

func (api *API) pushEnabled(response http.ResponseWriter) bool {
	response.Header().Set("Cache-Control", "no-store")
	if api.push == nil {
		writeError(response, http.StatusNotImplemented, "push.unsupported")
		return false
	}
	return true
}

func writePushError(response http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, push.ErrInvalid): writeError(response, http.StatusBadRequest, "protocol.invalid")
	case errors.Is(err, push.ErrDenied): writeError(response, http.StatusForbidden, "access.denied")
	default: writeError(response, http.StatusServiceUnavailable, "push.unavailable")
	}
}

func (api *API) registerPush(response http.ResponseWriter, request *http.Request) {
	if !api.pushEnabled(response) { return }
	var registration push.Registration
	if request.URL.RawQuery != "" || !decodeBody(response, request, &registration) {
		writeError(response, http.StatusBadRequest, "protocol.invalid")
		return
	}
	binding, err := api.push.Register(request.Context(), pushActorFrom(request), registration)
	if err != nil { writePushError(response, err); return }
	writeData(response, http.StatusOK, map[string]string{"binding_id": binding})
}

func (api *API) revokePush(response http.ResponseWriter, request *http.Request) {
	if !api.pushEnabled(response) { return }
	if request.URL.RawQuery != "" { writeError(response, http.StatusBadRequest, "protocol.invalid"); return }
	if err := api.push.Revoke(request.Context(), pushActorFrom(request), request.PathValue("id")); err != nil { writePushError(response, err); return }
	writeData(response, http.StatusOK, map[string]bool{"revoked": true})
}

func (api *API) emitPush(response http.ResponseWriter, request *http.Request) {
	if !api.pushEnabled(response) { return }
	var emit push.Emit
	if request.URL.RawQuery != "" || !decodeBody(response, request, &emit) { writeError(response, http.StatusBadRequest, "protocol.invalid"); return }
	installation, _ := installationFrom(request)
	if err := api.push.Emit(request.Context(), push.Installation{ID: installation.InstallationID, KeyVersion: installation.KeyVersion, ExpiresAt: installation.ExpiresAt}, emit); err != nil { writePushError(response, err); return }
	writeData(response, http.StatusOK, map[string]bool{"accepted": true})
}
