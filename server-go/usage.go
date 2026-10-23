package main

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const anonymousAccount = "anonymous"

// Price is the cost in USD per 1M tokens (or per minute for STT).
type Price struct {
	InMiss    float64 `json:"inMiss"`
	InHit     float64 `json:"inHit"`
	Out       float64 `json:"out"`
	STTPerMin float64 `json:"sttPerMin"`
	Version   string  `json:"version"`
}

// LLMCostMicros converts token usage to micro-USD. Because prices are given in
// USD per 1M tokens, tokens*price is already micro-USD.
func (p Price) LLMCostMicros(u Usage) int64 {
	miss := u.PromptCacheMissTokens
	if miss == 0 {
		miss = u.PromptTokens - u.PromptCacheHitTokens
	}
	if miss < 0 {
		miss = 0
	}
	hit := u.PromptCacheHitTokens
	if hit < 0 {
		hit = 0
	}
	out := u.CompletionTokens
	if out < 0 {
		out = 0
	}
	return int64(math.Round(float64(miss)*p.InMiss + float64(hit)*p.InHit + float64(out)*p.Out))
}

func (p Price) STTCostMicros(seconds float64) int64 {
	if seconds <= 0 || p.STTPerMin <= 0 {
		return 0
	}
	return int64(math.Round(seconds / 60 * p.STTPerMin * 1e6))
}

// UsageRecord is one metered LLM/STT call, stored as a JSONL line.
type UsageRecord struct {
	TS           string  `json:"ts"`
	Unix         int64   `json:"unix"`
	AccountID    string  `json:"accountId"`
	SessionID    string  `json:"sessionId,omitempty"`
	Op           string  `json:"op"`
	Model        string  `json:"model"`
	InMiss       int     `json:"inMiss,omitempty"`
	InHit        int     `json:"inHit,omitempty"`
	Out          int     `json:"out,omitempty"`
	AudioSec     float64 `json:"audioSec,omitempty"`
	CostMicros   int64   `json:"costMicros"`
	PriceVersion string  `json:"priceVersion,omitempty"`
}

type UsageTotals struct {
	Calls      int     `json:"calls"`
	InMiss     int     `json:"inMiss"`
	InHit      int     `json:"inHit"`
	Out        int     `json:"out"`
	AudioSec   float64 `json:"audioSec,omitempty"`
	CostMicros int64   `json:"costMicros"`
}

func (t *UsageTotals) add(r UsageRecord) {
	t.Calls++
	t.InMiss += r.InMiss
	t.InHit += r.InHit
	t.Out += r.Out
	t.AudioSec += r.AudioSec
	t.CostMicros += r.CostMicros
}

type UsageReport struct {
	AccountID string                 `json:"accountId"`
	From      string                 `json:"from"`
	To        string                 `json:"to"`
	Totals    UsageTotals            `json:"totals"`
	ByDay     map[string]UsageTotals `json:"byDay"`
	ByOp      map[string]UsageTotals `json:"byOp"`
	BySession map[string]UsageTotals `json:"bySession"`
	Price     Price                  `json:"price"`
}

type AccountUsage struct {
	AccountID         string      `json:"accountId"`
	Totals            UsageTotals `json:"totals"`
	Registered        bool        `json:"registered"`
	CreatedAt         string      `json:"createdAt,omitempty"`
	Credits           int         `json:"credits"`
	SubscriptionUntil string      `json:"subscriptionUntil,omitempty"`
}

// Include registered clients even when they have no calls in the selected period.
func (s *Server) adminAccounts(from, to time.Time) []AccountUsage {
	rows := s.usage.Accounts(from, to)
	byID := make(map[string]int, len(rows))
	for i, row := range rows {
		byID[row.AccountID] = i
	}
	if s.accounts != nil {
		for _, client := range s.accounts.Clients() {
			i, ok := byID[client.AccountID]
			if !ok {
				i = len(rows)
				rows = append(rows, AccountUsage{AccountID: client.AccountID})
			}
			rows[i].Registered = true
			rows[i].CreatedAt = client.CreatedAt
			rows[i].Credits = client.Credits
			rows[i].SubscriptionUntil = client.SubscriptionUntil
		}
	}
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].Totals.CostMicros != rows[j].Totals.CostMicros {
			return rows[i].Totals.CostMicros > rows[j].Totals.CostMicros
		}
		return rows[i].AccountID < rows[j].AccountID
	})
	return rows
}

