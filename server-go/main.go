package main

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const maxUploadBytes = 256 << 20

type Config struct {
	Port              string
	DataDir           string
	APIKey            string
	BaseURL           string
	Model             string
	TranscribeBaseURL string
	TranscribeAPIKey  string
	TranscribeModel   string
	TranscribeLang    string
	AppToken          string
	TLSDomains        []string
	ACMEEmail         string
	HTTPSPort         string
	HTTPPort          string
	CertDir           string
	RateLimitPerMin   int
	MaxUploadMB       int
	MaxUploadFiles    int
}

type Server struct {
	cfg         Config
	store       *Store
	ds          *DeepSeek
	transcriber *Transcriber
	httpClient  *http.Client
	vinCache    *vinCache
	limiter     *rateLimiter
	locks       sync.Map
}

func (s *Server) sessionLock(id string) *sync.Mutex {
	v, _ := s.locks.LoadOrStore(id, &sync.Mutex{})
	return v.(*sync.Mutex)
}

func main() {
	loadDotEnv(".env")
	if logFile := openLogFile(envDefault("LOG_FILE", "server.log")); logFile != nil {
		defer logFile.Close()
		log.SetOutput(io.MultiWriter(os.Stdout, logFile))
	} else {
		log.SetOutput(os.Stdout)
	}
	cfg := loadConfig()

	if err := os.MkdirAll(filepath.Join(cfg.DataDir, "uploads"), 0o755); err != nil {
		log.Fatalf("не удалось создать DATA_DIR: %v", err)
	}

	srv := &Server{
		cfg:        cfg,
		store:      NewStore(cfg.DataDir),
		ds:         NewDeepSeek(cfg.BaseURL, cfg.APIKey, cfg.Model),
		httpClient: &http.Client{Timeout: 25 * time.Second},
		vinCache:   newVINCache(),
		limiter:    newRateLimiter(cfg.RateLimitPerMin),
	}
	if cfg.TranscribeBaseURL != "" && cfg.TranscribeModel != "" {
		srv.transcriber = &Transcriber{
			http:    &http.Client{Timeout: 15 * time.Minute},
			baseURL: cfg.TranscribeBaseURL,
			apiKey:  cfg.TranscribeAPIKey,
			model:   cfg.TranscribeModel,
			lang:    cfg.TranscribeLang,
		}
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", srv.handleHealth)
	mux.Handle("POST /v1/diagnostics", srv.protect(http.HandlerFunc(srv.handleUpload)))
	mux.Handle("GET /v1/diagnostics/{id}", srv.protect(http.HandlerFunc(srv.handleStatus)))
	mux.Handle("POST /v1/diagnostics/{id}/messages", srv.protect(http.HandlerFunc(srv.handleMessages)))
	mux.Handle("POST /v1/diagnostics/{id}/chat", srv.protect(http.HandlerFunc(srv.handleChat)))
	mux.Handle("GET /v1/diagnostics/{id}/conclusion.txt", srv.protect(http.HandlerFunc(srv.handleConclusion)))
	mux.Handle("GET /v1/vin/{vin}", srv.protect(http.HandlerFunc(srv.handleVIN)))
	srv.startLimiterCleanup()

	if !srv.ds.Enabled() {
		log.Printf("ВНИМАНИЕ: DEEPSEEK_API_KEY не задан — запросы анализа будут возвращать 503")
	}

	handler := withLogging(mux)
	srv.startTLS(handler)

	listenAddr := ":" + cfg.Port
	if len(cfg.TLSDomains) > 0 {
		listenAddr = "127.0.0.1:" + cfg.Port
	}
	httpSrv := &http.Server{
		Addr:              listenAddr,
		Handler:           handler,
		ReadHeaderTimeout: 15 * time.Second,
	}

	if cfg.AppToken == "" {
		log.Printf("ВНИМАНИЕ: APP_TOKEN не задан — сервер не проверяет токен приложения")
	}
	log.Printf("Diagnostic Tool server (DeepSeek, модель %s) слушает %s, data=%s", cfg.Model, listenAddr, cfg.DataDir)
	if err := httpSrv.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}

func loadConfig() Config {
	return Config{
		Port:              envDefault("PORT", "8080"),
		DataDir:           envDefault("DATA_DIR", "./data"),
		APIKey:            strings.TrimSpace(os.Getenv("DEEPSEEK_API_KEY")),
		BaseURL:           envDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
		Model:             envDefault("DEEPSEEK_MODEL", "deepseek-chat"),
		TranscribeBaseURL: strings.TrimRight(strings.TrimSpace(os.Getenv("TRANSCRIBE_BASE_URL")), "/"),
		TranscribeAPIKey:  strings.TrimSpace(os.Getenv("TRANSCRIBE_API_KEY")),
		TranscribeModel:   strings.TrimSpace(os.Getenv("TRANSCRIBE_MODEL")),
		TranscribeLang:    envDefault("TRANSCRIBE_LANGUAGE", "ru"),
		AppToken:          strings.TrimSpace(os.Getenv("APP_TOKEN")),
		TLSDomains:        splitDomains(os.Getenv("TLS_DOMAIN")),
		ACMEEmail:         strings.TrimSpace(os.Getenv("ACME_EMAIL")),
		HTTPSPort:         envDefault("HTTPS_PORT", "443"),
		HTTPPort:          envDefault("HTTP_PORT", "80"),
		CertDir:           envDefault("CERT_DIR", "./certs"),
		RateLimitPerMin:   envInt("RATE_LIMIT_PER_MIN", 120),
		MaxUploadMB:       envInt("MAX_UPLOAD_MB", 256),
		MaxUploadFiles:    envInt("MAX_UPLOAD_FILES", 10),
	}
}

func splitDomains(value string) []string {
	var out []string
	for _, d := range strings.Split(value, ",") {
		d = strings.TrimSpace(d)
		if d != "" && d != "off" {
			out = append(out, d)
		}
	}
	return out
}

func envInt(key string, fallback int) int {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "service": "diagnostic-tool"})
}

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	if !s.ds.Enabled() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "DEEPSEEK_API_KEY не настроен на сервере"})
		return
	}

	maxBytes := int64(s.cfg.MaxUploadMB) << 20
	if maxBytes <= 0 {
		maxBytes = maxUploadBytes
	}
	maxFiles := s.cfg.MaxUploadFiles
	if maxFiles <= 0 {
		maxFiles = 10
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	if err := r.ParseMultipartForm(16 << 20); err != nil {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{
			"error": "Не удалось принять загрузку (лимит " + strconv.FormatInt(maxBytes>>20, 10) + " МБ): " + err.Error(),
		})
		return
	}
	if r.MultipartForm != nil && len(r.MultipartForm.File["files"]) > maxFiles {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{
			"error": "Слишком много файлов (максимум " + strconv.Itoa(maxFiles) + ")",
		})
		return
	}

	id := newID()
	dir := s.store.dir(id)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	var files []FileInfo
	if r.MultipartForm != nil {
		for _, fh := range r.MultipartForm.File["files"] {
			name := sanitizeName(fh.Filename)
			dst := filepath.Join(dir, name)
			size, err := saveUploadedFile(fh, dst)
			if err != nil {
				writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
				return
			}
			files = append(files, FileInfo{Name: name, Path: dst, Size: size})
		}
	}

	sessionName := strings.TrimSpace(r.FormValue("sessionName"))
	if sessionName == "" {
		sessionName = id
	}

	st := &SessionState{
		ID:          id,
		State:       "processing",
		Stage:       "Очередь диагностики",
		Message:     "Сессия принята…",
		Options:     []string{},
		Car:         r.FormValue("car"),
		Complaint:   r.FormValue("complaint"),
		SessionName: sessionName,
		Files:       files,
		History:     []HistoryItem{},
		CreatedAt:   now(),
		UpdatedAt:   now(),
	}
	if err := s.store.Save(st); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"id": id})
	go s.runAnalysis(id, "")
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	st, err := s.store.Load(r.PathValue("id"))
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, st.Public())
}

