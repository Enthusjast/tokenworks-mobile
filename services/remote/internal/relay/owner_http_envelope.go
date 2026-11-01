package relay

import (
	"encoding/json"
	"io"
	"mime"
	"net/http"
	"strings"
	"time"
	"unicode"

	"dsh-mobile-ustc/services/remote/internal/auth"
	"dsh-mobile-ustc/services/remote/internal/requestlog"
)

const maxOwnerJSONBytes = 64 << 10

// Only standard metadata envelopes are bounded and reframed. Successful file
// content keeps the existing streaming path, regardless of its actual bytes.
func (server *Server) forwardJSONEnvelope(w http.ResponseWriter, r *http.Request, reply *http.Response) bool {
	if !requestlog.JSONEnabled(r.Context()) {
		return false
	}
	kind, _ := OwnerHTTPResource(r.Method, r.URL.Path)
	if kind == "host_file" && reply.StatusCode >= 200 && reply.StatusCode < 300 {
		return false
	}
	fail := func() bool {
		// A POST may already have committed at the owner. This is not
		// evidence that the runtime was offline or the operation did not run.
		writeError(w, http.StatusServiceUnavailable, "internal")
		return true
	}
	media, parameters, err := mime.ParseMediaType(reply.Header.Get("Content-Type"))
	if err != nil || len(reply.Header.Values("Content-Type")) != 1 || media != "application/json" ||
		len(parameters) != 0 && (len(parameters) != 1 || !strings.EqualFold(parameters["charset"], "utf-8")) ||
		len(reply.Header.Values("Content-Encoding")) > 1 ||
		reply.Header.Get("Content-Encoding") != "" && reply.Header.Get("Content-Encoding") != "identity" ||
		reply.ContentLength > maxOwnerJSONBytes {
		return fail()
	}
	// GET content has a long stream lifetime; its error envelope must not hold
	// a forwarding slot for that whole lifetime. Close only this response body.
	timer := time.AfterFunc(2*time.Second, func() { _ = reply.Body.Close() })
	raw, err := io.ReadAll(io.LimitReader(reply.Body, maxOwnerJSONBytes+1))
	timer.Stop()
	if err != nil || len(raw) > maxOwnerJSONBytes || auth.ValidateJSONObject(raw) != nil {
		return fail()
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil {
		return fail()
	}
	versions := reply.Header.Values(requestlog.EnvelopeHeader)
	if len(versions) == 0 {
		if len(fields) != 2 {
			return fail()
		}
	} else {
		if len(versions) != 1 || versions[0] != requestlog.RequestIDEnvelope || len(fields) != 3 {
			return fail()
		}
		var id string
		ids := reply.Header.Values(requestlog.Header)
		if json.Unmarshal(fields["request_id"], &id) != nil || len(ids) != 1 || !requestlog.ValidID(id) || id != ids[0] {
			return fail()
		}
	}
	var ok *bool
	if json.Unmarshal(fields["ok"], &ok) != nil || ok == nil {
		return fail()
	}
	var code string
	if *ok {
		if fields["data"] == nil || reply.StatusCode < 200 || reply.StatusCode >= 300 {
			return fail()
		}
	} else {
		var problem map[string]json.RawMessage
		if json.Unmarshal(fields["error"], &problem) != nil || len(problem) != 1 || json.Unmarshal(problem["code"], &code) != nil ||
			code == "" || len(code) > 128 || strings.IndexFunc(code, unicode.IsControl) >= 0 || reply.StatusCode < 400 || reply.StatusCode >= 600 {
			return fail()
		}
	}
	// Preserve proof challenges after validating the whole envelope. Rebuilding
	// the body deliberately drops its old byte length and content encoding.
	for _, name := range []string{"DPoP-Nonce", "WWW-Authenticate"} {
		if values := reply.Header.Values(name); len(values) == 1 {
			w.Header().Set(name, values[0])
		}
	}
	w.Header().Del("Content-Length")
	w.Header().Del("Content-Encoding")
	if *ok {
		requestlog.Data(w, reply.StatusCode, fields["data"])
	} else {
		requestlog.Error(w, reply.StatusCode, code)
	}
	return true
}
