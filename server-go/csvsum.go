package main

import (
	"encoding/csv"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
)

const csvMaxRows = 300000

type table struct {
	header  []string
	rows    [][]float64
	timeIdx int
	valid   []bool
}

func isTimeCol(name string) bool {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "t", "time", "timestamp", "sec", "seconds", "время", "сек":
		return true
	}
	return false
}

func parseTable(raw string) (*table, error) {
	r := csv.NewReader(strings.NewReader(raw))
	r.FieldsPerRecord = -1
	r.LazyQuotes = true
	recs, err := r.ReadAll()
	if err != nil || len(recs) < 2 {
		return nil, fmt.Errorf("недостаточно строк")
	}
	header := make([]string, len(recs[0]))
	for i, h := range recs[0] {
		header[i] = strings.TrimSpace(h)
	}
	data := recs[1:]
	if len(data) > csvMaxRows {
		data = data[:csvMaxRows]
	}
	timeIdx := 0
	for i, h := range header {
		if isTimeCol(h) {
			timeIdx = i
			break
		}
	}
	rows := make([][]float64, 0, len(data))
	for _, rec := range data {
		row := make([]float64, len(header))
		numOK := false
		for i := range header {
			row[i] = math.NaN()
			if i < len(rec) {
				if v, err := strconv.ParseFloat(strings.TrimSpace(rec[i]), 64); err == nil {
					row[i] = v
					if i != timeIdx {
						numOK = true
					}
				}
			}
		}
		if numOK {
			rows = append(rows, row)
		}
	}
	if len(rows) == 0 {
		return nil, fmt.Errorf("нет числовых строк")
	}
	valid := make([]bool, len(header))
	for i := range header {
		cnt := 0
		for _, row := range rows {
			if !math.IsNaN(row[i]) {
				cnt++
			}
		}
		valid[i] = cnt >= len(rows)*7/10
	}
	return &table{header: header, rows: rows, timeIdx: timeIdx, valid: valid}, nil
}

func (t *table) timeAt(i int) float64 {
	if t.timeIdx >= 0 && t.timeIdx < len(t.rows[i]) && !math.IsNaN(t.rows[i][t.timeIdx]) {
		return t.rows[i][t.timeIdx]
	}
	return float64(i)
}

func (t *table) column(idx int) []float64 {
	out := make([]float64, 0, len(t.rows))
	for _, row := range t.rows {
		if idx < len(row) && !math.IsNaN(row[idx]) {
			out = append(out, row[idx])
		}
	}
	return out
}

func (t *table) findCol(substrs ...string) int {
	for _, s := range substrs {
		for i, h := range t.header {
			if strings.Contains(strings.ToLower(h), s) {
				return i
			}
		}
	}
	return -1
}

func (t *table) keyColumns(max int) []int {
	preferred := []string{"rpm", "speed", "load", "throttle", "map", "coolant", "intake", "voltage", "ax", "ay", "az"}
	var out []int
	seen := map[int]bool{}
	for _, name := range preferred {
		if i := t.findCol(name); i >= 0 && t.valid[i] && !seen[i] {
			out = append(out, i)
			seen[i] = true
		}
		if len(out) >= max {
			return out
		}
	}
	for i := range t.header {
		if i == t.timeIdx || !t.valid[i] || seen[i] {
			continue
		}
		out = append(out, i)
		seen[i] = true
		if len(out) >= max {
			break
		}
	}
	return out
}

type numStats struct {
	Min, Max, Mean, P50, P90, P99 float64
	Count                         int
}

func calcStats(vals []float64) numStats {
	if len(vals) == 0 {
		return numStats{}
	}
	v := append([]float64(nil), vals...)
	sort.Float64s(v)
	var sum float64
	for _, x := range v {
		sum += x
	}
	return numStats{
		Min:   v[0],
		Max:   v[len(v)-1],
		Mean:  sum / float64(len(v)),
		P50:   percentile(v, 0.50),
		P90:   percentile(v, 0.90),
		P99:   percentile(v, 0.99),
		Count: len(v),
	}
}

func percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(p * float64(len(sorted)-1))
	if idx < 0 {
		idx = 0
	}
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

