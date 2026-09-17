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

func wavMetrics(b []byte) WavMetrics {
	if len(b) < 44 || string(b[0:4]) != "RIFF" {
		return WavMetrics{Error: "WAV header not recognised"}
	}
	channels := int(binary.LittleEndian.Uint16(b[22:24]))
	rate := int(binary.LittleEndian.Uint32(b[24:28]))
	bits := int(binary.LittleEndian.Uint16(b[34:36]))

	dataStart, dataSize := -1, 0
	for pos := 12; pos+8 <= len(b); {
		id := string(b[pos : pos+4])
		size := int(binary.LittleEndian.Uint32(b[pos+4 : pos+8]))
		if id == "data" {
			dataStart = pos + 8
			dataSize = size
			if dataSize > len(b)-dataStart {
				dataSize = len(b) - dataStart
			}
			break
		}
		pos += 8 + size + (size & 1)
	}
	if dataStart < 0 || bits != 16 {
		return WavMetrics{Channels: channels, Rate: rate, Bits: bits, Error: "Only PCM16 WAV is analysed"}
	}

	bpf := channels * 2
	if bpf <= 0 || rate <= 0 {
		return WavMetrics{Channels: channels, Rate: rate, Bits: bits, Error: "Invalid WAV parameters"}
	}
	frames := dataSize / bpf
	duration := float64(frames) / float64(rate)
	stride := int(math.Max(1, math.Floor(float64(rate)*0.5)))

	var rmsSum, peak float64
	var count, zc int
	var prev float64
	first := true
	for f := 0; f < frames; f += stride {
		idx := dataStart + f*bpf
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
		Channels:      channels,
		Rate:          rate,
		Bits:          bits,
		DurationSec:   roundTo(duration, 2),
		SampledPoints: count,
		RMS:           roundTo(math.Sqrt(rmsSum/math.Max(1, float64(count))), 5),
		Peak:          roundTo(peak, 5),
		ZeroCrossings: zc,
	}
}

func roundTo(v float64, digits int) float64 {
	p := math.Pow(10, float64(digits))
	return math.Round(v*p) / p
}
