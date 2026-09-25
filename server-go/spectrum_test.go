package main

import (
	"encoding/binary"
	"math"
	"testing"
)

func makeWavBytes(samples []float64, rate int) []byte {
	dataSize := len(samples) * 2
	b := make([]byte, 44+dataSize)
	copy(b[0:], "RIFF")
	binary.LittleEndian.PutUint32(b[4:], uint32(36+dataSize))
	copy(b[8:], "WAVE")
	copy(b[12:], "fmt ")
	binary.LittleEndian.PutUint32(b[16:], 16)
	binary.LittleEndian.PutUint16(b[20:], 1)
	binary.LittleEndian.PutUint16(b[22:], 1)
	binary.LittleEndian.PutUint32(b[24:], uint32(rate))
	binary.LittleEndian.PutUint32(b[28:], uint32(rate*2))
	binary.LittleEndian.PutUint16(b[32:], 2)
	binary.LittleEndian.PutUint16(b[34:], 16)
	copy(b[36:], "data")
	binary.LittleEndian.PutUint32(b[40:], uint32(dataSize))
	for i, s := range samples {
		v := int16(math.Max(-32768, math.Min(32767, s*32767)))
		binary.LittleEndian.PutUint16(b[44+i*2:], uint16(v))
	}
	return b
}

func TestSpectrumDominantFrequency(t *testing.T) {
	rate := 48000
	n := rate * 3
	samples := make([]float64, n)
	for i := range samples {
		samples[i] = 0.5 * math.Sin(2*math.Pi*80*float64(i)/float64(rate))
	}
	b := makeWavBytes(samples, rate)
	pcm, r := wavSamples(b)
	if r != rate {
		t.Fatalf("rate=%d", r)
	}
	frames := wavSpectrumFrames(pcm, rate)
	if len(frames) < 2 {
		t.Fatalf("frames=%d", len(frames))
	}
	for i, f := range frames {
		if math.Abs(f.dom-80) > 12 {
			t.Fatalf("frame %d dom=%.1f, want ~80", i, f.dom)
		}
	}
}