type UsageStore struct {
	path   string
	price  Price
	mu     sync.Mutex
	file   *os.File
	loaded int
	all    []UsageRecord
}

func NewUsageStore(root string, price Price) *UsageStore {
	s := &UsageStore{path: filepath.Join(root, "llm_usage.jsonl"), price: price}
	s.load()
	f, err := os.OpenFile(s.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		log.Printf("usage: не удалось открыть %s: %v (учёт будет только в памяти)", s.path, err)
	} else {
		s.file = f
	}
	return s
}

func (s *UsageStore) load() {
	f, err := os.Open(s.path)
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64<<10), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		var r UsageRecord
		if json.Unmarshal([]byte(line), &r) != nil {
			continue
		}
		if r.AccountID == "" {
			r.AccountID = anonymousAccount
		}
		if r.Unix == 0 && r.TS != "" {
			if t, err := time.Parse(time.RFC3339Nano, r.TS); err == nil {
				r.Unix = t.Unix()
			}
		}
		s.all = append(s.all, r)
	}
	s.loaded = len(s.all)
	if s.loaded > 0 {
		log.Printf("usage: загружено %d записей из %s", s.loaded, s.path)
	}
}

func (s *UsageStore) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file == nil {
		return nil
	}
	err := s.file.Close()
	s.file = nil
	return err
}

func (s *UsageStore) Price() Price {
	return s.price
}

// RecordLLM meters one chat completion call.
func (s *UsageStore) RecordLLM(accountID, sessionID, op, model string, u Usage) {
	miss := u.PromptCacheMissTokens
	if miss == 0 && u.PromptTokens > 0 {
		miss = u.PromptTokens - u.PromptCacheHitTokens
	}
	if miss < 0 {
		miss = 0
	}
	s.Record(UsageRecord{
		AccountID: accountID,
		SessionID: sessionID,
		Op:        op,
		Model:     model,
		InMiss:    miss,
		InHit:     u.PromptCacheHitTokens,
		Out:       u.CompletionTokens,
	})
}

// RecordSTT meters audio transcription, which is billed per minute rather than per token.
func (s *UsageStore) RecordSTT(accountID, sessionID, model string, seconds float64) {
	s.Record(UsageRecord{
		AccountID: accountID,
		SessionID: sessionID,
		Op:        "stt",
		Model:     model,
		AudioSec:  seconds,
	})
}

func (s *UsageStore) Record(r UsageRecord) {
	now := time.Now().UTC()
	if r.AccountID == "" {
		r.AccountID = anonymousAccount
	}
	if r.TS == "" {
		r.TS = now.Format("2006-01-02T15:04:05.000Z")
	}
	r.Unix = now.Unix()
	if r.Op == "" {
		r.Op = "llm"
	}
	if r.AudioSec > 0 {
		r.CostMicros = s.price.STTCostMicros(r.AudioSec)
	} else {
		r.CostMicros = s.price.LLMCostMicros(Usage{
			PromptTokens:          r.InMiss + r.InHit,
			PromptCacheHitTokens:  r.InHit,
			PromptCacheMissTokens: r.InMiss,
			CompletionTokens:      r.Out,
		})
	}
	r.PriceVersion = s.price.Version

	line, err := json.Marshal(r)
	if err != nil {
		log.Printf("usage: не удалось сериализовать запись: %v", err)
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.all = append(s.all, r)
	if s.file != nil {
		if _, err := s.file.Write(append(line, '\n')); err != nil {
			log.Printf("usage: не удалось записать %s: %v", s.path, err)
		}
	}
	log.Printf("usage: account=%s session=%s op=%s model=%s in=%d/%d out=%d cost=%.6f$",
		r.AccountID, r.SessionID, r.Op, r.Model, r.InMiss, r.InHit, r.Out, float64(r.CostMicros)/1e6)
}

func (s *UsageStore) Totals(accountID string) UsageTotals {
	s.mu.Lock()
	defer s.mu.Unlock()
	var t UsageTotals
	for _, r := range s.all {
		if r.AccountID == accountID {
			t.add(r)
		}
	}
	return t
}

func (s *UsageStore) Report(accountID string, from, to time.Time) UsageReport {
	s.mu.Lock()
	defer s.mu.Unlock()
	rep := UsageReport{
		AccountID: accountID,
		From:      from.Format("2006-01-02"),
		To:        to.Add(-time.Second).Format("2006-01-02"),
		ByDay:     map[string]UsageTotals{},
		ByOp:      map[string]UsageTotals{},
		BySession: map[string]UsageTotals{},
		Price:     s.price,
	}
	for _, r := range s.all {
		if r.AccountID != accountID || !inRange(r, from, to) {
			continue
		}
		rep.Totals.add(r)
		day := time.Unix(r.Unix, 0).UTC().Format("2006-01-02")
		addTo(rep.ByDay, day, r)
		addTo(rep.ByOp, r.Op, r)
		if r.SessionID != "" {
			addTo(rep.BySession, r.SessionID, r)
		}
	}
	return rep
}

func (s *UsageStore) Accounts(from, to time.Time) []AccountUsage {
	s.mu.Lock()
	defer s.mu.Unlock()
	m := map[string]*UsageTotals{}
	for _, r := range s.all {
		if !inRange(r, from, to) {
			continue
		}
		t := m[r.AccountID]
		if t == nil {
			t = &UsageTotals{}
			m[r.AccountID] = t
		}
		t.add(r)
	}
	out := make([]AccountUsage, 0, len(m))
	for id, t := range m {
		out = append(out, AccountUsage{AccountID: id, Totals: *t})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Totals.CostMicros != out[j].Totals.CostMicros {
			return out[i].Totals.CostMicros > out[j].Totals.CostMicros
		}
		return out[i].AccountID < out[j].AccountID
	})
	return out
}

