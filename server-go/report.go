package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

type liveReportRequest struct {
	Car      json.RawMessage `json:"car"`
	Values   map[string]any  `json:"values"`
	Codes    []string        `json:"codes"`
	Mil      *bool           `json:"mil"`
	DtcCount *int            `json:"dtcCount"`
}

const liveReportPrompt = `Ты — автомобильный диагност приложения AlfaDiag. По текущему срезу параметров OBD-II и кодам неисправностей составь краткий, но информативный отчёт для владельца.

Анализируй ТОЛЬКО переданные данные. Это мгновенный срез, а не запись: не утверждай, что видел динамику, разгон, дорожные шумы или изменение параметров во времени. Если параметр не передан — не выдумывай его. Чётко отделяй факт от гипотезы.

Для каждого кода неисправности дай:
- code — сам код;
- meaning — что означает;
- cause — вероятные причины;
- remedy — что проверить и что делать.
Если код тебе неизвестен — дай осторожное предположение и попроси уточнить по сервисной документации.

Оцени состояние по параметрам (температура ОЖ, напряжение, топливные коррекции, лямбда, катализатор, наддув и т.д.), но не ставь диагноз по одному значению.

ВАЖНО: если в запросе передан список кодов, массив errors ОБЯЗАТЕЛЬНО должен содержать КАЖДЫЙ код из списка, даже если причина неочевидна. Не оставляй errors пустым при наличии кодов. Для каждого кода заполни meaning, cause и remedy.

Верни СТРОГО одним JSON-объектом без markdown:
{"complaint":"...","analysis":"...","results":["..."],"errors":[{"code":"...","meaning":"...","cause":"...","remedy":"..."}],"errorsNote":"...","serviceNote":"","conclusion":"...","priority":"...","recommended":"...","limitation":"..."}`

func (s *Server) handleReport(w http.ResponseWriter, r *http.Request) {
	if !s.ds.Enabled() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "DEEPSEEK_API_KEY не настроен на сервере"})
		return
	}

	var req liveReportRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Некорректный JSON"})
		return
	}

	var car carInfo
	_ = json.Unmarshal(req.Car, &car)

	valuesText := "нет данных"
	if len(req.Values) > 0 {
		if b, err := json.MarshalIndent(req.Values, "", "  "); err == nil {
			valuesText = string(b)
		}
	}
	codesText := "нет"
	if len(req.Codes) > 0 {
		codesText = strings.Join(req.Codes, ", ")
	}
	milText := "неизвестно"
	if req.Mil != nil {
		if *req.Mil {
			milText = "включена"
		} else {
			milText = "выключена"
		}
	}
	dtcText := "неизвестно"
	if req.DtcCount != nil {
		dtcText = fmt.Sprintf("%d", *req.DtcCount)
	}

	userPrompt := fmt.Sprintf(`Автомобиль:
%s

Текущие параметры OBD-II (JSON):
%s

Коды неисправностей: %s
Лампа Check Engine: %s
Количество кодов по ЭБУ: %s

Сформируй отчёт по этим данным.`,
		reportCarText(car), valuesText, codesText, milText, dtcText)

	accountID := anonymousAccount
	if a, ok := s.accountFromRequest(r); ok {
		accountID = a.ID
	}
	sessionID := fmt.Sprintf("report-%d", time.Now().UnixNano())

	answer, err := s.chat(r.Context(), accountID, sessionID, "report", []ChatMessage{
		{Role: "system", Content: liveReportPrompt},
		{Role: "user", Content: userPrompt},
	}, true)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	result, err := parseModelJSON(answer)
	if err != nil {
		// Модель ответила не JSON — отдаём хотя бы текстовый отчёт.
		doc := baseLiveReportDoc(car, req)
		doc.Analysis = strings.TrimSpace(answer)
		doc.Conclusion = strings.TrimSpace(answer)
		writeJSON(w, http.StatusOK, map[string]any{"document": doc})
		return
	}

	doc := baseLiveReportDoc(car, req)
	part := result.Document
	doc.Analysis = strings.TrimSpace(part.Analysis)
	doc.Results = cleanList(part.Results)
	doc.Errors = mergeReportErrors(cleanErrors(part.Errors), req.Codes)
	doc.ErrorsNote = strings.TrimSpace(part.ErrorsNote)
	doc.Conclusion = strings.TrimSpace(part.Conclusion)
	if doc.Conclusion == "" {
		doc.Conclusion = strings.TrimSpace(result.Conclusion)
	}
	doc.Priority = strings.TrimSpace(part.Priority)
	doc.Recommended = strings.TrimSpace(part.Recommended)
	doc.Limitation = strings.TrimSpace(part.Limitation)
	writeJSON(w, http.StatusOK, map[string]any{"document": doc})
}

