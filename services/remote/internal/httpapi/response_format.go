package httpapi

import (
	"net/http"

	"dsh-mobile-ustc/services/remote/internal/requestlog"
)

// Preserve ServeMux method matching, Allow and legacy responses. Only its own
// unmatched-path/method errors get the negotiated JSON envelope.
func routerWithJSONErrors(mux *http.ServeMux) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if requestlog.JSONEnabled(r.Context()) {
			if _, pattern := mux.Handler(r); pattern == "" {
				w = &routingErrorWriter{ResponseWriter: w}
			}
		}
		mux.ServeHTTP(w, r)
	})
}

type routingErrorWriter struct {
	http.ResponseWriter
	rewritten bool
}

func (w *routingErrorWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *routingErrorWriter) WriteHeader(status int) {
	if w.rewritten {
		return
	}
	switch status {
	case http.StatusNotFound:
		w.rewritten = true
		writeError(w.ResponseWriter, status, "route.not_found")
	case http.StatusMethodNotAllowed:
		w.rewritten = true
		writeError(w.ResponseWriter, status, "route.method_not_allowed")
	default:
		w.ResponseWriter.WriteHeader(status)
	}
}
func (w *routingErrorWriter) Write(body []byte) (int, error) {
	if w.rewritten {
		return len(body), nil
	}
	return w.ResponseWriter.Write(body)
}
