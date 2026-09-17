package main

import (
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"golang.org/x/crypto/acme/autocert"
)

func (s *Server) startTLS(mux http.Handler) {
	domains := s.cfg.TLSDomains
	if len(domains) == 0 {
		return
	}
	if err := os.MkdirAll(s.cfg.CertDir, 0o700); err != nil {
		log.Printf("TLS: не удалось создать каталог сертификатов: %v", err)
	}

	manager := &autocert.Manager{
		Prompt:     autocert.AcceptTOS,
		Cache:      autocert.DirCache(s.cfg.CertDir),
		HostPolicy: autocert.HostWhitelist(domains...),
		Email:      s.cfg.ACMEEmail,
	}

	tlsServer := &http.Server{
		Addr:              ":" + s.cfg.HTTPSPort,
		Handler:           withLogging(mux),
		TLSConfig:         manager.TLSConfig(),
		ReadHeaderTimeout: 15 * time.Second,
	}
	go func() {
		log.Printf("HTTPS (Let's Encrypt) слушает :%s для %s", s.cfg.HTTPSPort, strings.Join(domains, ", "))
		if err := tlsServer.ListenAndServeTLS("", ""); err != nil {
			log.Printf("HTTPS остановлен: %v", err)
		}
	}()

	httpServer := &http.Server{
		Addr:              ":" + s.cfg.HTTPPort,
		Handler:           manager.HTTPHandler(redirectToHTTPS(s.cfg.HTTPSPort)),
		ReadHeaderTimeout: 15 * time.Second,
	}
	go func() {
		log.Printf("HTTP слушает :%s (ACME-челлендж и редирект на HTTPS)", s.cfg.HTTPPort)
		if err := httpServer.ListenAndServe(); err != nil {
			log.Printf("HTTP остановлен: %v", err)
		}
	}()
}

func redirectToHTTPS(httpsPort string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host := r.Host
		if h, _, err := net.SplitHostPort(host); err == nil {
			host = h
		}
		if host == "" {
			host = r.URL.Host
		}
		target := "https://" + host
		if httpsPort != "" && httpsPort != "443" {
			target += ":" + httpsPort
		}
		target += r.URL.RequestURI()
		http.Redirect(w, r, target, http.StatusMovedPermanently)
	})
}
