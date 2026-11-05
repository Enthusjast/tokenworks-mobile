package accounts

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"strings"
	"testing"

	"tokenworks-mobile/services/remote/internal/auth"
	"tokenworks-mobile/services/remote/internal/store"
	"tokenworks-mobile/services/remote/internal/testsupport"
)

func TestPasswordAccountsBrowserLifecycleAndSecurityBoundaries(t *testing.T) {
	ctx, db := testsupport.PostgresDatabase(t)
	service, err := New(&store.Postgres{DB: db}, store.OriginalDatabaseEpoch)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewUnstartedServer(nil)
	origin := "http://" + server.Listener.Addr().String()
	portal, err := NewPortal(service, origin, true)
	if err != nil {
		t.Fatal(err)
	}
	server.Config.Handler = portal.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Keep the actual DPoP boundary: browser cookies cannot authenticate it.
		var verifier *auth.Authenticator
		verifier.Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("password cookie entered DPoP handler") })).ServeHTTP(w, r)
	}))
	server.Start()
	t.Cleanup(server.Close)
	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar}
	call := func(path, body, csrf string, expected int) map[string]json.RawMessage {
		t.Helper()
		method := "GET"
		if body != "" {
			method = "POST"
		}
		req, _ := http.NewRequestWithContext(ctx, method, origin+path, strings.NewReader(body))
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Origin", origin)
			req.Header.Set("X-CSRF-Token", csrf)
		}
		response, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != expected {
			t.Fatalf("%s %s = %d, want %d", method, path, response.StatusCode, expected)
		}
		if path != "/v1/me" && response.Header.Get("Cache-Control") != "no-store" {
			t.Fatal("account response is cacheable")
		}
		var envelope struct {
			Data map[string]json.RawMessage `json:"data"`
		}
		_ = json.NewDecoder(response.Body).Decode(&envelope)
		return envelope.Data
	}
	password := "fixture-only-password-123"
	credentials := `{"username":"Example.User","password":"` + password + `"}`
	call("/account/api/session", "", "", 401)
	call("/account/api/register", credentials, "", 201)
	call("/account/api/register", credentials, "", 409)
	call("/account/api/login", `{"username":"example.user","password":"wrong-password"}`, "", 401)
	login := call("/account/api/login", credentials, "", 200)
	var csrf string
	_ = json.Unmarshal(login["csrf"], &csrf)
	data := call("/account/api/session", "", "", 200)
	var session Session
	if json.Unmarshal(data["session"], &session) != nil || session.Username != "example.user" {
		t.Fatal("session identity mismatch")
	}
	if string(data["hosts"]) != "[]" {
		t.Fatal("registration created host authority")
	}
	var identity store.AccountIdentity
	_ = json.Unmarshal(data["identity"], &identity)
	if identity.UserID != session.UserID || len(identity.Memberships) != 0 {
		t.Fatal("registration created membership authority")
	}
	var stored string
	if db.QueryRowContext(ctx, `select password_hash from local_accounts where user_id=$1`, session.UserID).Scan(&stored) != nil || strings.Contains(stored, password) {
		t.Fatal("password not protected")
	}
	response, err := client.Get(origin + "/account/")
	if err != nil {
		t.Fatal(err)
	}
	if response.Header.Get("Content-Security-Policy") == "" {
		t.Fatal("missing CSP")
	}
	response.Body.Close()
	for _, cookie := range jar.Cookies(response.Request.URL) {
		if cookie.Name == portal.cookieName(false) && len(cookie.Value) != 43 {
			t.Fatal("bad session secret")
		}
	}
	if portal.cookieName(false) != "tokenworks_mobile_account" || portal.cookieName(true) != "tokenworks_mobile_account_csrf" {
		t.Fatal("browser cookie namespace was not cut over")
	}
	for _, cookie := range jar.Cookies(response.Request.URL) {
		if cookie.Name != portal.cookieName(false) {
			continue
		}
		legacy, _ := http.NewRequestWithContext(ctx, "GET", origin+"/account/api/session", nil)
		legacy.AddCookie(&http.Cookie{Name: "remote_account", Value: cookie.Value})
		legacyResponse, err := http.DefaultClient.Do(legacy)
		if err != nil {
			t.Fatal(err)
		}
		legacyResponse.Body.Close()
		if legacyResponse.StatusCode != 401 {
			t.Fatal("retired browser cookie authenticated a current session")
		}
	}
	call("/v1/me", "", "", 503)
	call("/account/api/logout", "{}", "wrong-csrf", 403)
	req, _ := http.NewRequest("POST", origin+"/account/api/logout", strings.NewReader("{}"))
	req.Header.Set("Origin", "https://other.example")
	req.Header.Set("Content-Type", "application/json")
	refused, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if refused.StatusCode != 403 {
		t.Fatal("cross-origin mutation accepted")
	}
	refused.Body.Close()
	call("/account/api/session", "", "", 200)
	// A separate login must also be revoked when the password changes.
	second, secondCSRF, err := service.Login(ctx, "example.user", password)
	if err != nil {
		t.Fatal(err)
	}
	if service.CSRF(ctx, second, secondCSRF) != nil {
		t.Fatal("second session not usable")
	}
	call("/account/api/password", `{"current_password":"wrong-password","password":"replacement-password-123"}`, csrf, 401)
	call("/account/api/password", `{"current_password":"`+password+`","password":"replacement-password-123"}`, csrf, 200)
	call("/account/api/session", "", "", 401)
	if _, err = service.Current(ctx, second); !errors.Is(err, ErrCredentials) {
		t.Fatal("old second session survived password change")
	}
	call("/account/api/login", credentials, "", 401)
	login = call("/account/api/login", `{"username":"example.user","password":"replacement-password-123"}`, "", 200)
	_ = json.Unmarshal(login["csrf"], &csrf)
	call("/account/api/logout", "{}", csrf, 200)
	call("/account/api/session", "", "", 401)
	token, _, err := service.Login(ctx, "example.user", "replacement-password-123")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.ExecContext(ctx, `update account_sessions set expires_at=created_at+interval '1 microsecond' where user_id=$1`, session.UserID); err != nil {
		t.Fatal(err)
	}
	if _, err = service.Current(ctx, token); !errors.Is(err, ErrCredentials) {
		t.Fatal("expired session survived")
	}
	token, _, err = service.Login(ctx, "example.user", "replacement-password-123")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.ExecContext(ctx, `update users set status='suspended' where id=$1`, session.UserID); err != nil {
		t.Fatal(err)
	}
	if _, err = service.Current(ctx, token); !errors.Is(err, ErrCredentials) {
		t.Fatal("suspended account retained session")
	}
	if _, _, err = service.Login(ctx, "example.user", "replacement-password-123"); !errors.Is(err, ErrCredentials) {
		t.Fatal("suspended account logged in")
	}
	if _, err = db.ExecContext(ctx, `update users set status='active' where id=$1`, session.UserID); err != nil {
		t.Fatal(err)
	}
	if _, err = service.Current(ctx, token); !errors.Is(err, ErrCredentials) {
		t.Fatal("reactivation revived the old browser session")
	}
	token, _, err = service.Login(ctx, "example.user", "replacement-password-123")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.ExecContext(ctx, `update remote_recovery_state set epoch='11111111-1111-4111-8111-111111111111' where slot=1`); err != nil {
		t.Fatal(err)
	}
	if _, err = service.Current(ctx, token); !errors.Is(err, ErrCredentials) {
		t.Fatal("restored epoch revived old session")
	}
}

