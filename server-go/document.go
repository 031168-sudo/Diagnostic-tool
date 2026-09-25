package main

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type DocRow struct {
	Label string `json:"label"`
	Value string `json:"value"`
}

type ErrorItem struct {
	Code    string `json:"code"`
	Meaning string `json:"meaning"`
	Cause   string `json:"cause"`
	Remedy  string `json:"remedy"`
}

type ConclusionDoc struct {
	Title       string   `json:"title"`
	Subtitle    string   `json:"subtitle"`
	DateTime    string   `json:"dateTime"`
	Duration    string   `json:"duration"`
	Complaint   string   `json:"complaint"`
	CarRows     []DocRow `json:"carRows"`
	DataIntro   string   `json:"dataIntro"`
	FileRows    []DocRow `json:"fileRows"`
	DataRanges  string      `json:"dataRanges"`
	Analysis    string      `json:"analysis"`
	Results     []string    `json:"results"`
	Errors      []ErrorItem `json:"errors"`
	ErrorsNote  string      `json:"errorsNote"`
	Conclusion  string      `json:"conclusion"`
	Priority    string      `json:"priority"`
	Recommended string      `json:"recommended"`
	Limitation  string      `json:"limitation"`
	Disclaimer  string      `json:"disclaimer"`
}

const legalDisclaimer = "Настоящее заключение сформировано автоматически на основании предоставленных данных (аудиозаписи, показаний OBD-II, GPS и датчиков автомобиля). Оно носит рекомендательный и информационный характер, не является точной диагностикой и не заменяет осмотр и выявление неисправности квалифицированным специалистом в техцентре. Решение о ремонте и эксплуатации автомобиля принимает владелец."

type docPart struct {
	Complaint   string      `json:"complaint"`
	Analysis    string      `json:"analysis"`
	Results     []string    `json:"results"`
	Errors      []ErrorItem `json:"errors"`
	ErrorsNote  string      `json:"errorsNote"`
	Conclusion  string      `json:"conclusion"`
	Priority    string   `json:"priority"`
	Recommended string   `json:"recommended"`
	Limitation  string   `json:"limitation"`
}

type carInfo struct {
	Make         string `json:"make"`
	Model        string `json:"model"`
	Year         string `json:"year"`
	Engine       string `json:"engine"`
	Fuel         string `json:"fuel"`
	Transmission string `json:"transmission"`
	Drive        string `json:"drive"`
	VIN          string `json:"vin"`
	Mileage      string `json:"mileage"`
	Notes        string `json:"notes"`
}

func buildConclusionDoc(st *SessionState, metrics WavMetrics, part docPart) *ConclusionDoc {
	dir := ""
	if len(st.Files) > 0 {
		dir = filepath.Dir(st.Files[0].Path)
	}
	hasOBD := fileExists(filepath.Join(dir, "obd.csv"))
	doc := &ConclusionDoc{
		Title:       "ДИАГНОСТИЧЕСКОЕ ЗАКЛЮЧЕНИЕ",
		Subtitle:    "Анализ акустической записи параметров автомобиля",
		DateTime:    formatSessionDateTime(st),
		Duration:    formatSessionDuration(metrics.DurationSec),
		Complaint:   strings.TrimSpace(part.Complaint),
		CarRows:     carRows(st.Car, hasOBD),
		DataIntro:   dataIntro(dir),
		FileRows:    fileRows(dir, metrics),
		DataRanges:  obdRanges(filepath.Join(dir, "obd.csv")),
		Analysis:    strings.TrimSpace(part.Analysis),
		Results:     cleanList(part.Results),
		Errors:      cleanErrors(part.Errors),
		ErrorsNote:  strings.TrimSpace(part.ErrorsNote),
		Conclusion:  strings.TrimSpace(part.Conclusion),
		Priority:    strings.TrimSpace(part.Priority),
		Recommended: strings.TrimSpace(part.Recommended),
		Limitation:  strings.TrimSpace(part.Limitation),
		Disclaimer:  legalDisclaimer,
	}
	if doc.Complaint == "" {
		doc.Complaint = strings.TrimSpace(st.Complaint)
	}
	if doc.Limitation == "" {
		doc.Limitation = "Данное заключение основано на анализе предоставленной цифровой записи и описании проявления неисправности. Оно определяет наиболее согласующуюся группу причин, но не заменяет физическую проверку автомобиля и не устанавливает конкретную неисправную деталь без осмотра."
	}
	return doc
}

