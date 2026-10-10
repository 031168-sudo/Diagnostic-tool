package main

import (
	"encoding/binary"
	"math"
)

type WavMetrics struct {
	Channels      int     `json:"channels"`
	Rate          int     `json:"rate"`
	Bits          int     `json:"bits"`
	DurationSec   float64 `json:"durationSec"`
	SampledPoints int     `json:"sampledPoints"`
	RMS           float64 `json:"rms"`
	Peak          float64 `json:"peak"`
	ZeroCrossings int     `json:"zeroCrossings"`
	Error         string  `json:"error,omitempty"`
}

type wavPCM struct {
	dataStart int
	frames    int
	bpf       int
	rate      int
	channels  int
	bits      int
}

func parseWav(b []byte) (wavPCM, bool) {
	if len(b) < 44 || string(b[0:4]) != "RIFF" {
		return wavPCM{}, false
	}
	pcm := wavPCM{
		channels: int(binary.LittleEndian.Uint16(b[22:24])),
		rate:     int(binary.LittleEndian.Uint32(b[24:28])),
		bits:     int(binary.LittleEndian.Uint16(b[34:36])),
	}
	for pos := 12; pos+8 <= len(b); {
		id := string(b[pos : pos+4])
		size := int(binary.LittleEndian.Uint32(b[pos+4 : pos+8]))
		if id == "data" {
			pcm.dataStart = pos + 8
			dataSize := size
			if dataSize > len(b)-pcm.dataStart {
				dataSize = len(b) - pcm.dataStart
			}
			pcm.bpf = pcm.channels * 2
			if pcm.bpf <= 0 || pcm.rate <= 0 {
				return pcm, false
			}
			pcm.frames = dataSize / pcm.bpf
			return pcm, pcm.bits == 16
		}
		pos += 8 + size + (size & 1)
	}
	return pcm, false
}

func wavMetrics(b []byte) WavMetrics {
	pcm, ok := parseWav(b)
	if pcm.channels == 0 && pcm.rate == 0 && pcm.bits == 0 {
		return WavMetrics{Error: "WAV header not recognised"}
	}
	if !ok {
		return WavMetrics{Channels: pcm.channels, Rate: pcm.rate, Bits: pcm.bits, Error: "Only PCM16 WAV is analysed"}
	}

	duration := float64(pcm.frames) / float64(pcm.rate)
	stride := int(math.Max(1, math.Floor(float64(pcm.rate)*0.5)))

	var rmsSum, peak float64
	var count, zc int
	var prev float64
	first := true
	for f := 0; f < pcm.frames; f += stride {
		idx := pcm.dataStart + f*pcm.bpf
		if idx+1 >= len(b) {
			break
		}
		x := float64(int16(binary.LittleEndian.Uint16(b[idx:idx+2]))) / 32768.0
		rmsSum += x * x
		if math.Abs(x) > peak {
			peak = math.Abs(x)
		}
		if !first && (x >= 0) != (prev >= 0) {
			zc++
		}
		first = false
		prev = x
		count++
	}

	return WavMetrics{
		Channels:      pcm.channels,
		Rate:          pcm.rate,
		Bits:          pcm.bits,
		DurationSec:   roundTo(duration, 2),
		SampledPoints: count,
		RMS:           roundTo(math.Sqrt(rmsSum/math.Max(1, float64(count))), 5),
		Peak:          roundTo(peak, 5),
		ZeroCrossings: zc,
	}
}

func wavPerSecondRMS(b []byte) []float64 {
	pcm, ok := parseWav(b)
	if !ok || pcm.frames <= 0 {
		return nil
	}
	secs := int(math.Ceil(float64(pcm.frames) / float64(pcm.rate)))
	if secs <= 0 || secs > 86400 {
		return nil
	}
	sums := make([]float64, secs)
	cnts := make([]int, secs)
	for f := 0; f < pcm.frames; f++ {
		idx := pcm.dataStart + f*pcm.bpf
		if idx+1 >= len(b) {
			break
		}
		x := float64(int16(binary.LittleEndian.Uint16(b[idx:idx+2]))) / 32768.0
		s := f / pcm.rate
		if s >= secs {
			s = secs - 1
		}
		sums[s] += x * x
		cnts[s]++
	}
	out := make([]float64, secs)
	for i := range out {
		if cnts[i] > 0 {
			out[i] = math.Sqrt(sums[i] / float64(cnts[i]))
		} else {
			out[i] = math.NaN()
		}
	}
	return out
}

func roundTo(v float64, digits int) float64 {
	p := math.Pow(10, float64(digits))
	return math.Round(v*p) / p
}
