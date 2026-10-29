package main

import (
	"crypto/subtle"
	"log"
	"math"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type bucket struct {
	tokens float64
	last   time.Time
}

type rateLimiter struct {
	mu      sync.Mutex
	buckets map[string]*bucket
	rate    float64
	burst   float64
}

func newRateLimiter(perMinute int) *rateLimiter {
	if perMinute <= 0 {
		return &rateLimiter{buckets: map[string]*bucket{}}
	}
	return &rateLimiter{
		buckets: map[string]*bucket{},
		rate:    float64(perMinute) / 60.0,
		burst:   float64(perMinute),
	}
}

func (l *rateLimiter) enabled() bool {
	return l != nil && l.rate > 0
}

func (l *rateLimiter) allow(key string) bool {
	if !l.enabled() {
		return true
	}
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	}
	elapsed := now.Sub(b.last).Seconds()
	b.tokens = math.Min(l.burst, b.tokens+elapsed*l.rate)
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func (l *rateLimiter) cleanup(maxAge time.Duration) {
	if l == nil {
		return
	}
	cutoff := time.Now().Add(-maxAge)
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, b := range l.buckets {
		if b.last.Before(cutoff) {
			delete(l.buckets, k)
		}
	}
}

func clientIP(r *http.Request) string {
	if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
		if i := strings.IndexByte(fwd, ','); i > 0 {
			return strings.TrimSpace(fwd[:i])
		}
		return strings.TrimSpace(fwd)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (s *Server) withRateLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !s.limiter.allow(clientIP(r)) {
			writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "Слишком много запросов. Повторите позже."})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) withAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := strings.TrimSpace(s.cfg.AppToken)
		if token != "" {
			provided := strings.TrimSpace(r.Header.Get("X-Api-Key"))
			if provided == "" {
				if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
					provided = strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
				}
			}
			if subtle.ConstantTimeCompare([]byte(provided), []byte(token)) != 1 {
				log.Printf("auth: отклонён запрос %s %s от %s", r.Method, r.URL.Path, clientIP(r))
				writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "Неверный или отсутствующий токен приложения"})
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) protect(h http.Handler) http.Handler {
	return s.withRateLimit(s.withAuth(h))
}

func (s *Server) startLimiterCleanup() {
	if !s.limiter.enabled() {
		return
	}
	go func() {
		ticker := time.NewTicker(10 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			s.limiter.cleanup(15 * time.Minute)
		}
	}()
}
