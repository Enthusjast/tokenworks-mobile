package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
)

type enrollmentTerminalRepository interface {
	ObserveEnrollmentTerminal(context.Context, store.UserIdentity, string, string) (store.EnrollmentTerminal, error)
}
type enrollmentTerminalRequest struct {
	RequestID string `json:"request_id"`
}

func (value *enrollmentTerminalRequest) UnmarshalJSON(raw []byte) error {
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) == nil && len(fields) == 1 && json.Unmarshal(fields["request_id"], &value.RequestID) == nil && uuidPattern.MatchString(value.RequestID) {
		return nil
	}
	return errors.New("invalid enrollment terminal request")
}
func (api *API) observeEnrollmentTerminal(w http.ResponseWriter, r *http.Request) {
	var body enrollmentTerminalRequest
	if r.URL.RawQuery != "" || r.URL.ForceQuery || !decodeBody(w, r, &body) {
		writeError(w, http.StatusBadRequest, "protocol.invalid")
		return
	}
	repository, ok := api.database.(enrollmentTerminalRepository)
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "internal")
		return
	}
	principal, _ := auth.PrincipalFromContext(r.Context())
	result, err := repository.ObserveEnrollmentTerminal(r.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, principal.ProofKeyJKT, body.RequestID)
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	writeData(w, http.StatusOK, result)
}
