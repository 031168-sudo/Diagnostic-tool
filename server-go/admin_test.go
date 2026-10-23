package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAdminRoutes(t *testing.T) {
	s := &Server{cfg: Config{AdminToken: "admin-secret", AppToken: "apk-secret"}, limiter: newRateLimiter(0)}
	s.usage = NewUsageStore(t.TempDir(), Price{InMiss: 1})
	defer s.usage.Close()
	s.usage.RecordLLM("client-a", "session-a", "analysis", "test", Usage{PromptTokens: 100})
	mux := http.NewServeMux()
	s.registerAdmin(mux)
	for _, path := range []string{"/admin", "/admin/admin.js", "/admin/admin.css"} {
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code != 200 || w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("Content-Security-Policy") == "" {
			t.Fatalf("%s: status=%d headers=%v", path, w.Code, w.Header())
		}
		if strings.Contains(w.Body.String(), "admin-secret") {
			t.Fatal("admin token exposed in static asset")
		}
	}
	for _, token := range []string{"", "apk-secret", "wrong", "admin-secret"} {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("GET", "/v1/admin/usage", nil)
		r.Header.Set("X-Admin-Token", token)
		mux.ServeHTTP(w, r)
		want := 403
		if token == "admin-secret" {
			want = 200
		}
		if w.Code != want {
			t.Fatalf("token %q: got %d want %d", token, w.Code, want)
		}
		if want == 200 {
			var report struct {
				Accounts []AccountUsage `json:"accounts"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &report); err != nil || len(report.Accounts) != 1 || report.Accounts[0].AccountID != "client-a" {
				t.Fatalf("bad report: %s (%v)", w.Body.String(), err)
			}
		}
	}
	// No token configured must never grant access, including an empty header.
	s.cfg.AdminToken = ""
	for _, path := range []string{"/admin", "/v1/admin/usage"} {
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code == 200 {
			t.Fatalf("disabled admin accessible at %s", path)
		}
	}
}

func TestAdminSession(t *testing.T) {
	s := &Server{cfg: Config{AdminToken: "secret"}}
	r := httptest.NewRequest("POST", "https://example.com/v1/admin/login", nil)
	r.Header.Set("X-Admin-Token", "secret")
	w := httptest.NewRecorder()
	s.handleAdminLogin(w, r)
	cookies := w.Result().Cookies()
	if w.Code != 200 || len(cookies) != 1 {
		t.Fatal("login failed")
	}
	c := cookies[0]
	if !c.HttpOnly || !c.Secure || c.SameSite != http.SameSiteStrictMode || c.MaxAge != 30*24*60*60 {
		t.Fatalf("unsafe cookie: %+v", c)
	}
	r = httptest.NewRequest("GET", "https://example.com/v1/admin/usage", nil)
	r.AddCookie(c)
	if !s.adminSessionAllowed(r) {
		t.Fatal("session rejected")
	}
	s.cfg.AdminToken = "rotated"
	if s.adminSessionAllowed(r) {
		t.Fatal("rotation must invalidate session")
	}
	s.cfg.AdminToken = "secret"
	for _, value := range []string{c.Value + "tampered", "invalid", "1." + s.adminSignature("1")} {
		r = httptest.NewRequest("GET", "/", nil)
		r.AddCookie(&http.Cookie{Name: adminCookie, Value: value})
		if s.adminSessionAllowed(r) {
			t.Fatal("invalid cookie accepted")
		}
	}
	if !c.Expires.After(time.Now().Add(29 * 24 * time.Hour)) {
		t.Fatal("incorrect expiry")
	}
	w = httptest.NewRecorder()
	s.handleAdminLogout(w, httptest.NewRequest("POST", "/", nil))
	if w.Result().Cookies()[0].MaxAge != -1 {
		t.Fatal("logout must delete cookie")
	}
}

func TestAdminListsClientsWithoutUsage(t *testing.T) {
	dir := t.TempDir()
	s := &Server{cfg: Config{AdminToken: "secret"}, accounts: NewAccountStore(dir), usage: NewUsageStore(dir, Price{})}
	defer s.usage.Close()
	a, err := s.accounts.Register()
	if err != nil {
		t.Fatal(err)
	}
	if err := s.accounts.AddCredits(a.ID, 7); err != nil {
		t.Fatal(err)
	}
	b, err := s.accounts.Register()
	if err != nil {
		t.Fatal(err)
	}
	s.usage.RecordLLM(b.ID, "s", "chat", "test", Usage{PromptTokens: 10})
	w := httptest.NewRecorder()
	r := httptest.NewRequest("GET", "/v1/admin/usage?from=2000-01-01&to=2000-01-02", nil)
	r.Header.Set("X-Admin-Token", "secret")
	s.handleAdminUsage(w, r)
	var report struct {
		Accounts []AccountUsage `json:"accounts"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if w.Code != 200 || len(report.Accounts) != 2 {
		t.Fatalf("unexpected report: %s", w.Body.String())
	}
	for _, client := range report.Accounts {
		if !client.Registered || client.CreatedAt == "" || client.Totals.Calls != 0 {
			t.Fatalf("invalid client: %+v", client)
		}
		if client.AccountID == a.ID && client.Credits != 7 {
			t.Fatalf("credits: %d", client.Credits)
		}
	}
	for _, token := range []string{a.Token, b.Token, `"token":`} {
		if strings.Contains(w.Body.String(), token) {
			t.Fatal("authentication token leaked in client list")
		}
	}
}
