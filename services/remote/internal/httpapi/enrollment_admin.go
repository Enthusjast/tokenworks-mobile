package httpapi

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"

	"dsh-mobile-ustc/services/remote/internal/auth"
	"dsh-mobile-ustc/services/remote/internal/store"
)

type enrollmentAdminRepository interface {
	ReviewEnrollmentAdmin(context.Context, store.UserIdentity, string, string, string) (store.EnrollmentAdminReview, error)
	ApproveEnrollmentAdmin(context.Context, store.UserIdentity, string, string, string, string) (store.EnrollmentConfirmation, error)
}

type enrollmentAdminApprovalRequest struct {
	ReviewSHA256 string `json:"review_sha256"`
}

func (value *enrollmentAdminApprovalRequest) UnmarshalJSON(raw []byte) error {
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) == nil && len(fields) == 1 && json.Unmarshal(fields["review_sha256"], &value.ReviewSHA256) == nil {
		decoded, err := hex.DecodeString(value.ReviewSHA256)
		if err == nil && len(decoded) == 32 && hex.EncodeToString(decoded) == value.ReviewSHA256 {
			return nil
		}
	}
	return errors.New("invalid enrollment administrator approval")
}

func (api *API) reviewEnrollmentAdmin(w http.ResponseWriter, r *http.Request) {
	if !uuidPattern.MatchString(r.PathValue("id")) || r.URL.RawQuery != "" || r.URL.ForceQuery || r.ContentLength != 0 || len(r.TransferEncoding) != 0 {
		writeError(w, http.StatusBadRequest, "protocol.invalid")
		return
	}
	repository, ok := api.database.(enrollmentAdminRepository)
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "internal")
		return
	}
	principal, _ := auth.PrincipalFromContext(r.Context())
	device, _ := r.Context().Value(deviceContextKey{}).(string)
	value, err := repository.ReviewEnrollmentAdmin(r.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, device, principal.ProofKeyJKT, r.PathValue("id"))
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	writeData(w, http.StatusOK, value)
}

func (api *API) approveEnrollmentAdmin(w http.ResponseWriter, r *http.Request) {
	var body enrollmentAdminApprovalRequest
	if !uuidPattern.MatchString(r.PathValue("id")) || r.URL.RawQuery != "" || r.URL.ForceQuery || !decodeBody(w, r, &body) {
		writeError(w, http.StatusBadRequest, "protocol.invalid")
		return
	}
	repository, ok := api.database.(enrollmentAdminRepository)
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "internal")
		return
	}
	principal, _ := auth.PrincipalFromContext(r.Context())
	device, _ := r.Context().Value(deviceContextKey{}).(string)
	value, err := repository.ApproveEnrollmentAdmin(r.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, device, principal.ProofKeyJKT, r.PathValue("id"), body.ReviewSHA256)
	if err != nil {
		api.writeStoreError(w, err)
		return
	}
	writeData(w, http.StatusOK, value)
}
