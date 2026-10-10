package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

var errAccountNotFound = errors.New("аккаунт не найден")
var errBadTransferCode = errors.New("неверный или истёкший код переноса")

const transferCodeTTL = 20 * time.Minute

const transferAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

type Account struct {
	ID                string `json:"id"`
	Token             string `json:"token"`
	Credits           int    `json:"credits"`
	SubscriptionUntil string `json:"subscriptionUntil,omitempty"`
	CreatedAt         string `json:"createdAt"`
}

type accountsFile struct {
	Accounts []*Account `json:"accounts"`
}

type transferCode struct {
	AccountID string
	ExpiresAt time.Time
}

type AccountStore struct {
	path      string
	mu        sync.Mutex
	byID      map[string]*Account
	byToken   map[string]*Account
	transfers map[string]*transferCode
}

func NewAccountStore(root string) *AccountStore {
	s := &AccountStore{
		path:      filepath.Join(root, "accounts.json"),
		byID:      map[string]*Account{},
		byToken:   map[string]*Account{},
		transfers: map[string]*transferCode{},
	}
	s.load()
	return s
}

func (s *AccountStore) load() {
	b, err := os.ReadFile(s.path)
	if err != nil {
		return
	}
	var f accountsFile
	if json.Unmarshal(b, &f) != nil {
		return
	}
	for _, a := range f.Accounts {
		if a == nil || a.ID == "" || a.Token == "" {
			continue
		}
		s.byID[a.ID] = a
		s.byToken[a.Token] = a
	}
}

func (s *AccountStore) saveLocked() error {
	f := accountsFile{Accounts: make([]*Account, 0, len(s.byID))}
	for _, a := range s.byID {
		f.Accounts = append(f.Accounts, a)
	}
	b, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *AccountStore) Register() (*Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a := &Account{ID: newID(), Token: newSecret(32), CreatedAt: now()}
	s.byID[a.ID] = a
	s.byToken[a.Token] = a
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return a, nil
}

func (s *AccountStore) ByToken(token string) (*Account, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return nil, errAccountNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if a, ok := s.byToken[token]; ok {
		return a, nil
	}
	return nil, errAccountNotFound
}

func (s *AccountStore) CreateTransfer(accountID string) (string, time.Time, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.byID[accountID]; !ok {
		return "", time.Time{}, errAccountNotFound
	}
	exp := time.Now().Add(transferCodeTTL)
	code := s.uniqueCodeLocked()
	s.transfers[code] = &transferCode{AccountID: accountID, ExpiresAt: exp}
	return code, exp, nil
}

func (s *AccountStore) uniqueCodeLocked() string {
	for {
		code := newTransferCode()
		if _, exists := s.transfers[code]; !exists {
			return code
		}
	}
}

func (s *AccountStore) RedeemTransfer(code string) (*Account, error) {
	code = strings.ToUpper(strings.TrimSpace(code))
	s.mu.Lock()
	defer s.mu.Unlock()
	tc, ok := s.transfers[code]
	if !ok || time.Now().After(tc.ExpiresAt) {
		delete(s.transfers, code)
		return nil, errBadTransferCode
	}
	delete(s.transfers, code)
	a, ok := s.byID[tc.AccountID]
	if !ok {
		return nil, errAccountNotFound
	}
	return a, nil
}

func (s *AccountStore) AddCredits(accountID string, n int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.byID[accountID]
	if !ok {
		return errAccountNotFound
	}
	a.Credits += n
	return s.saveLocked()
}

func newSecret(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func newTransferCode() string {
	var sb strings.Builder
	max := big.NewInt(int64(len(transferAlphabet)))
	for i := 0; i < 8; i++ {
		n, err := rand.Int(rand.Reader, max)
		if err != nil {
			panic(err)
		}
		sb.WriteByte(transferAlphabet[n.Int64()])
	}
	return sb.String()
}

type publicAccount struct {
	AccountID         string `json:"accountId"`
	Token             string `json:"token,omitempty"`
	Credits           int    `json:"credits"`
	SubscriptionUntil string `json:"subscriptionUntil,omitempty"`
}

func publicAccountOf(a *Account, withToken bool) publicAccount {
	pa := publicAccount{AccountID: a.ID, Credits: a.Credits, SubscriptionUntil: a.SubscriptionUntil}
	if withToken {
		pa.Token = a.Token
	}
	return pa
}

func (s *Server) accountFromRequest(r *http.Request) (*Account, bool) {
	token := r.Header.Get("X-Account-Token")
	if token == "" {
		token = r.URL.Query().Get("accountToken")
	}
	a, err := s.accounts.ByToken(token)
	if err != nil {
		return nil, false
	}
	return a, true
}

func (s *Server) handleAccountRegister(w http.ResponseWriter, _ *http.Request) {
	a, err := s.accounts.Register()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, publicAccountOf(a, true))
}

func (s *Server) handleAccountMe(w http.ResponseWriter, r *http.Request) {
	a, ok := s.accountFromRequest(r)
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "Неизвестный аккаунт"})
		return
	}
	writeJSON(w, http.StatusOK, publicAccountOf(a, false))
}

func (s *Server) handleAccountTransferCreate(w http.ResponseWriter, r *http.Request) {
	a, ok := s.accountFromRequest(r)
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "Неизвестный аккаунт"})
		return
	}
	code, exp, err := s.accounts.CreateTransfer(a.ID)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": code, "expiresAt": exp.UTC().Format(time.RFC3339)})
}

func (s *Server) handleAccountTransferRedeem(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Code string `json:"code"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&body)
	a, err := s.accounts.RedeemTransfer(body.Code)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, publicAccountOf(a, true))
}