func (s *Server) handleMessages(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	st, err := s.store.Load(id)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
		return
	}
	if st.State != "question" {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "Сейчас ответ не требуется"})
		return
	}

	text := trimBodyText(r)
	if text == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Пустой ответ"})
		return
	}

	s.store.Update(id, func(st *SessionState) {
		st.State = "processing"
		st.Stage = "Уточнение диагноза"
		st.Message = "Учитываю ответ и повторно проверяю данные…"
		st.UpdatedAt = now()
	})

	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	go s.runAnalysis(id, text)
}

func (s *Server) handleChat(w http.ResponseWriter, r *http.Request) {
	if !s.ds.Enabled() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "DEEPSEEK_API_KEY не настроен на сервере"})
		return
	}

	id := r.PathValue("id")
	st, err := s.store.Load(id)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
		return
	}

	text := trimBodyText(r)
	if text == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Пустое сообщение"})
		return
	}

	history := st.History
	if len(history) > 12 {
		history = history[len(history)-12:]
	}
	var sb strings.Builder
	for _, h := range history {
		sb.WriteString(h.Role)
		sb.WriteString(": ")
		sb.WriteString(h.Text)
		sb.WriteByte('\n')
	}

	answer, err := s.ds.Chat(r.Context(), []ChatMessage{
		{Role: "system", Content: followUpPrompt},
		{Role: "user", Content: fmt.Sprintf("Заключение:\n%s\nИстория:\n%s\nНовое сообщение владельца:\n%s", st.Conclusion, sb.String(), text)},
	})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	s.store.Update(id, func(st *SessionState) {
		st.History = append(st.History,
			HistoryItem{Role: "user", Text: text},
			HistoryItem{Role: "assistant", Text: answer},
		)
		st.UpdatedAt = now()
	})

	writeJSON(w, http.StatusOK, map[string]string{"answer": answer})
}

func (s *Server) handleConclusion(w http.ResponseWriter, r *http.Request) {
	st, err := s.store.Load(r.PathValue("id"))
	if err != nil {
		http.Error(w, "Диагностика не найдена", http.StatusNotFound)
		return
	}
	if st.Conclusion == "" {
		http.Error(w, "Заключение ещё не готово", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = w.Write([]byte(st.Conclusion))
}

func trimBodyText(r *http.Request) string {
	var body struct {
		Text string `json:"text"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body)
	return strings.TrimSpace(body.Text)
}

var unsafeNameChars = regexp.MustCompile(`[^a-zA-Z0-9._-]`)

func sanitizeName(name string) string {
	name = unsafeNameChars.ReplaceAllString(name, "_")
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == ".." {
		return "file"
	}
	return name
}

func saveUploadedFile(fh *multipart.FileHeader, dst string) (int64, error) {
	src, err := fh.Open()
	if err != nil {
		return 0, err
	}
	defer src.Close()
	out, err := os.Create(dst)
	if err != nil {
		return 0, err
	}
	defer out.Close()
	return io.Copy(out, src)
}

func newID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func now() string {
	return time.Now().UTC().Format("2006-01-02T15:04:05.000Z")
}

func envDefault(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func openLogFile(path string) *os.File {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil
	}
	return f
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		start := time.Now()
		next.ServeHTTP(rec, r)
		log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, rec.status, time.Since(start).Round(time.Millisecond))
	})
}
