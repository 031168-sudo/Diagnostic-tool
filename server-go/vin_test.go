package main

import "testing"

func TestNormalizeVINCyrillic(t *testing.T) {
	got, ok := normalizeVIN("SJNJ\u0412NJ10U7045303") // \u0412 = кириллическая В
	if !ok {
		t.Fatalf("expected valid, got invalid: %q", got)
	}
	if got != "SJNJBNJ10U7045303" {
		t.Fatalf("expected SJNJBNJ10U7045303, got %q", got)
	}
}

func TestNormalizeVINLatin(t *testing.T) {
	got, ok := normalizeVIN(" sjnjbnj10u7045303 ")
	if !ok || got != "SJNJBNJ10U7045303" {
		t.Fatalf("got %q ok=%v", got, ok)
	}
}

func TestNormalizeVINInvalid(t *testing.T) {
	if _, ok := normalizeVIN("ABC"); ok {
		t.Fatalf("short VIN must be invalid")
	}
}
