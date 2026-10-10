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

const systemPrompt = `Ты — автомобильный диагност приложения Alfa Diagnostic. Анализируй только предоставленные данные, отделяй факт от гипотезы и пиши кратко и ясно, как опытный мастер. Сопоставляй аудио, OBD, GPS и датчики по общей временной шкале. Не утверждай неисправность конкретной детали, если данные её не доказывают. Если для различения причин нужен простой тест или вопрос владельцу — задай его.

Обязательно учитывай движение автомобиля: если скорость по OBD и GPS отсутствует или равна нулю — автомобиль стоит на месте; в этом случае не делай выводов о разгоне, движении под нагрузкой или дорожных шумах, а анализируй режим на месте (холостой ход). Опирайся на раздел «Движение» и не противоречь ему.

Отвечай СТРОГО одним JSON-объектом без markdown:
{"state":"question|completed","stage":"...","message":"...","question":"...","options":["..."],"conclusion":"...","document":{"complaint":"...","analysis":"...","results":["..."],"errors":[{"code":"...","meaning":"...","cause":"...","remedy":"..."}],"errorsNote":"...","serviceNote":"...","conclusion":"...","priority":"...","recommended":"...","limitation":"..."}}
Для question поля conclusion и document пустые/отсутствуют. Для completed question пустое.

Стиль: коротко, по делу, без воды и повторений, без перечисления всех числовых значений — только ключевые. Пиши так, чтобы было понятно владельцу и полезно мастеру.

При завершении заполняй поля document:
- complaint — жалоба владельца, 2–3 предложения;
- analysis — что анализировалось, 2–3 предложения;
- results — 4–6 пунктов: только значимые наблюдения (частотные характеристики звука, его связь с нагрузкой и оборотами, что исключено);
- errors — разбор каждого кода DTC: code, meaning, cause, remedy;
- errorsNote — связаны ли выявленные коды с акустической жалобой. Если связаны — объясни как; если нет — прямо скажи, что не связаны. Если кодов нет — напиши, что коды не обнаружены;
- serviceNote — связь жалобы и кодов с технической историей (сервисной книжкой). Если жалоба или коды объясняются записями журнала — прямо укажи это с датой/пробегом (например: «скрип сзади, а в журнале замена задних тормозных дисков и колодок N км назад — вероятно связано»; «ошибка по лямбде, а в журнале указано удаление катализатора»). Если связи нет или журнал пуст — оставь пусто;
- conclusion — наиболее вероятная группа причин, 2–3 предложения;
- priority — что проверить в первую очередь, одной строкой;
- recommended — как и где проверять автомобиль, 2–3 предложения;
- limitation — ограничения анализа, 1–2 предложения.
Если коды связаны с посторонними звуками — учти их в анализе и заключении.
Поле conclusion продублируй связным текстом заключения для чата.`

const followUpPrompt = `Ты продолжаешь диалог по автомобильной диагностике. Отвечай по имеющимся данным, не придумывай измерения и чётко отделяй факт от предположения. Учитывай данные о движении: если скорость отсутствует или равна нулю — автомобиль стоит на месте, не выдумывай разгон и движение.`

const finalizePrompt = `Ты обновляешь итоговое диагностическое заключение с учётом диалога с владельцем. Учти всё, что обсуждалось в диалоге, и построй итоговое заключение именно по этим дискуссиям и данным. Не противоречь данным о движении. Верни СТРОГО одним JSON-объектом без markdown:
{"state":"completed","stage":"...","message":"...","question":"","options":[],"conclusion":"...","document":{"complaint":"...","analysis":"...","results":["..."],"errors":[{"code":"...","meaning":"...","cause":"...","remedy":"..."}],"errorsNote":"...","conclusion":"...","priority":"...","recommended":"...","limitation":"..."}}`

const maxPromptChars = 300000

type modelResult struct {
	State      string   `json:"state"`
	Stage      string   `json:"stage"`
	Message    string   `json:"message"`
	Question   string   `json:"question"`
	Options    []string `json:"options"`
	Conclusion string   `json:"conclusion"`
	Document   docPart  `json:"document"`
}

