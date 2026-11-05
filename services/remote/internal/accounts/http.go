package accounts

import (
	"bytes"
	"context"
	"embed"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/requestlog"
)

//go:embed web/index.html web/app.js web/style.css
var web embed.FS

type Portal struct {
	service      *Service
	origin       string
	secure       bool
	registration bool
}

func NewPortal(service *Service, origin string, registration bool) (*Portal, error) {
	u, err := url.Parse(origin)
	if err != nil || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path != "" || u.Opaque != "" || u.Host == "" || origin != u.Scheme+"://"+u.Host {
		return nil, ErrInput
	}
	loopback := u.Hostname() == "localhost"
	if ip := net.ParseIP(u.Hostname()); ip != nil {
		loopback = ip.IsLoopback()
	}
	if u.Scheme != "https" && !(u.Scheme == "http" && loopback) {
		return nil, ErrInput
	}
	if service == nil {
		return nil, ErrInput
	}
	return &Portal{service: service, origin: origin, secure: u.Scheme == "https", registration: registration}, nil
}
func (p *Portal) cookieName(csrf bool) string {
	name := "tokenworks_mobile_account"
	if csrf {
		name += "_csrf"
	}
	if p.secure {
		name = "__Secure-" + name
	}
	return name
}
func (p *Portal) cookie(r *http.Request, csrf bool) string {
	var value string
	for _, cookie := range r.Cookies() {
		if cookie.Name == p.cookieName(csrf) {
			if value != "" {
				return ""
			}
			value = cookie.Value
		}
	}
	return value
}
func (p *Portal) cookies(w http.ResponseWriter, token, csrf string) {
	for _, item := range []struct {
		csrf  bool
		value string
	}{{false, token}, {true, csrf}} {
		age := int(sessionAge.Seconds())
		if token == "" {
			age = -1
		}
		http.SetCookie(w, &http.Cookie{Name: p.cookieName(item.csrf), Value: item.value, Path: "/account", HttpOnly: true, Secure: p.secure, SameSite: http.SameSiteStrictMode, MaxAge: age})
	}
}

