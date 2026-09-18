package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
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

const maxPromptChars = 300000

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

func summarizeTable(name string, t *table, err error) string {
	if err != nil || t == nil {
		return fmt.Sprintf("%s: нет данных\n", name)
	}
	return t.summarize(name, 40) + "\n"
}

func audioTimelineText(audio []float64) string {
	if len(audio) == 0 {
		return ""
	}
	var sum, peak float64
	peakT, cnt := 0, 0
	for i, v := range audio {
		if math.IsNaN(v) {
			continue
		}
		sum += v
		cnt++
		if v > peak {
			peak, peakT = v, i
		}
	}
	mean := 0.0
	if cnt > 0 {
		mean = sum / float64(cnt)
	}
	var b strings.Builder
	fmt.Fprintf(&b, "RMS: среднее %.4f, максимум %.4f (t≈%d сек)\n", mean, peak, peakT)
	buckets := 60
	step := len(audio) / buckets
	if step < 1 {
		step = 1
	}
	var vals []string
	for i := 0; i < len(audio); i += step {
		end := i + step
		if end > len(audio) {
			end = len(audio)
		}
		var s float64
		var c int
		for j := i; j < end; j++ {
			if !math.IsNaN(audio[j]) {
				s += audio[j]
				c++
			}
		}
		if c > 0 {
			vals = append(vals, fmt.Sprintf("%.3f", s/float64(c)))
		} else {
			vals = append(vals, "-")
		}
	}
	fmt.Fprintf(&b, "Дорожка RMS (прорежено до %d точек): %s\n", len(vals), strings.Join(vals, ", "))
	return b.String()
}

func audioCorrelations(audio []float64, tables ...*table) string {
	if len(audio) == 0 {
		return ""
	}
	targets := []struct {
		name string
		cols []string
	}{
		{"rpm", []string{"rpm"}},
		{"speed", []string{"speed"}},
		{"load", []string{"load"}},
		{"map", []string{"map"}},
		{"throttle", []string{"throttle"}},
		{"ax", []string{"ax"}},
		{"ay", []string{"ay"}},
		{"az", []string{"az"}},
	}
	var b strings.Builder
	for _, tg := range targets {
		for _, t := range tables {
			if t == nil {
				continue
			}
			ci := t.findCol(tg.cols...)
			if ci < 0 || !t.valid[ci] {
				continue
			}
			r, n := pearson(audio, t.perSecondMean(ci))
			if n >= 10 {
				fmt.Fprintf(&b, "  звук↔%s: r=%.2f (n=%d сек)\n", tg.name, r, n)
			}
			break
		}
	}
	return b.String()
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
	session := readTextIfExists(filepath.Join(dir, "session.json"), 4000)

	obdRaw := readTextIfExists(filepath.Join(dir, "obd.csv"), 64<<20)
	gpsRaw := readTextIfExists(filepath.Join(dir, "gps.csv"), 16<<20)
	sensorsRaw := readTextIfExists(filepath.Join(dir, "sensors.csv"), 64<<20)

	obdTable, obdErr := parseTable(obdRaw)
	gpsTable, gpsErr := parseTable(gpsRaw)
	sensorsTable, sensorsErr := parseTable(sensorsRaw)

	var audioPath, audioName string
	for _, f := range st.Files {
		if f.Name == "audio.wav" {
			audioPath, audioName = f.Path, f.Name
			break
		}
	}

	metrics := WavMetrics{}
	var audioRMS []float64
	if audioPath != "" {
		if b, err := os.ReadFile(audioPath); err == nil {
			metrics = wavMetrics(b)
			audioRMS = wavPerSecondRMS(b)
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
		st.Message = "Считаю статистику, события и корреляции…"
		st.UpdatedAt = now()
	})

	metricsJSON, _ := json.Marshal(metrics)
	obdSum := summarizeTable("OBD", obdTable, obdErr)
	gpsSum := summarizeTable("GPS", gpsTable, gpsErr)
	sensorsSum := summarizeTable("SENSORS", sensorsTable, sensorsErr)
	corr := audioCorrelations(audioRMS, obdTable, sensorsTable, gpsTable)

	prompt := fmt.Sprintf(`Данные автомобиля:
%s

Жалоба:
%s

Сессия:
%s

Аудио-метрики:
%s

Аудио-динамика:
%s

Распознанная речь:
%s

Корреляции звука с сигналами (r, по секундам):
%s

Сводка по OBD:
%s

Сводка по GPS:
%s

Сводка по датчикам (акселерометр/гироскоп):
%s

Дополнительный ответ владельца:
%s

Проведи диагностический анализ по этим сводкам и верни JSON по заданному формату.`,
		st.Car,
		orDefault(st.Complaint, "не указана"),
		session,
		metricsJSON,
		audioTimelineText(audioRMS),
		orDefault(transcript, "нет"),
		orDefault(corr, "недостаточно данных"),
		obdSum,
		gpsSum,
		sensorsSum,
		orDefault(extra, "нет"),
	)

	if len(prompt) > maxPromptChars {
		cut := prompt[:maxPromptChars]
		if i := strings.LastIndexByte(cut, '\n'); i > 0 {
			cut = cut[:i]
		}
		prompt = cut + "\n\n[данные усечены из-за ограничения размера]"
	}
	log.Printf("analysis %s: prompt %d символов (~%d токенов)", id, len(prompt), len(prompt)/4)

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
