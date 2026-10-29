package main

import "testing"

func TestAppendTranscript(t *testing.T) {
	st := &SessionState{}

	appendTranscript(st, "user", "  привет  ")
	appendTranscript(st, "assistant", "здравствуйте")
	appendTranscript(st, "assistant", "здравствуйте")
	appendTranscript(st, "user", "   ")

	if len(st.Transcript) != 2 {
		t.Fatalf("expected 2 entries, got %d", len(st.Transcript))
	}
	if st.Transcript[0].Role != "user" || st.Transcript[0].Text != "привет" {
		t.Fatalf("unexpected first entry: %+v", st.Transcript[0])
	}
	if st.Transcript[1].Role != "assistant" || st.Transcript[1].Text != "здравствуйте" {
		t.Fatalf("unexpected second entry: %+v", st.Transcript[1])
	}
}

func TestPublicIncludesTranscript(t *testing.T) {
	st := &SessionState{ID: "x", Transcript: []HistoryItem{{Role: "user", Text: "a"}}}
	p := st.Public()
	if len(p.Transcript) != 1 || p.Transcript[0].Text != "a" {
		t.Fatalf("transcript not exposed: %+v", p.Transcript)
	}

	empty := (&SessionState{ID: "y"}).Public()
	if empty.Transcript == nil || len(empty.Transcript) != 0 {
		t.Fatalf("empty transcript must be an empty slice, got %#v", empty.Transcript)
	}
}
