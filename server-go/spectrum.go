package main

import (
	"encoding/binary"
	"fmt"
	"math"
	"sort"
	"strings"
)

type spectrumFrame struct {
	dom      float64
	lowRatio float64
	centroid float64
	rms      float64
}

func wavSamples(b []byte) ([]float64, int) {
	pcm, ok := parseWav(b)
	if !ok || pcm.frames <= 0 || pcm.channels <= 0 {
		return nil, pcm.rate
	}
	out := make([]float64, pcm.frames)
	for i := 0; i < pcm.frames; i++ {
		var sum float64
		for c := 0; c < pcm.channels; c++ {
			idx := pcm.dataStart + (i*pcm.channels+c)*2
			if idx+1 >= len(b) {
				break
			}
			sum += float64(int16(binary.LittleEndian.Uint16(b[idx:idx+2]))) / 32768.0
		}
		out[i] = sum / float64(pcm.channels)
	}
	return out, pcm.rate
}

func fftInPlace(re, im []float64) {
	n := len(re)
	j := 0
	for i := 1; i < n; i++ {
		bit := n >> 1
		for ; j&bit != 0; bit >>= 1 {
			j ^= bit
		}
		j ^= bit
		if i < j {
			re[i], re[j] = re[j], re[i]
			im[i], im[j] = im[j], im[i]
		}
	}
	for length := 2; length <= n; length <<= 1 {
		ang := -2 * math.Pi / float64(length)
		wr, wi := math.Cos(ang), math.Sin(ang)
		for i := 0; i < n; i += length {
			cr, ci := 1.0, 0.0
			for k := 0; k < length/2; k++ {
				ur, ui := re[i+k], im[i+k]
				vr := re[i+k+length/2]*cr - im[i+k+length/2]*ci
				vi := re[i+k+length/2]*ci + im[i+k+length/2]*cr
				re[i+k], im[i+k] = ur+vr, ui+vi
				re[i+k+length/2], im[i+k+length/2] = ur-vr, ui-vi
				ncr := cr*wr - ci*wi
				ci = cr*wi + ci*wr
				cr = ncr
			}
		}
	}
}

func wavSpectrumFrames(samples []float64, rate int) []spectrumFrame {
	const win = 8192
	if rate <= 0 || len(samples) < win {
		return nil
	}
	w := make([]float64, win)
	for i := range w {
		w[i] = 0.5 - 0.5*math.Cos(2*math.Pi*float64(i)/float64(win-1))
	}
	re := make([]float64, win)
	im := make([]float64, win)
	binHz := float64(rate) / float64(win)
	hop := rate
	var frames []spectrumFrame
	for start := 0; start+win <= len(samples); start += hop {
		var energy float64
		for i := 0; i < win; i++ {
			v := samples[start+i] * w[i]
			re[i], im[i] = v, 0
			energy += v * v
		}
		fftInPlace(re, im)
		var low, total, wsum, dom, domMag float64
		for k := 1; k < win/2; k++ {
			f := float64(k) * binHz
			if f < 20 || f > 2000 {
				continue
			}
			mag := math.Hypot(re[k], im[k])
			total += mag
			wsum += mag * f
			if f <= 200 {
				low += mag
			}
			if f <= 400 && mag > domMag {
				domMag, dom = mag, f
			}
		}
		ratio, centroid := 0.0, 0.0
		if total > 0 {
			ratio = low / total
			centroid = wsum / total
		}
		frames = append(frames, spectrumFrame{dom: dom, lowRatio: ratio, centroid: centroid, rms: math.Sqrt(energy / float64(win))})
	}
	return frames
}

func medianOf(v []float64) float64 {
	if len(v) == 0 {
		return 0
	}
	s := append([]float64(nil), v...)
	sort.Float64s(s)
	return s[len(s)/2]
}

func rangeOf(v []float64) (float64, float64) {
	if len(v) == 0 {
		return 0, 0
	}
	s := append([]float64(nil), v...)
	sort.Float64s(s)
	return s[0], s[len(s)-1]
}

func audioSpectrumText(wav []byte, obd *table) string {
	samples, rate := wavSamples(wav)
	frames := wavSpectrumFrames(samples, rate)
	if len(frames) < 3 {
		return ""
	}
	var rpmPerSec []float64
	if obd != nil {
		if ci := obd.findCol("rpm"); ci >= 0 && obd.valid[ci] {
			rpmPerSec = obd.perSecondMean(ci)
		}
	}
	var loadDom, loadLow, loadCen, idleDom, idleLow, idleCen, orders []float64
	for i, f := range frames {
		rpm := 0.0
		if i < len(rpmPerSec) {
			rpm = rpmPerSec[i]
		}
		if rpm >= 1500 {
			loadDom = append(loadDom, f.dom)
			loadLow = append(loadLow, f.lowRatio)
			loadCen = append(loadCen, f.centroid)
			if rpm > 0 && f.dom > 0 {
				orders = append(orders, f.dom/(rpm/60.0))
			}
		} else if rpm > 0 && rpm < 900 {
			idleDom = append(idleDom, f.dom)
			idleLow = append(idleLow, f.lowRatio)
			idleCen = append(idleCen, f.centroid)
		}
	}
	var b strings.Builder
	b.WriteString("Акустический спектр (FFT, окно 8192, шаг 1 с; полосы 20–200 Гц и 20–2000 Гц):\n")
	if len(loadDom) > 0 {
		lo, hi := rangeOf(loadDom)
		fmt.Fprintf(&b, "- Эпизоды нагрузки (n=%d): доминирующая частота медиана %.0f Гц (диапазон %.0f–%.0f Гц); доля энергии 20–200 Гц %.2f; спектральный центроид %.0f Гц.\n",
			len(loadDom), medianOf(loadDom), lo, hi, medianOf(loadLow), medianOf(loadCen))
	} else {
		b.WriteString("- Эпизоды нагрузки: недостаточно секунд с оборотами >1500.\n")
	}
	if len(idleDom) > 0 {
		fmt.Fprintf(&b, "- Холостой ход (n=%d): доминирующая частота медиана %.0f Гц; доля энергии 20–200 Гц %.2f; центроид %.0f Гц.\n",
			len(idleDom), medianOf(idleDom), medianOf(idleLow), medianOf(idleCen))
	}
	if len(orders) > 0 {
		lo, hi := rangeOf(orders)
		fmt.Fprintf(&b, "- Отношение частоты к оборотам f/(rpm/60) в нагрузке: медиана %.2f (диапазон %.2f–%.2f). Постоянство этого отношения указывало бы на вращающийся узел; разброс — на резонанс.\n",
			medianOf(orders), lo, hi)
	}
	return b.String()
}
