package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const systemPrompt = `Ты — автомобильный диагност приложения Diagnostic Tool. Анализируй только предоставленные данные и явно отделяй факт от гипотезы. Сопоставляй audio, OBD, GPS и датчики по общей временной шкале. Не утверждай неисправность конкретной детали, если данные её не доказывают. Если для различения причин нужен простой дополнительный тест или вопрос владельцу — задай его.

Отвечай СТРОГО одним JSON-объектом без markdown:
{"state":"question|completed","stage":"...","message":"...","question":"...","options":["..."],"conclusion":"..."}
Для question поле conclusion пустое. Для completed question пустое.

При завершении заключение должно быть техническим и структурированным: исходная жалоба; что реально обнаружено в данных; корреляции и временные закономерности; возможные причины с уровнем уверенности; что проверить в первую очередь; контрольные проверки; ограничения анализа.`

const followUpPrompt = `Ты продолжаешь диалог по автомобильной диагностике. Отвечай по имеющимся данным, не придумывай измерения и чётко отделяй факт от предположения.`

type modelResult struct {
	State      string   `json:"state"`
	Stage      string   `json:"stage"`
	Message    string   `json:"message"`
	Question   string   `json:"question"`
	Options    []string `json:"options"`
	Conclusion string   `json:"conclusion"`
}

func parseModelJSON(text string) (modelResult, error) {
	var r modelResult
	if err := json.Unmarshal([]byte(strings.TrimSpace(text)), &r); err == nil {
		return r, nil
	}
	a := strings.Index(text, "{")
	b := strings.LastIndex(text, "}")
	if a >= 0 && b > a {
		if err := json.Unmarshal([]byte(text[a:b+1]), &r); err == nil {
			return r, nil
		}
	}
	return r, errors.New("ИИ вернул ответ не в JSON-формате")
}

func readTextIfExists(path string, max int) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	if len(b) > max {
		b = b[:max]
		if i := bytes.LastIndexByte(b, '\n'); i > 0 {
			b = b[:i+1]
		}
	}
	return string(b)
}

func (s *Server) runAnalysis(id, extra string) {
	lock := s.sessionLock(id)
	lock.Lock()
	defer lock.Unlock()

	ctx := context.Background()

	st, err := s.store.Update(id, func(st *SessionState) {
		st.State = "processing"
		st.Stage = "Подготовка данных"
		st.Message = "Собираю и сопоставляю файлы…"
		st.UpdatedAt = now()
	})
	if err != nil {
		return
	}

	dir := s.store.dir(id)
	session := readTextIfExists(filepath.Join(dir, "session.json"), 500_000)
	obd := readTextIfExists(filepath.Join(dir, "obd.csv"), 2_500_000)
	gps := readTextIfExists(filepath.Join(dir, "gps.csv"), 1_500_000)
	sensors := readTextIfExists(filepath.Join(dir, "sensors.csv"), 2_500_000)

	var audioPath, audioName string
	for _, f := range st.Files {
		if f.Name == "audio.wav" {
			audioPath, audioName = f.Path, f.Name
			break
		}
	}

	metrics := WavMetrics{}
	if audioPath != "" {
		if b, err := os.ReadFile(audioPath); err == nil {
			metrics = wavMetrics(b)
		}
	}

	s.store.Update(id, func(st *SessionState) {
		st.Stage = "Анализ звука"
		st.Message = "Выделяю акустические характеристики…"
		st.UpdatedAt = now()
	})

	transcript := ""
	if audioPath != "" && s.transcriber.Enabled() {
		if b, err := os.ReadFile(audioPath); err == nil {
			if txt, terr := s.transcriber.Transcribe(ctx, audioName, b); terr == nil {
				transcript = txt
			} else {
				transcript = "Транскрипция недоступна: " + terr.Error()
			}
		}
	}

	s.store.Update(id, func(st *SessionState) {
		st.Stage = "Сопоставление OBD и датчиков"
		st.Message = "Сопоставляю обороты, скорость, нагрузку, MAP и движения автомобиля…"
		st.UpdatedAt = now()
	})

	metricsJSON, _ := json.Marshal(metrics)
	prompt := fmt.Sprintf(`Данные автомобиля:
%s

Жалоба:
%s

Сессия:
%s

Аудио-метрики:
%s

Распознанная речь:
%s

OBD CSV:
%s

GPS CSV:
%s

SENSORS CSV:
%s

Дополнительный ответ владельца:
%s

Проведи диагностический анализ и верни JSON по заданному формату.`,
		st.Car,
		orDefault(st.Complaint, "не указана"),
		session,
		metricsJSON,
		transcript,
		obd,
		gps,
		sensors,
		orDefault(extra, "нет"),
	)

	answer, err := s.ds.Chat(ctx, []ChatMessage{
		{Role: "system", Content: systemPrompt},
		{Role: "user", Content: prompt},
	})
	if err != nil {
		s.fail(id, err)
		return
	}

	result, err := parseModelJSON(answer)
	if err != nil {
		s.fail(id, err)
		return
	}

	s.store.Update(id, func(st *SessionState) {
		st.State = firstNonEmpty(result.State, "question")
		st.Stage = firstNonEmpty(result.Stage, "Анализ")
		st.Message = result.Message
		st.Question = result.Question
		st.Options = result.Options
		if st.Options == nil {
			st.Options = []string{}
		}
		st.Conclusion = result.Conclusion
		entry := extra
		if entry == "" {
			entry = "initial"
		}
		st.History = append(st.History,
			HistoryItem{Role: "user", Text: entry},
			HistoryItem{Role: "assistant", Text: answer},
		)
		st.UpdatedAt = now()
	})
}

func (s *Server) fail(id string, err error) {
	s.store.Update(id, func(st *SessionState) {
		st.State = "error"
		st.Stage = "Ошибка"
		st.Message = err.Error()
		st.UpdatedAt = now()
	})
}

func orDefault(v, fallback string) string {
	if strings.TrimSpace(v) == "" {
		return fallback
	}
	return v
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