func formatSessionDateTime(st *SessionState) string {
	if t, err := time.Parse("20060102_150405", st.SessionName); err == nil {
		return t.Format("02.01.2006 15:04:05")
	}
	if t, err := time.Parse("2006-01-02T15:04:05.000Z", st.CreatedAt); err == nil {
		return t.Format("02.01.2006 15:04:05")
	}
	return ""
}

func formatSessionDuration(seconds float64) string {
	if seconds <= 0 {
		return ""
	}
	total := int(math.Round(seconds))
	h := total / 3600
	m := (total % 3600) / 60
	s := total % 60
	if h > 0 {
		return fmt.Sprintf("%d:%02d:%02d", h, m, s)
	}
	return fmt.Sprintf("%d:%02d", m, s)
}

func carRows(carJSON string, hasOBD bool) []DocRow {
	var c carInfo
	_ = json.Unmarshal([]byte(carJSON), &c)
	rows := []DocRow{}
	add := func(label, value string) {
		value = strings.TrimSpace(value)
		if value != "" {
			rows = append(rows, DocRow{Label: label, Value: value})
		}
	}
	add("Марка / модель", strings.TrimSpace(c.Make+" "+c.Model))
	add("Год / исполнение", c.Year)
	add("Двигатель", c.Engine)
	add("Топливо", c.Fuel)
	add("Коробка передач", c.Transmission)
	add("Привод", c.Drive)
	add("Пробег, км", c.Mileage)
	add("VIN", c.VIN)
	if hasOBD {
		add("Диагностический интерфейс", "ELM327 BLE")
		add("OBD протокол", "ISO 15765-4 CAN, 11-bit, 500 kbps (ATSP6)")
	}
	return rows
}

func dataIntro(dir string) string {
	names := []string{}
	for _, n := range []string{"audio.wav", "obd.csv", "gps.csv", "sensors.csv", "session.json"} {
		if fileExists(filepath.Join(dir, n)) {
			names = append(names, n)
		}
	}
	if len(names) == 0 {
		return ""
	}
	return "Для анализа предоставлена синхронная диагностическая сессия. В её составе получены файлы: " + strings.Join(names, ", ") + "."
}

func fileRows(dir string, metrics WavMetrics) []DocRow {
	rows := []DocRow{}
	if fileExists(filepath.Join(dir, "audio.wav")) {
		rate := metrics.Rate
		if rate == 0 {
			rate = 48000
		}
		channels := metrics.Channels
		if channels == 0 {
			channels = 1
		}
		desc := groupThousands(rate) + " Гц; " + strconv.Itoa(channels) + " канал; PCM16_LE"
		if metrics.DurationSec > 0 {
			desc += "; длительность около " + ruNumber(metrics.DurationSec, 1) + " с."
		} else {
			desc += "."
		}
		rows = append(rows, DocRow{Label: "audio.wav", Value: desc})
	}
	if fileExists(filepath.Join(dir, "obd.csv")) {
		rows = append(rows, DocRow{Label: "obd.csv", Value: groupThousands(countDataRows(filepath.Join(dir, "obd.csv"))) + " записей; RPM, скорость, нагрузка, дроссель, MAP, температура ОЖ, температура впуска, напряжение."})
	}
	if fileExists(filepath.Join(dir, "gps.csv")) {
		rows = append(rows, DocRow{Label: "gps.csv", Value: groupThousands(countDataRows(filepath.Join(dir, "gps.csv"))) + " записей; скорость, координаты, точность."})
	}
	if fileExists(filepath.Join(dir, "sensors.csv")) {
		rows = append(rows, DocRow{Label: "sensors.csv", Value: groupThousands(countDataRows(filepath.Join(dir, "sensors.csv"))) + " записей; акселерометр и гироскоп."})
	}
	if fileExists(filepath.Join(dir, "session.json")) {
		rows = append(rows, DocRow{Label: "session.json", Value: "Единая временная база android_elapsedRealtimeNanos; начало сессии и аудио зафиксированы раздельно."})
	}
	return rows
}