// Browser routes intentionally do not authenticate the existing DPoP API.
func (p *Portal) Handler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" && r.URL.Path != "/account" && !strings.HasPrefix(r.URL.Path, "/account/") {
			next.ServeHTTP(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		expected, _ := url.Parse(p.origin)
		if r.Host != expected.Host {
			p.fail(w, 403, "account.origin_invalid")
			return
		}
		if r.Method != "GET" {
			if len(r.Header.Values("Origin")) != 1 || r.Header.Get("Origin") != p.origin || r.Header.Get("Sec-Fetch-Site") == "cross-site" {
				p.fail(w, 403, "account.origin_invalid")
				return
			}
		}
		if r.URL.Path == "/" || r.URL.Path == "/account" {
			if r.Method != "GET" {
				p.fail(w, 405, "account.method_invalid")
				return
			}
			http.Redirect(w, r, "/account/", http.StatusSeeOther)
			return
		}
		if r.Method == "GET" {
			switch r.URL.Path {
			case "/account/":
				p.asset(w, "web/index.html", "text/html; charset=utf-8")
				return
			case "/account/app.js":
				p.asset(w, "web/app.js", "text/javascript; charset=utf-8")
				return
			case "/account/style.css":
				p.asset(w, "web/style.css", "text/css; charset=utf-8")
				return
			case "/account/api/providers":
				p.json(w, 200, map[string]any{"providers": Providers(), "registration": p.registration})
				return
			}
		}
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		defer cancel()
		r = r.WithContext(ctx)
		if r.Method == "POST" && (r.URL.Path == "/account/api/login" || r.URL.Path == "/account/api/register") {
			var input struct {
				Username string `json:"username"`
				Password string `json:"password"`
			}
			if !p.decode(w, r, &input) {
				return
			}
			ip, _, err := net.SplitHostPort(r.RemoteAddr)
			if err != nil {
				ip = r.RemoteAddr
			}
			if err = p.service.Attempt(ctx, ip, input.Username); err != nil {
				p.serviceError(w, err)
				return
			}
			if r.URL.Path == "/account/api/register" {
				if !p.registration {
					p.fail(w, 403, "account.registration_disabled")
					return
				}
				if err = p.service.Register(ctx, input.Username, input.Password); err != nil {
					p.serviceError(w, err)
					return
				}
				p.json(w, 201, map[string]bool{"registered": true})
				return
			}
			token, csrf, err := p.service.Login(ctx, input.Username, input.Password)
			if err != nil {
				p.serviceError(w, err)
				return
			}
			// Retire an existing browser session rather than accumulating replacements.
			if old := p.cookie(r, false); old != "" {
				if err = p.service.Logout(ctx, old); err != nil {
					_ = p.service.Logout(ctx, token)
					p.serviceError(w, err)
					return
				}
			}
			p.cookies(w, token, csrf)
			p.json(w, 200, map[string]string{"csrf": csrf})
			return
		}
		validPath := (r.Method == "GET" && r.URL.Path == "/account/api/session") || (r.Method == "POST" && (r.URL.Path == "/account/api/logout" || r.URL.Path == "/account/api/password"))
		if !validPath {
			p.fail(w, 404, "account.not_found")
			return
		}
		token := p.cookie(r, false)
		session, err := p.service.Current(ctx, token)
		if err != nil {
			p.serviceError(w, err)
			return
		}
		csrf := p.cookie(r, true)
		if r.Method != "GET" {
			csrf = r.Header.Get("X-CSRF-Token")
		}
		if err = p.service.CSRF(ctx, token, csrf); err != nil {
			p.fail(w, 403, "account.csrf_invalid")
			return
		}
		switch r.URL.Path {
		case "/account/api/session":
			identity, hosts, err := p.service.Overview(ctx, session)
			if err != nil {
				p.serviceError(w, err)
				return
			}
			p.json(w, 200, map[string]any{"session": session, "identity": identity, "hosts": hosts, "csrf": csrf, "provider": "password"})
		case "/account/api/logout":
			if err = p.service.Logout(ctx, token); err != nil {
				p.serviceError(w, err)
				return
			}
			p.cookies(w, "", "")
			p.json(w, 200, map[string]bool{"logged_out": true})
		case "/account/api/password":
			var input struct {
				Current  string `json:"current_password"`
				Password string `json:"password"`
			}
			if !p.decode(w, r, &input) {
				return
			}
			ip, _, _ := net.SplitHostPort(r.RemoteAddr)
			if err = p.service.Attempt(ctx, ip, session.Username); err != nil {
				p.serviceError(w, err)
				return
			}
			if err = p.service.ChangePassword(ctx, token, input.Current, input.Password); err != nil {
				p.serviceError(w, err)
				return
			}
			p.cookies(w, "", "")
			p.json(w, 200, map[string]bool{"login_required": true})
		}
	})
}
func (p *Portal) asset(w http.ResponseWriter, path, kind string) {
	body, _ := web.ReadFile(path)
	w.Header().Set("Content-Type", kind)
	_, _ = w.Write(body)
}
func (p *Portal) decode(w http.ResponseWriter, r *http.Request, value any) bool {
	kind, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || kind != "application/json" {
		p.fail(w, 415, "account.json_required")
		return false
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2048))
	if err != nil || auth.ValidateJSONObject(body) != nil {
		p.fail(w, 400, "account.input_invalid")
		return false
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if decoder.Decode(value) != nil {
		p.fail(w, 400, "account.input_invalid")
		return false
	}
	return true
}
func (p *Portal) json(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "data": value, "request_id": w.Header().Get(requestlog.Header)})
}
func (p *Portal) fail(w http.ResponseWriter, status int, code string) {
	requestlog.Error(w, status, code)
}
func (p *Portal) serviceError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrCredentials):
		p.fail(w, 401, "account.credentials_invalid")
	case errors.Is(err, ErrInput):
		p.fail(w, 400, "account.input_invalid")
	case errors.Is(err, ErrExists):
		p.fail(w, 409, "account.username_unavailable")
	case errors.Is(err, ErrLimited):
		w.Header().Set("Retry-After", "600")
		p.fail(w, 429, "account.rate_limited")
	default:
		p.fail(w, 503, "account.unavailable")
	}
}
