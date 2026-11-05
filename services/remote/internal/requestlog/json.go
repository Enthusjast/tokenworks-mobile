package requestlog

import (
	"context"
	"encoding/json"
	"net/http"
)

const EnvelopeHeader = "X-TokenWorks-Mobile-Response-Envelope"
const RequestIDEnvelope = "request-id.v1"

func JSONEnabled(ctx context.Context) bool {
	value, ok := ctx.Value(contextKey{}).(*attempt)
	return ok && value.jsonEnvelope
}

// Data and Error are the only standard JSON envelope constructors. The payload
// is encoded as before; only explicitly negotiated responses add metadata.
func Data(w http.ResponseWriter, status int, data any) {
	writeJSON(w, status, map[string]any{"ok": true, "data": data})
}

func Error(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]any{"ok": false, "error": map[string]string{"code": code}})
}

func writeJSON(w http.ResponseWriter, status int, body map[string]any) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json")
	w.Header().Add("Vary", EnvelopeHeader)
	w.Header().Del(EnvelopeHeader)
	// Other transport wrappers may enclose the correlation writer. Limit
	// traversal defensively and never trust response/request header contents.
	current := w
	for range 16 {
		if owned, ok := current.(*writer); ok {
			if owned.attempt.jsonEnvelope {
				body["request_id"] = owned.attempt.id.Load().(string)
				w.Header().Set(EnvelopeHeader, RequestIDEnvelope)
			}
			break
		}
		wrapped, ok := current.(interface{ Unwrap() http.ResponseWriter })
		if !ok {
			break
		}
		current = wrapped.Unwrap()
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
