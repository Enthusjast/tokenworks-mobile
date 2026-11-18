package httpapi

import (
	"context"
	"embed"
	"errors"
	"io"
	"net/http"
	"strconv"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
)

//go:embed share_reader/*
var readerAssets embed.FS

type SessionShareRepository interface {
	Create(context.Context, store.UserIdentity, string, string, time.Time, []byte) (store.SessionShareCreated, error)
	Read(context.Context, string) ([]byte, error)
}

func (api *API) mountSessionShares(mux *http.ServeMux, repository SessionShareRepository) {
	shares := &sessionShareHTTP{repository: repository, writes: make(chan struct{}, 4), reads: make(chan struct{}, 16)}
	mux.Handle("POST /v1/session-shares", api.protected(http.HandlerFunc(shares.create)))
	mux.HandleFunc("GET /share/{id}", shareReader)
	mux.HandleFunc("GET /share/{id}/ciphertext", shares.read)
	mux.HandleFunc("GET /share-reader/v1.js", shareReaderAsset("v1.js", "text/javascript; charset=utf-8"))
	mux.HandleFunc("GET /share-reader/v1.css", shareReaderAsset("v1.css", "text/css; charset=utf-8"))
}

type sessionShareHTTP struct {
	repository SessionShareRepository
	writes     chan struct{}
	reads      chan struct{}
}

func shareResponseHeaders(w http.ResponseWriter) {
	h := w.Header()
	h.Set("Cache-Control", "no-store")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Cross-Origin-Resource-Policy", "same-origin")
	h.Set("X-Robots-Tag", "noindex, nofollow, noarchive")
}

func (shares *sessionShareHTTP) create(w http.ResponseWriter, r *http.Request) {
	shareResponseHeaders(w)
	confirmation := r.Header.Values("X-TokenWorks-Share-Confirmed")
	if len(confirmation) != 1 || confirmation[0] != "v1" || r.URL.RawQuery != "" || r.Header.Get("Content-Type") != "application/octet-stream" || r.Header.Get("Content-Encoding") != "" {
		writeError(w, http.StatusBadRequest, "protocol.invalid")
		return
	}
	principal, ok := auth.PrincipalFromContext(r.Context())
	device, _ := r.Context().Value(deviceContextKey{}).(string)
	if !ok || device == "" {
		writeError(w, http.StatusForbidden, "access.denied")
		return
	}
	select {
	case shares.writes <- struct{}{}:
		defer func() { <-shares.writes }()
	default:
		writeError(w, http.StatusTooManyRequests, "rate.limited")
		return
	}
	if r.ContentLength > store.SessionShareMaxBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "share.too_large")
		return
	}
	control := http.NewResponseController(w)
	_ = control.SetReadDeadline(time.Now().Add(15 * time.Second))
	ciphertext, err := io.ReadAll(http.MaxBytesReader(w, r.Body, store.SessionShareMaxBytes))
	_ = control.SetReadDeadline(time.Time{})
	var tooLarge *http.MaxBytesError
	if errors.As(err, &tooLarge) {
		writeError(w, http.StatusRequestEntityTooLarge, "share.too_large")
		return
	}
	if err != nil || len(ciphertext) < store.SessionShareMinBytes || ciphertext[0] != 1 {
		writeError(w, http.StatusBadRequest, "protocol.invalid")
		return
	}
	created, err := shares.repository.Create(r.Context(), store.UserIdentity{Issuer: principal.Issuer, Subject: principal.Subject}, device, principal.ProofKeyJKT, principal.ExpiresAt, ciphertext)
	switch {
	case err == nil:
		writeData(w, http.StatusCreated, created)
	case errors.Is(err, store.ErrSessionShareQuota):
		writeError(w, http.StatusTooManyRequests, "rate.limited")
	case errors.Is(err, store.ErrAccessDenied):
		writeError(w, http.StatusForbidden, "access.denied")
	default:
		writeError(w, http.StatusServiceUnavailable, "share.unavailable")
	}
}

var serveShareReader = shareReaderAsset("index.html", "text/html; charset=utf-8")

func shareReader(w http.ResponseWriter, r *http.Request) {
	if !store.ValidSessionShareID(r.PathValue("id")) || r.URL.RawQuery != "" {
		shareResponseHeaders(w)
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	serveShareReader(w, r)
}

func shareReaderAsset(file, contentType string) http.HandlerFunc {
	asset, err := readerAssets.ReadFile("share_reader/" + file)
	if err != nil {
		panic("share reader asset was not embedded")
	}
	return func(w http.ResponseWriter, _ *http.Request) {
		shareResponseHeaders(w)
		w.Header().Set("Content-Type", contentType)
		_, _ = w.Write(asset)
	}
}

func (shares *sessionShareHTTP) read(w http.ResponseWriter, r *http.Request) {
	shareResponseHeaders(w)
	id := r.PathValue("id")
	if !store.ValidSessionShareID(id) || r.URL.RawQuery != "" {
		writeError(w, http.StatusNotFound, "share.unavailable")
		return
	}
	select {
	case shares.reads <- struct{}{}:
		defer func() { <-shares.reads }()
	default:
		writeError(w, http.StatusTooManyRequests, "rate.limited")
		return
	}
	ciphertext, err := shares.repository.Read(r.Context(), id)
	if errors.Is(err, store.ErrSessionShareNotFound) {
		writeError(w, http.StatusNotFound, "share.unavailable")
		return
	}
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "share.unavailable")
		return
	}
	control := http.NewResponseController(w)
	_ = control.SetWriteDeadline(time.Now().Add(20 * time.Second))
	defer control.SetWriteDeadline(time.Time{})
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(ciphertext)))
	_, _ = w.Write(ciphertext)
}
