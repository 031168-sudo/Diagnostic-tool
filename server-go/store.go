package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"sync"
)

var errNotFound = errors.New("диагностика не найдена")

var idPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type FileInfo struct {
	Name string `json:"name"`
	Path string `json:"path"`
	Size int64  `json:"size"`
}

type HistoryItem struct {
	Role string `json:"role"`
	Text string `json:"text"`
}

type SessionState struct {
	ID          string        `json:"id"`
	State       string        `json:"state"`
	Stage       string        `json:"stage"`
	Message     string        `json:"message"`
	Question    string        `json:"question"`
	Options     []string      `json:"options"`
	Conclusion  string         `json:"conclusion"`
	Document    *ConclusionDoc `json:"document,omitempty"`
	Car         string         `json:"car"`
	Complaint   string         `json:"complaint"`
	SessionName string         `json:"sessionName"`
	Files       []FileInfo     `json:"files"`
	History     []HistoryItem  `json:"history"`
	CreatedAt   string         `json:"createdAt"`
	UpdatedAt   string         `json:"updatedAt"`
}

type PublicState struct {
	ID         string         `json:"id"`
	State      string         `json:"state"`
	Stage      string         `json:"stage"`
	Message    string         `json:"message"`
	Question   string         `json:"question"`
	Options    []string       `json:"options"`
	Conclusion string         `json:"conclusion"`
	Document   *ConclusionDoc `json:"document,omitempty"`
	PdfURL     string         `json:"pdfUrl"`
}

func (s *SessionState) Public() PublicState {
	pdfURL := ""
	if s.Conclusion != "" {
		pdfURL = "/v1/diagnostics/" + s.ID + "/conclusion.txt"
	}
	options := s.Options
	if options == nil {
		options = []string{}
	}
	return PublicState{
		ID:         s.ID,
		State:      s.State,
		Stage:      s.Stage,
		Message:    s.Message,
		Question:   s.Question,
		Options:    options,
		Conclusion: s.Conclusion,
		Document:   s.Document,
		PdfURL:     pdfURL,
	}
}

type Store struct {
	root string
	mu   sync.Mutex
}

func NewStore(root string) *Store {
	return &Store{root: root}
}

func (s *Store) dir(id string) string {
	return filepath.Join(s.root, id)
}

func (s *Store) statePath(id string) string {
	return filepath.Join(s.dir(id), "state.json")
}

func (s *Store) Save(st *SessionState) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.saveLocked(st)
}

func (s *Store) saveLocked(st *SessionState) error {
	if err := os.MkdirAll(s.dir(st.ID), 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(s.statePath(st.ID), b, 0o644)
}

func (s *Store) Load(id string) (*SessionState, error) {
	if !idPattern.MatchString(id) {
		return nil, errNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := os.ReadFile(s.statePath(id))
	if err != nil {
		return nil, errNotFound
	}
	var st SessionState
	if err := json.Unmarshal(b, &st); err != nil {
		return nil, errNotFound
	}
	return &st, nil
}

func (s *Store) Update(id string, fn func(*SessionState)) (*SessionState, error) {
	if !idPattern.MatchString(id) {
		return nil, errNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := os.ReadFile(s.statePath(id))
	if err != nil {
		return nil, errNotFound
	}
	var st SessionState
	if err := json.Unmarshal(b, &st); err != nil {
		return nil, errNotFound
	}
	fn(&st)
	if err := s.saveLocked(&st); err != nil {
		return nil, err
	}
	return &st, nil
}
