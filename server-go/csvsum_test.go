package main

import (
	"encoding/binary"
	"fmt"
	"strings"
	"testing"
)

func TestSummarize(t *testing.T) {
	var b strings.Builder
	b.WriteString("t,rpm,speed,load,throttle,map,coolant,intake,voltage\n")
	for i := 0; i < 5000; i++ {
		tf := float64(i) / 10.0
		speed, rpm := 0.0, 800.0
		if tf > 200 {
			speed = (tf - 200) * 3
			if speed > 60 {
				speed = 60
			}
			rpm = 800 + speed*40
		}
		load := 20 + speed*0.8
		throttle := 5 + speed*0.5
		mp := 35 + speed*0.9
		fmt.Fprintf(&b, "%.1f,%.0f,%.1f,%.0f,%.1f,%.0f,85,22,14.2\n", tf, rpm, speed, load, throttle, mp)
	}
	tbl, err := parseTable(b.String())
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	out := tbl.summarize("OBD", 40)
	fmt.Println("----- summary start -----")
	fmt.Println(out)
	fmt.Println("----- summary end -----")
	fmt.Println("summary chars:", len(out))
	if len(out) > 8000 {
		t.Fatalf("summary too large: %d", len(out))
	}
	if !strings.Contains(out, "rpm") || !strings.Contains(out, "скорость") {
		t.Fatalf("summary missing expected content")
	}
	if !strings.Contains(out, "стартов с места") {
		t.Fatalf("expected start event detection")
	}
}

func TestWavTimeline(t *testing.T) {
	b := makeWav(48000, 2, 0.3)
	rms := wavPerSecondRMS(b)
	if len(rms) != 2 {
		t.Fatalf("expected 2 seconds, got %d", len(rms))
	}
	for i, v := range rms {
		if v <= 0 {
			t.Fatalf("second %d rms=%v", i, v)
		}
	}
}

func makeWav(rate, secs int, amp float64) []byte {
	samples := rate * secs
	dataSize := samples * 2
	buf := make([]byte, 44+dataSize)
	copy(buf[0:], "RIFF")
	binary.LittleEndian.PutUint32(buf[4:], uint32(36+dataSize))
	copy(buf[8:], "WAVE")
	copy(buf[12:], "fmt ")
	binary.LittleEndian.PutUint32(buf[16:], 16)
	binary.LittleEndian.PutUint16(buf[20:], 1)
	binary.LittleEndian.PutUint16(buf[22:], 1)
	binary.LittleEndian.PutUint32(buf[24:], uint32(rate))
	binary.LittleEndian.PutUint32(buf[28:], uint32(rate*2))
	binary.LittleEndian.PutUint16(buf[32:], 2)
	binary.LittleEndian.PutUint16(buf[34:], 16)
	copy(buf[36:], "data")
	binary.LittleEndian.PutUint32(buf[40:], uint32(dataSize))
	v := int16(amp * 32767)
	for i := 0; i < samples; i++ {
		binary.LittleEndian.PutUint16(buf[44+i*2:], uint16(v))
	}
	return buf
}
