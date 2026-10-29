package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
	"time"
)

//go:embed admin/index.html admin/admin.js admin/admin.css
var adminFiles embed.FS

func (s *Server) registerAdmin(mux *http.ServeMux) {
	mux.Handle("POST /v1/admin/login", s.withRateLimit(http.HandlerFunc(s.handleAdminLogin)))
	mux.HandleFunc("POST /v1/admin/logout", s.handleAdminLogout)
	// Admin authentication is independent of APP_TOKEN embedded in the APK.
	mux.Handle("GET /v1/admin/usage", s.withRateLimit(http.HandlerFunc(s.handleAdminUsage)))
	for route, file := range map[string]string{
		"GET /admin":           "admin/index.html",
		"GET /admin/admin.js":  "admin/admin.js",
		"GET /admin/admin.css": "admin/admin.css",
	} {
		mux.HandleFunc(route, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("X-Content-Type-Options", "nosniff")
			w.Header().Set("Referrer-Policy", "no-referrer")
			w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
			if s.cfg.AdminToken == "" {
				http.Error(w, "Админка отключена: настройте ADMIN_TOKEN на сервере", http.StatusServiceUnavailable)
				return
			}
			http.ServeFileFS(w, r, adminFiles, file)
		})
	}
}

const adminCookie = "__Host-diag-admin"

func (s *Server) adminSignature(value string) string {
	mac := hmac.New(sha256.New, []byte(s.cfg.AdminToken))
	mac.Write([]byte("diag-admin-session:" + value))
	return hex.EncodeToString(mac.Sum(nil))
}

func (s *Server) adminSessionAllowed(r *http.Request) bool {
	if s.cfg.AdminToken == "" {
		return false
	}
	cookie, err := r.Cookie(adminCookie)
	if err != nil {
		return false
	}
	parts := strings.Split(cookie.Value, ".")
	if len(parts) != 2 {
		return false
	}
	expires, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || expires <= time.Now().Unix() {
		return false
	}
	return hmac.Equal([]byte(parts[1]), []byte(s.adminSignature(parts[0])))
}

func (s *Server) handleAdminLogin(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if !adminAllowed(r, s.cfg.AdminToken) {
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "Неверный ADMIN_TOKEN или доступ отключён"})
		return
	}
	expires := time.Now().Add(30 * 24 * time.Hour)
	value := strconv.FormatInt(expires.Unix(), 10)
	http.SetCookie(w, &http.Cookie{Name: adminCookie, Value: value + "." + s.adminSignature(value), Path: "/", Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 30 * 24 * 60 * 60, Expires: expires})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) handleAdminLogout(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	http.SetCookie(w, &http.Cookie{Name: adminCookie, Value: "", Path: "/", Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: -1})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