func TestPasswordAccountRateLimitsAndAuditRollback(t *testing.T) {
	ctx, db := testsupport.PostgresDatabase(t)
	s, err := New(&store.Postgres{DB: db}, store.OriginalDatabaseEpoch)
	if err != nil {
		t.Fatal(err)
	}
	for range 12 {
		if err = s.Attempt(ctx, "127.0.0.1", "limited"); err != nil {
			t.Fatal(err)
		}
	}
	other, err := New(&store.Postgres{DB: db}, store.OriginalDatabaseEpoch)
	if err != nil {
		t.Fatal(err)
	}
	if err = other.Attempt(ctx, "127.0.0.2", "LIMITED"); !errors.Is(err, ErrLimited) {
		t.Fatal("rate limit reset across service instances")
	}
	if _, err = db.ExecContext(ctx, `create function fixture_account_audit_failure() returns trigger language plpgsql as $$begin if new.action like 'account.%' then raise exception 'fixture';end if;return new;end$$;create trigger fixture_account_audit before insert on audit_log for each row execute function fixture_account_audit_failure()`); err != nil {
		t.Fatal(err)
	}
	if err = s.Register(ctx, "audit-failure", "fixture-password-1234"); !errors.Is(err, ErrUnavailable) {
		t.Fatal("audit failure allowed registration")
	}
	var count int
	if db.QueryRowContext(ctx, `select count(*) from local_accounts where username='audit-failure'`).Scan(&count) != nil || count != 0 {
		t.Fatal("failed registration persisted")
	}
}
func TestPasswordHashBoundsAndProviderReservation(t *testing.T) {
	first, err := passwordHash("a-long-valid-password")
	if err != nil {
		t.Fatal(err)
	}
	second, _ := passwordHash("a-long-valid-password")
	if first == second || !passwordMatches(first, "a-long-valid-password") || passwordMatches(first, "wrong") {
		t.Fatal("salted password verification failed")
	}
	for _, bad := range []string{"", strings.Replace(first, "600000", "999999999", 1), strings.Replace(first, "pbkdf2-sha256", "other", 1)} {
		if passwordMatches(bad, "a-long-valid-password") {
			t.Fatal("invalid hash accepted")
		}
	}
	for _, password := range []string{"short", strings.Repeat("x", 257), string([]byte{0xff})} {
		if validPassword(password) {
			t.Fatal("invalid password accepted")
		}
	}
	if !validPassword("十二个字符组成的合法测试密码") {
		t.Fatal("Unicode passphrase rejected")
	}
	for _, p := range Providers() {
		if p.ID == "sso" && p.Enabled {
			t.Fatal("SSO unexpectedly enabled")
		}
	}
	// Explicit insecure remote origins and URL credentials are never accepted.
	for _, origin := range []string{"http://remote.example", "https://remote.example/", "https://user:password@remote.example", "https://remote.example?x=1"} {
		if _, err := NewPortal(&Service{}, origin, false); !errors.Is(err, ErrInput) {
			t.Fatal("invalid portal origin accepted")
		}
	}
}