func fnum(v float64) string {
	if math.IsNaN(v) {
		return "-"
	}
	av := math.Abs(v)
	switch {
	case av >= 100:
		return strconv.FormatFloat(v, 'f', 0, 64)
	case av >= 1:
		return strconv.FormatFloat(v, 'f', 2, 64)
	case av == 0:
		return "0"
	default:
		return strconv.FormatFloat(v, 'f', 3, 64)
	}
}

func (t *table) resampleText(cols []int, buckets int) string {
	if len(t.rows) == 0 || len(cols) == 0 || buckets <= 0 {
		return ""
	}
	if buckets > len(t.rows) {
		buckets = len(t.rows)
	}
	var b strings.Builder
	for k := 0; k < buckets; k++ {
		start := k * len(t.rows) / buckets
		end := (k + 1) * len(t.rows) / buckets
		if end <= start {
			end = start + 1
		}
		if end > len(t.rows) {
			end = len(t.rows)
		}
		sums := make([]float64, len(cols))
		cnts := make([]int, len(cols))
		var tsum float64
		var tcnt int
		for r := start; r < end; r++ {
			tsum += t.timeAt(r)
			tcnt++
			for j, ci := range cols {
				if ci < len(t.rows[r]) && !math.IsNaN(t.rows[r][ci]) {
					sums[j] += t.rows[r][ci]
					cnts[j]++
				}
			}
		}
		tavg := 0.0
		if tcnt > 0 {
			tavg = tsum / float64(tcnt)
		}
		fmt.Fprintf(&b, "  t≈%s:", fnum(tavg))
		for j, ci := range cols {
			if cnts[j] == 0 {
				continue
			}
			fmt.Fprintf(&b, " %s=%s", t.header[ci], fnum(sums[j]/float64(cnts[j])))
		}
		b.WriteString("\n")
	}
	return b.String()
}

func (t *table) eventsText() string {
	var b strings.Builder
	write := func(format string, args ...any) {
		fmt.Fprintf(&b, "  - "+format+"\n", args...)
	}

	if si := t.findCol("speed"); si >= 0 && t.valid[si] {
		var idle, drive float64
		var maxAccel, maxAccelT float64
		var maxDecel, maxDecelT float64
		starts := 0
		wasIdle := false
		prevSpeed := math.NaN()
		prevT := math.NaN()
		for i := 0; i < len(t.rows); i++ {
			s := t.rows[i][si]
			tv := t.timeAt(i)
			if math.IsNaN(s) {
				prevSpeed, prevT = s, tv
				continue
			}
			if s < 0.5 {
				wasIdle = true
			} else if wasIdle && s >= 3 {
				starts++
				wasIdle = false
			}
			if !math.IsNaN(prevSpeed) && !math.IsNaN(prevT) {
				dt := tv - prevT
				if dt > 0 && dt < 5 {
					a := (s - prevSpeed) / dt
					if a > maxAccel {
						maxAccel, maxAccelT = a, tv
					}
					if a < maxDecel {
						maxDecel, maxDecelT = a, tv
					}
					if s < 1 {
						idle += dt
					} else {
						drive += dt
					}
				}
			}
			prevSpeed, prevT = s, tv
		}
		write("скорость: стартов с места %d", starts)
		if maxAccel > 0.1 {
			write("макс. ускорение ≈%s м/с² (t≈%s)", fnum(maxAccel), fnum(maxAccelT))
		}
		if maxDecel < -0.1 {
			write("макс. замедление ≈%s м/с² (t≈%s)", fnum(maxDecel), fnum(maxDecelT))
		}
		write("время: в движении ≈%s сек, на холостом (speed<1) ≈%s сек", fnum(drive), fnum(idle))
	}

	thresholdEvent := func(label string, idx int, threshold float64, above bool) {
		if idx < 0 || !t.valid[idx] {
			return
		}
		var dur, peak, peakT float64
		prevT := math.NaN()
		for i := 0; i < len(t.rows); i++ {
			v := t.rows[i][idx]
			tv := t.timeAt(i)
			if math.IsNaN(v) {
				prevT = tv
				continue
			}
			cond := v > threshold
			if !above {
				cond = v < threshold
			}
			if cond {
				if !math.IsNaN(prevT) {
					dt := tv - prevT
					if dt > 0 && dt < 5 {
						dur += dt
					}
				}
				if math.Abs(v) > math.Abs(peak) {
					peak, peakT = v, tv
				}
			}
			prevT = tv
		}
		if dur > 0 {
			write("%s %s %s: суммарно ≈%s сек, экстремум %s (t≈%s)", label, map[bool]string{true: "выше", false: "ниже"}[above], fnum(threshold), fnum(dur), fnum(peak), fnum(peakT))
		}
	}

	thresholdEvent("обороты (rpm)", t.findCol("rpm"), 3000, true)
	thresholdEvent("нагрузка (load), %", t.findCol("load"), 70, true)
	thresholdEvent("MAP, кПа", t.findCol("map"), 90, true)
	thresholdEvent("температура ОЖ, °C", t.findCol("coolant"), 95, true)
	thresholdEvent("напряжение (voltage)", t.findCol("voltage"), 13.5, false)

	return b.String()
}

