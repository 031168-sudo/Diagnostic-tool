package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLLMCostMicros(t *testing.T) {
	p := Price{InMiss: 0.28, InHit: 0.028, Out: 0.42}
	u := Usage{
		PromptTokens:          1_000_000,
		PromptCacheHitTokens:  100_000,
		PromptCacheMissTokens: 900_000,
		CompletionTokens:      1_000_000,
	}
	// 900k*0.28 + 100k*0.028 + 1M*0.42 = 674800 micro-USD
	if got := p.LLMCostMicros(u); got != 674_800 {
		t.Fatalf("cost = %d, want 674800", got)
	}

	// Если API не прислал cache_miss, он вычисляется как prompt - hit.
	u = Usage{PromptTokens: 1000, PromptCacheHitTokens: 200, CompletionTokens: 0}
	if got := p.LLMCostMicros(u); got != 230 {
		t.Fatalf("fallback cost = %d", got)
	}
}

func TestSTTCostMicros(t *testing.T) {
	p := Price{STTPerMin: 0.006}
	if got := p.STTCostMicros(60); got != 6000 {
		t.Fatalf("stt cost = %d, want 6000", got)
	}
	if got := p.STTCostMicros(0); got != 0 {
		t.Fatalf("stt zero cost = %d", got)
	}
}

func TestUsageStoreRecordReportAndReload(t *testing.T) {
	dir := t.TempDir()
	price := Price{InMiss: 0.28, InHit: 0.028, Out: 0.42, Version: "test"}
	s := NewUsageStore(dir, price)

	s.RecordLLM("acc-a", "sess-1", "analysis", "deepseek-chat", Usage{
		PromptCacheMissTokens: 1000, PromptCacheHitTokens: 500, CompletionTokens: 100,
	})
	s.RecordLLM("acc-a", "sess-1", "chat", "deepseek-chat", Usage{
		PromptCacheMissTokens: 200, CompletionTokens: 50,
	})
	s.RecordSTT("acc-a", "sess-1", "whisper", 60)
	s.RecordLLM("acc-b", "sess-2", "analysis", "deepseek-chat", Usage{
		PromptCacheMissTokens: 4000, CompletionTokens: 400,
	})

	a := s.Totals("acc-a")
	if a.Calls != 3 || a.InMiss != 1200 || a.InHit != 500 || a.Out != 150 || a.AudioSec != 60 {
		t.Fatalf("acc-a totals = %+v", a)
	}
	if b := s.Totals("acc-b"); b.Calls != 1 || b.InMiss != 4000 {
		t.Fatalf("acc-b totals = %+v", b)
	}
	wantA := price.LLMCostMicros(Usage{PromptCacheMissTokens: 1000, PromptCacheHitTokens: 500, CompletionTokens: 100}) +
		price.LLMCostMicros(Usage{PromptCacheMissTokens: 200, CompletionTokens: 50}) +
		price.STTCostMicros(60)
	if a.CostMicros != wantA {
		t.Fatalf("acc-a cost = %d, want %d", a.CostMicros, wantA)
	}

	now := time.Now().UTC()
	rep := s.Report("acc-a", now.Add(-time.Hour), now.Add(time.Hour))
	if rep.Totals.Calls != 3 || rep.ByOp["analysis"].Calls != 1 || rep.ByOp["chat"].Calls != 1 || rep.ByOp["stt"].Calls != 1 {
		t.Fatalf("report = %+v", rep)
	}
	if rep.BySession["sess-1"].Calls != 3 {
		t.Fatalf("bySession = %+v", rep.BySession)
	}
	if rep.ByDay[now.Format("2006-01-02")].Calls != 3 {
		t.Fatalf("byDay = %+v", rep.ByDay)
	}

	accounts := s.Accounts(now.Add(-time.Hour), now.Add(time.Hour))
	if len(accounts) != 2 || accounts[0].AccountID != "acc-b" {
		t.Fatalf("accounts = %+v (expected acc-b first by cost)", accounts)
	}

	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	reloaded := NewUsageStore(dir, price)
	defer reloaded.Close()
	if got := reloaded.Totals("acc-a"); got != a {
		t.Fatalf("reloaded totals = %+v, want %+v", got, a)
	}
}

func TestRecordLLMDerivesMissTokens(t *testing.T) {
	s := NewUsageStore(t.TempDir(), Price{InMiss: 0.28})
	defer s.Close()
	s.RecordLLM("a", "s", "analysis", "m", Usage{
		PromptTokens: 1000, PromptCacheHitTokens: 200, CompletionTokens: 5,
	})
	got := s.Totals("a")
	if got.InMiss != 800 || got.InHit != 200 || got.CostMicros != 800*0.28*1 {
		t.Fatalf("totals = %+v", got)
	}
}

func TestUsageRange(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/v1/account/usage?from=2026-01-01&to=2026-01-31", nil)
	from, to, err := usageRange(r)
	if err != nil {
		t.Fatal(err)
	}
	if from.Format("2006-01-02") != "2026-01-01" || to.Format("2006-01-02") != "2026-02-01" {
		t.Fatalf("range = %s..%s", from, to)
	}

	bad := httptest.NewRequest(http.MethodGet, "/v1/account/usage?from=01.01.2026", nil)
	if _, _, err := usageRange(bad); err == nil {
		t.Fatal("expected error for bad date")
	}
}

func TestUsageStoreAnonymousAccount(t *testing.T) {
	s := NewUsageStore(t.TempDir(), Price{})
	defer s.Close()
	s.RecordLLM("", "sess", "analysis", "m", Usage{PromptCacheMissTokens: 10})
	if got := s.Totals(anonymousAccount); got.Calls != 1 {
		t.Fatalf("anonymous totals = %+v", got)
	}
}