func inRange(r UsageRecord, from, to time.Time) bool {
	ts := time.Unix(r.Unix, 0)
	return !ts.Before(from) && ts.Before(to)
}

func addTo(m map[string]UsageTotals, key string, r UsageRecord) {
	t := m[key]
	t.add(r)
	m[key] = t
}

// chat is the single entry point for LLM calls: it meters every successful call.
func (s *Server) chat(ctx context.Context, accountID, sessionID, op string, messages []ChatMessage, jsonMode bool) (string, error) {
	answer, u, err := s.ds.Chat(ctx, messages, jsonMode)
	if err != nil {
		return "", err
	}
	if s.usage != nil {
		s.usage.RecordLLM(accountID, sessionID, op, s.ds.model, u)
	}
	return answer, nil
}

func usageRange(r *http.Request) (time.Time, time.Time, error) {
	now := time.Now().UTC()
	from := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC).AddDate(0, 0, -29)
	to := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC).AddDate(0, 0, 1)
	if v := strings.TrimSpace(r.URL.Query().Get("from")); v != "" {
		t, err := time.Parse("2006-01-02", v)
		if err != nil {
			return from, to, fmt.Errorf("from: ожидается дата YYYY-MM-DD")
		}
		from = t
	}
	if v := strings.TrimSpace(r.URL.Query().Get("to")); v != "" {
		t, err := time.Parse("2006-01-02", v)
		if err != nil {
			return from, to, fmt.Errorf("to: ожидается дата YYYY-MM-DD")
		}
		to = t.AddDate(0, 0, 1)
	}
	if !from.Before(to) {
		return from, to, fmt.Errorf("from должен быть раньше to")
	}
	return from, to, nil
}

func (s *Server) handleAccountUsage(w http.ResponseWriter, r *http.Request) {
	a, ok := s.accountFromRequest(r)
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "Неизвестный аккаунт"})
		return
	}
	from, to, err := usageRange(r)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, s.usage.Report(a.ID, from, to))
}

func (s *Server) handleAdminUsage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if !adminAllowed(r, s.cfg.AdminToken) && !s.adminSessionAllowed(r) {
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "Нужен заголовок X-Admin-Token"})
		return
	}
	from, to, err := usageRange(r)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	if accountID := strings.TrimSpace(r.URL.Query().Get("accountId")); accountID != "" {
		writeJSON(w, http.StatusOK, s.usage.Report(accountID, from, to))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"from":     from.Format("2006-01-02"),
		"to":       to.Add(-time.Second).Format("2006-01-02"),
		"price":    s.usage.Price(),
		"accounts": s.adminAccounts(from, to),
	})
}

func adminAllowed(r *http.Request, want string) bool {
	want = strings.TrimSpace(want)
	if want == "" {
		return false
	}
	got := strings.TrimSpace(r.Header.Get("X-Admin-Token"))
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}