func parseModelJSON(text string) (modelResult, error) {
	var r modelResult
	trimmed := strings.TrimSpace(text)
	if err := json.Unmarshal([]byte(trimmed), &r); err == nil {
		return r, nil
	}
	if obj := extractJSONObject(trimmed); obj != "" {
		if err := json.Unmarshal([]byte(obj), &r); err == nil {
			return r, nil
		}
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

func extractJSONObject(s string) string {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, "```") {
		s = strings.TrimPrefix(s, "```")
		if i := strings.IndexByte(s, '\n'); i >= 0 {
			s = s[i+1:]
		}
		if j := strings.LastIndex(s, "```"); j >= 0 {
			s = s[:j]
		}
		s = strings.TrimSpace(s)
	}
	start := strings.IndexByte(s, '{')
	if start < 0 {
		return ""
	}
	depth := 0
	inStr := false
	esc := false
	for i := start; i < len(s); i++ {
		c := s[i]
		if inStr {
			switch {
			case esc:
				esc = false
			case c == '\\':
				esc = true
			case c == '"':
				inStr = false
			}
			continue
		}
		switch c {
		case '"':
			inStr = true
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return s[start : i+1]
			}
		}
	}
	return ""
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

func stripHashLines(s string) string {
	var out []string
	for _, line := range strings.Split(s, "\n") {
		if strings.HasPrefix(strings.TrimSpace(line), "#") {
			continue
		}
		out = append(out, line)
	}
	return strings.TrimSpace(strings.Join(out, "\n"))
}

func maxOf(t *table, cols ...string) (float64, bool) {
	if t == nil {
		return 0, false
	}
	ci := t.findCol(cols...)
	if ci < 0 || !t.valid[ci] {
		return 0, false
	}
	mx := math.Inf(-1)
	ok := false
	for _, v := range t.column(ci) {
		if math.IsNaN(v) {
			continue
		}
		if v > mx {
			mx = v
			ok = true
		}
	}
	return mx, ok
}

func movementText(obd, gps *table) string {
	var parts []string
	if v, ok := maxOf(obd, "speed"); ok {
		parts = append(parts, fmt.Sprintf("OBD-скорость: максимум %.0f км/ч", v))
	} else {
		parts = append(parts, "OBD-скорость: данных нет")
	}
	if v, ok := maxOf(gps, "speed"); ok {
		parts = append(parts, fmt.Sprintf("GPS-скорость: максимум %.0f км/ч", v))
	} else {
		parts = append(parts, "GPS-скорость: данных нет")
	}
	return strings.Join(parts, "; ") + ". Если скорость отсутствует или равна нулю — автомобиль стоит на месте."
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
	errorsText := stripHashLines(readTextIfExists(filepath.Join(dir, "errors.txt"), 100_000))
	journalText := strings.TrimSpace(readTextIfExists(filepath.Join(dir, "journal.json"), 200_000))

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
	var audioBytes []byte
	if audioPath != "" {
		if b, err := os.ReadFile(audioPath); err == nil {
			audioBytes = b
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
	spectrum := audioSpectrumText(audioBytes, obdTable)
	movement := movementText(obdTable, gpsTable)

	prompt := fmt.Sprintf(`Данные автомобиля:
%s

Жалоба:
%s

Сессия:
%s

Движение:
%s

Аудио-метрики:
%s

Аудио-динамика:
%s

Акустический спектр:
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

Считанные коды неисправностей (DTC):
%s

Техническая история автомобиля (сервисная книжка, JSON):
%s

Дополнительный ответ владельца:
%s

Примечание: сессия передана фирменным упакованным контейнером и распакована сервером; работай с распакованными данными.

Проведи диагностический анализ по этим сводкам и верни JSON по заданному формату.`,
		st.Car,
		orDefault(st.Complaint, "не указана"),
		session,
		movement,
		metricsJSON,
		audioTimelineText(audioRMS),
		orDefault(spectrum, "недостаточно данных"),
		orDefault(transcript, "нет"),
		orDefault(corr, "недостаточно данных"),
		obdSum,
		gpsSum,
		sensorsSum,
		orDefault(errorsText, "нет данных"),
		orDefault(journalText, "нет данных"),
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

	messages := []ChatMessage{
		{Role: "system", Content: systemPrompt},
		{Role: "user", Content: prompt},
	}
	answer, err := s.ds.Chat(ctx, messages, true)
	if err != nil {
		s.fail(id, err)
		return
	}

	result, err := parseModelJSON(answer)
	if err != nil {
		log.Printf("analysis %s: ответ не JSON (%v), %d символов: %s", id, err, len(answer), truncateForLog(answer, 400))
		retryMessages := append(messages,
			ChatMessage{Role: "assistant", Content: answer},
			ChatMessage{Role: "user", Content: "Твой ответ не является корректным JSON. Верни ТОЛЬКО валидный JSON-объект по заданной схеме, без markdown и пояснений."},
		)
		answer, err = s.ds.Chat(ctx, retryMessages, true)
		if err != nil {
			s.fail(id, err)
			return
		}
		result, err = parseModelJSON(answer)
		if err != nil {
			log.Printf("analysis %s: повторный ответ не JSON (%v), %d символов: %s", id, err, len(answer), truncateForLog(answer, 400))
			s.completeFallback(id, st, metrics, answer)
			return
		}
	}

	var doc *ConclusionDoc
	if firstNonEmpty(result.State, "question") == "completed" {
		doc = buildConclusionDoc(st, metrics, result.Document)
		if doc.Conclusion == "" {
			doc.Conclusion = result.Conclusion
		}
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
		st.Facts = movement
		if doc != nil {
			st.Document = doc
		}
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

func truncateForLog(s string, max int) string {
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) > max {
		return s[:max] + "…"
	}
	return s
}

func (s *Server) completeFallback(id string, st *SessionState, metrics WavMetrics, answer string) {
	text := strings.TrimSpace(answer)
	if text == "" {
		text = "Не удалось сформировать заключение."
	}
	doc := buildConclusionDoc(st, metrics, docPart{Complaint: st.Complaint, Conclusion: text})
	doc.Conclusion = text
	s.store.Update(id, func(st *SessionState) {
		st.State = "completed"
		st.Stage = "Анализ"
		st.Message = "Заключение сформировано"
		st.Conclusion = text
		st.Document = doc
		st.History = append(st.History,
			HistoryItem{Role: "user", Text: "initial"},
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