func (t *table) summarize(name string, buckets int) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s: %d числовых строк", name, len(t.rows))
	if t.valid[t.timeIdx] {
		fmt.Fprintf(&b, ", t=%.1f..%.1f сек", t.timeAt(0), t.timeAt(len(t.rows)-1))
	}
	b.WriteString("\nСтолбцы (min / max / среднее / p90):\n")
	for i, h := range t.header {
		if i == t.timeIdx || !t.valid[i] {
			continue
		}
		s := calcStats(t.column(i))
		fmt.Fprintf(&b, "  %s: %s / %s / %s / %s\n", h, fnum(s.Min), fnum(s.Max), fnum(s.Mean), fnum(s.P90))
	}
	if key := t.keyColumns(6); len(key) > 0 {
		b.WriteString("Ресемплинг (средние по интервалам):\n")
		b.WriteString(t.resampleText(key, buckets))
	}
	if ev := t.eventsText(); ev != "" {
		b.WriteString("События:\n")
		b.WriteString(ev)
	}
	return b.String()
}

func summarizeCSV(name, raw string) string {
	if strings.TrimSpace(raw) == "" {
		return name + ": нет данных\n"
	}
	t, err := parseTable(raw)
	if err != nil {
		return fmt.Sprintf("%s: не удалось разобрать (%v)\n", name, err)
	}
	return t.summarize(name, 40) + "\n"
}

func pearson(a, b []float64) (float64, int) {
	n := len(a)
	if len(b) < n {
		n = len(b)
	}
	var sumX, sumY, sumXY, sumX2, sumY2 float64
	cnt := 0
	for i := 0; i < n; i++ {
		if math.IsNaN(a[i]) || math.IsNaN(b[i]) {
			continue
		}
		x, y := a[i], b[i]
		sumX += x
		sumY += y
		sumXY += x * y
		sumX2 += x * x
		sumY2 += y * y
		cnt++
	}
	if cnt < 10 {
		return 0, cnt
	}
	fn := float64(cnt)
	num := fn*sumXY - sumX*sumY
	den := math.Sqrt((fn*sumX2 - sumX*sumX) * (fn*sumY2 - sumY*sumY))
	if den == 0 {
		return 0, cnt
	}
	return num / den, cnt
}

func (t *table) perSecondMean(idx int) []float64 {
	secs := int(t.timeAt(len(t.rows)-1)) + 1
	if secs <= 0 || secs > 86400 {
		return nil
	}
	sums := make([]float64, secs)
	cnts := make([]int, secs)
	for i := 0; i < len(t.rows); i++ {
		if idx >= len(t.rows[i]) || math.IsNaN(t.rows[i][idx]) {
			continue
		}
		s := int(t.timeAt(i))
		if s < 0 || s >= secs {
			continue
		}
		sums[s] += t.rows[i][idx]
		cnts[s]++
	}
	out := make([]float64, secs)
	for i := range out {
		if cnts[i] > 0 {
			out[i] = sums[i] / float64(cnts[i])
		} else {
			out[i] = math.NaN()
		}
	}
	return out
}