func baseLiveReportDoc(car carInfo, req liveReportRequest) *ConclusionDoc {
	doc := &ConclusionDoc{
		Title:       "ОТЧЁТ ПО ДИАГНОСТИКЕ",
		Subtitle:    "Текущие параметры OBD-II и коды неисправностей",
		DateTime:    time.Now().Format("02.01.2006 15:04"),
		Duration:    "мгновенный срез",
		Complaint:   "Отчёт по текущему срезу параметров OBD-II",
		CarRows:     reportCarRows(car),
		DataIntro:   "Отчёт сформирован по параметрам, считанным в момент запроса. Запись и анализ во времени не выполнялись.",
		FileRows:    []DocRow{},
		DataRanges:  "Параметры считаны в момент запроса (мгновенный срез).",
		Errors:      mergeReportErrors(nil, req.Codes),
		Disclaimer:  legalDisclaimer,
	}
	if len(req.Codes) > 0 {
		doc.ErrorsNote = "Обнаружены сохранённые коды неисправностей."
	}
	return doc
}

func reportCarText(c carInfo) string {
	parts := make([]string, 0, 10)
	add := func(label, value string) {
		if strings.TrimSpace(value) != "" {
			parts = append(parts, label+": "+strings.TrimSpace(value))
		}
	}
	add("Марка", c.Make)
	add("Модель", c.Model)
	add("Год", c.Year)
	add("Двигатель", c.Engine)
	add("Топливо", c.Fuel)
	add("КПП", c.Transmission)
	add("Привод", c.Drive)
	add("VIN", c.VIN)
	add("Пробег", c.Mileage)
	add("Заметки", c.Notes)
	if len(parts) == 0 {
		return "не указан"
	}
	return strings.Join(parts, "\n")
}

func mergeReportErrors(items []ErrorItem, codes []string) []ErrorItem {
	have := make(map[string]bool, len(items))
	for _, e := range items {
		have[strings.ToUpper(strings.TrimSpace(e.Code))] = true
	}
	for _, c := range codes {
		key := strings.ToUpper(strings.TrimSpace(c))
		if key == "" || have[key] {
			continue
		}
		have[key] = true
		items = append(items, ErrorItem{
			Code:    key,
			Meaning: "Код требует уточнения по сервисной документации",
			Cause:   "Причина не определена по переданным данным",
			Remedy:  "Проверить цепь и компонент, связанный с кодом",
		})
	}
	return items
}

func reportCarRows(c carInfo) []DocRow {
	rows := make([]DocRow, 0, 10)
	add := func(label, value string) {
		if strings.TrimSpace(value) != "" {
			rows = append(rows, DocRow{Label: label, Value: strings.TrimSpace(value)})
		}
	}
	add("Марка", c.Make)
	add("Модель", c.Model)
	add("Год", c.Year)
	add("Двигатель", c.Engine)
	add("Топливо", c.Fuel)
	add("КПП", c.Transmission)
	add("Привод", c.Drive)
	add("VIN", c.VIN)
	add("Пробег", c.Mileage)
	return rows
}