func obdRanges(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	r := csv.NewReader(f)
	r.FieldsPerRecord = -1
	header, err := r.Read()
	if err != nil {
		return ""
	}
	idx := map[string]int{}
	for i, h := range header {
		idx[strings.TrimSpace(h)] = i
	}
	type bounds struct {
		min, max float64
		ok       bool
	}
	cols := map[string]*bounds{}
	for _, c := range []string{"rpm", "speed_kmh", "load_pct", "throttle_pct", "map_kpa", "coolant_c", "intake_c", "voltage_v"} {
		cols[c] = &bounds{}
	}
	for {
		rec, err := r.Read()
		if err != nil {
			break
		}
		for name, b := range cols {
			i, ok := idx[name]
			if !ok || i >= len(rec) {
				continue
			}
			v := strings.TrimSpace(rec[i])
			if v == "" {
				continue
			}
			x, err := strconv.ParseFloat(v, 64)
			if err != nil {
				continue
			}
			if !b.ok {
				b.min, b.max, b.ok = x, x, true
			} else {
				if x < b.min {
					b.min = x
				}
				if x > b.max {
					b.max = x
				}
			}
		}
	}
	specs := []struct {
		key    string
		label  string
		unit   string
		digits int
	}{
		{"rpm", "RPM", " об/мин", 0},
		{"speed_kmh", "скорость", " км/ч", 0},
		{"load_pct", "нагрузка", "%", 0},
		{"throttle_pct", "дроссель", "%", 1},
		{"map_kpa", "MAP", " кПа", 0},
		{"coolant_c", "температура ОЖ", " °C", 0},
		{"intake_c", "температура впуска", " °C", 0},
		{"voltage_v", "напряжение", " В", 2},
	}
	parts := []string{}
	for _, sp := range specs {
		b := cols[sp.key]
		if b == nil || !b.ok {
			continue
		}
		parts = append(parts, sp.label+" "+ruNumber(b.min, sp.digits)+"–"+ruNumber(b.max, sp.digits)+sp.unit)
	}
	if len(parts) == 0 {
		return ""
	}
	return "В OBD-записи диапазоны наблюдавшихся параметров: " + strings.Join(parts, "; ") + "."
}

func countDataRows(path string) int {
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()
	r := csv.NewReader(f)
	r.FieldsPerRecord = -1
	n := 0
	for {
		rec, err := r.Read()
		if err != nil {
			break
		}
		if len(rec) == 0 {
			continue
		}
		n++
	}
	if n > 0 {
		n--
	}
	return n
}

func fileExists(path string) bool {
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func cleanErrors(items []ErrorItem) []ErrorItem {
	out := []ErrorItem{}
	for _, it := range items {
		it.Code = strings.TrimSpace(it.Code)
		it.Meaning = strings.TrimSpace(it.Meaning)
		it.Cause = strings.TrimSpace(it.Cause)
		it.Remedy = strings.TrimSpace(it.Remedy)
		if it.Code != "" || it.Meaning != "" {
			out = append(out, it)
		}
	}
	return out
}

func cleanList(items []string) []string {
	out := []string{}
	for _, it := range items {
		it = strings.TrimSpace(it)
		if it != "" {
			out = append(out, it)
		}
	}
	return out
}

func ruNumber(v float64, digits int) string {
	s := strconv.FormatFloat(v, 'f', digits, 64)
	return strings.ReplaceAll(s, ".", ",")
}

func groupThousands(n int) string {
	s := strconv.Itoa(n)
	neg := strings.HasPrefix(s, "-")
	if neg {
		s = s[1:]
	}
	var b strings.Builder
	for i, ch := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			b.WriteByte(' ')
		}
		b.WriteRune(ch)
	}
	if neg {
		return "-" + b.String()
	}
	return b.String()
}
