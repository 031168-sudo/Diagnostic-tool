package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var vinPattern = regexp.MustCompile(`^[A-HJ-NPR-Z0-9]{17}$`)

const vinYearAlphabet = "ABCDEFGHJKLMNPRSTVWXY123456789"

var wmiMakes = map[string]string{
	"JHM": "HONDA", "1HG": "HONDA", "2HG": "HONDA", "3HG": "HONDA", "5FN": "HONDA", "5J6": "HONDA", "SHH": "HONDA",
	"JTD": "TOYOTA", "JTE": "TOYOTA", "JTM": "TOYOTA", "JTN": "TOYOTA", "JTL": "TOYOTA", "4T1": "TOYOTA", "4T3": "TOYOTA",
	"5TD": "TOYOTA", "5TE": "TOYOTA", "5TF": "TOYOTA", "2T1": "TOYOTA", "2T2": "TOYOTA", "2T3": "TOYOTA", "SB1": "TOYOTA", "NMT": "TOYOTA",
	"JTH": "LEXUS", "5YM": "LEXUS",
	"JN1": "NISSAN", "JN8": "NISSAN", "JNK": "NISSAN", "JN6": "NISSAN", "1N4": "NISSAN", "1N6": "NISSAN", "3N1": "NISSAN",
	"5N1": "NISSAN", "5N6": "NISSAN", "SJN": "NISSAN", "SJK": "NISSAN", "VSK": "NISSAN",
	"JM1": "MAZDA", "JM3": "MAZDA", "4F2": "MAZDA", "4F4": "MAZDA", "1YV": "MAZDA", "3MZ": "MAZDA", "JMZ": "MAZDA",
	"JA3": "MITSUBISHI", "JA4": "MITSUBISHI", "4A3": "MITSUBISHI", "4A4": "MITSUBISHI", "MM8": "MITSUBISHI",
	"JF1": "SUBARU", "JF2": "SUBARU", "4S3": "SUBARU", "4S4": "SUBARU",
	"JS2": "SUZUKI", "JS3": "SUZUKI", "5S3": "SUZUKI", "TSM": "SUZUKI", "MA3": "SUZUKI",

	"KMH": "HYUNDAI", "KM8": "HYUNDAI", "5NM": "HYUNDAI", "5NP": "HYUNDAI", "5NT": "HYUNDAI", "NLH": "HYUNDAI", "TMA": "HYUNDAI",
	"KNA": "KIA", "KNB": "KIA", "KNC": "KIA", "KND": "KIA", "KNE": "KIA", "KNF": "KIA", "KNG": "KIA", "5XY": "KIA", "5XX": "KIA",
	"KLA": "GM KOREA", "KL1": "GM KOREA", "KL4": "GM KOREA", "KL7": "GM KOREA", "KL8": "GM KOREA",

	"1G1": "CHEVROLET", "1GC": "CHEVROLET", "2G1": "CHEVROLET", "3G1": "CHEVROLET",
	"1G4": "BUICK", "1G6": "CADILLAC", "1GK": "GMC",
	"1FA": "FORD", "1FB": "FORD", "1FC": "FORD", "1FD": "FORD", "2FM": "FORD", "2FT": "FORD", "3FA": "FORD",
	"WF0": "FORD", "WF1": "FORD", "NM0": "FORD",
	"1C3": "CHRYSLER", "2C3": "CHRYSLER", "3C4": "CHRYSLER", "1C4": "JEEP", "1J4": "JEEP", "1J8": "JEEP",
	"1B3": "DODGE", "1B7": "DODGE",

	"5UX": "BMW", "4US": "BMW", "WBA": "BMW", "WBS": "BMW",
	"WVW": "VOLKSWAGEN", "WV1": "VOLKSWAGEN", "WV2": "VOLKSWAGEN", "1VW": "VOLKSWAGEN", "3VW": "VOLKSWAGEN",
	"WVG": "VOLKSWAGEN", "WV5": "VOLKSWAGEN", "WV6": "VOLKSWAGEN", "WV7": "VOLKSWAGEN", "WV8": "VOLKSWAGEN",
	"WAU": "AUDI", "WUA": "AUDI", "TRU": "AUDI", "WA1": "AUDI",
	"WDB": "MERCEDES-BENZ", "WDD": "MERCEDES-BENZ", "WDC": "MERCEDES-BENZ", "4JG": "MERCEDES-BENZ", "W1K": "MERCEDES-BENZ", "W1N": "MERCEDES-BENZ",
	"WP0": "PORSCHE", "WP1": "PORSCHE",
	"W0L": "OPEL", "W0V": "OPEL", "W0A": "OPEL",

	"VF1": "RENAULT", "VF6": "RENAULT",
	"VF3": "PEUGEOT",
	"VF7": "CITROEN",
	"ZFA": "FIAT", "ZAR": "ALFA ROMEO", "ZLA": "LANCIA", "ZFF": "FERRARI", "ZAM": "MASERATI",
	"YV1": "VOLVO", "YV4": "VOLVO",
	"YS3": "SAAB",
	"SAL": "LAND ROVER", "SAJ": "JAGUAR",
	"TMB": "SKODA",
	"VSS": "SEAT",

	"XTA": "LADA", "XTT": "UAZ",
}

type VINResult struct {
	VIN          string `json:"vin"`
	Valid        bool   `json:"valid"`
	Make         string `json:"make"`
	Model        string `json:"model"`
	Year         string `json:"year"`
	Engine       string `json:"engine"`
	Fuel         string `json:"fuel"`
	Transmission string `json:"transmission"`
	Drive        string `json:"drive"`
	Body         string `json:"body"`
	Plant        string `json:"plant"`
	Country      string `json:"country"`
	Region       string `json:"region"`
	WMI          string `json:"wmi"`
	Source       string `json:"source"`
	Message      string `json:"message"`
	Error        string `json:"error"`
}

type vpicItem struct {
	Make              string `json:"Make"`
	Model             string `json:"Model"`
	ModelYear         string `json:"ModelYear"`
	Trim              string `json:"Trim"`
	Series            string `json:"Series"`
	EngineModel       string `json:"EngineModel"`
	DisplacementL     string `json:"DisplacementL"`
	EngineCylinders   string `json:"EngineCylinders"`
	FuelTypePrimary   string `json:"FuelTypePrimary"`
	TransmissionStyle string `json:"TransmissionStyle"`
	DriveType         string `json:"DriveType"`
	BodyClass         string `json:"BodyClass"`
	PlantCity         string `json:"PlantCity"`
	PlantCountry      string `json:"PlantCountry"`
	ErrorCode         string `json:"ErrorCode"`
	ErrorText         string `json:"ErrorText"`
}

type vpicResponse struct {
	Results []vpicItem `json:"Results"`
}

type vinCache struct {
	mu sync.Mutex
	m  map[string]VINResult
}

func newVINCache() *vinCache {
	return &vinCache{m: map[string]VINResult{}}
}

func (c *vinCache) get(key string) (VINResult, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	v, ok := c.m[key]
	return v, ok
}

func (c *vinCache) put(key string, value VINResult) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.m[key] = value
}

func normalizeVIN(raw string) (string, bool) {
	v := strings.ToUpper(strings.TrimSpace(raw))
	v = strings.NewReplacer(" ", "", "-", "", ".", "", "\t", "").Replace(v)
	if !vinPattern.MatchString(v) {
		return v, false
	}
	return v, true
}

func vinRegion(ch byte) string {
	switch {
	case ch >= 'A' && ch <= 'H':
		return "Africa"
	case ch == 'J':
		return "Japan"
	case ch == 'K':
		return "Korea"
	case ch == 'L':
		return "China"
	case ch >= 'M' && ch <= 'R':
		return "Asia"
	case ch >= 'S' && ch <= 'Z':
		return "Europe"
	case ch == '1' || ch == '4' || ch == '5':
		return "North America"
	case ch == '2':
		return "Canada"
	case ch == '3':
		return "Mexico"
	case ch == '6':
		return "Oceania"
	case ch == '7':
		return "New Zealand"
	case ch == '8' || ch == '9':
		return "South America"
	}
	return ""
}

func vinModelYear(ch byte) string {
	i := strings.IndexByte(vinYearAlphabet, ch)
	if i < 0 {
		return ""
	}
	base := 1980 + i
	best := base
	for y := base; y <= time.Now().Year()+1; y += 30 {
		best = y
	}
	return strconv.Itoa(best)
}

func wmiMake(wmi string) string {
	if len(wmi) < 3 {
		return ""
	}
	return wmiMakes[strings.ToUpper(wmi[:3])]
}

func describeEngine(displacement, cylinders, model string) string {
	parts := []string{}
	if d := strings.TrimSpace(displacement); d != "" {
		if f, err := strconv.ParseFloat(d, 64); err == nil && f > 0 {
			parts = append(parts, strconv.FormatFloat(f, 'f', 1, 64)+"L")
		}
	}
	if c := strings.TrimSpace(cylinders); c != "" && c != "0" {
		parts = append(parts, c+"-cyl")
	}
	base := strings.Join(parts, " ")
	if m := strings.TrimSpace(model); m != "" {
		if base != "" {
			base += " "
		}
		base += "(" + m + ")"
	}
	return base
}

func (s *Server) decodeVINOnline(ctx context.Context, vin string) (vpicItem, error) {
	url := "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/" + vin + "?format=json"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return vpicItem{}, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "diagnostic-tool-server")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return vpicItem{}, err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return vpicItem{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return vpicItem{}, fmt.Errorf("NHTSA HTTP %s", resp.Status)
	}

	var body vpicResponse
	if err := json.Unmarshal(data, &body); err != nil {
		return vpicItem{}, err
	}
	if len(body.Results) == 0 {
		return vpicItem{}, fmt.Errorf("декодер не вернул данных")
	}
	return body.Results[0], nil
}

func mergeOnline(r *VINResult, it vpicItem) {
	r.Make = firstNonEmpty(strings.TrimSpace(it.Make), r.Make)
	r.Model = firstNonEmpty(strings.TrimSpace(it.Model), r.Model)
	r.Year = firstNonEmpty(strings.TrimSpace(it.ModelYear), r.Year)
	r.Engine = firstNonEmpty(describeEngine(it.DisplacementL, it.EngineCylinders, it.EngineModel), r.Engine)
	r.Fuel = firstNonEmpty(strings.TrimSpace(it.FuelTypePrimary), r.Fuel)
	r.Transmission = firstNonEmpty(strings.TrimSpace(it.TransmissionStyle), r.Transmission)
	r.Drive = firstNonEmpty(strings.TrimSpace(it.DriveType), r.Drive)
	r.Body = firstNonEmpty(strings.TrimSpace(it.BodyClass), r.Body)
	r.Plant = firstNonEmpty(strings.TrimSpace(it.PlantCity), r.Plant)
	r.Country = firstNonEmpty(strings.TrimSpace(it.PlantCountry), r.Country)
}

func (s *Server) handleVIN(w http.ResponseWriter, r *http.Request) {
	raw := r.PathValue("vin")
	vin, ok := normalizeVIN(raw)
	if !ok {
		writeJSON(w, http.StatusBadRequest, VINResult{
			VIN:   strings.ToUpper(strings.TrimSpace(raw)),
			Valid: false,
			Error: "VIN должен содержать 17 символов A-Z и 0-9 (без I, O, Q)",
		})
		return
	}

	if cached, found := s.vinCache.get(vin); found {
		writeJSON(w, http.StatusOK, cached)
		return
	}

	result := VINResult{
		VIN:    vin,
		Valid:  true,
		WMI:    vin[:3],
		Region: vinRegion(vin[0]),
		Year:   vinModelYear(vin[9]),
	}

	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()

	decodeErr := error(nil)
	decoded, err := s.decodeVINOnline(ctx, vin)
	if err != nil {
		decodeErr = err
	} else {
		mergeOnline(&result, decoded)
	}

	if result.Make == "" {
		result.Make = wmiMake(result.WMI)
	}

	switch {
	case result.Make != "" && result.Model != "":
		result.Source = "vpic"
	case result.Make != "":
		result.Source = "wmi"
		result.Message = "Марка определена по коду WMI. Модель и характеристики этой сборки онлайн-декодер NHTSA не вернул — заполните их вручную."
	default:
		result.Source = "offline"
		if decodeErr != nil {
			result.Message = "Онлайн-декодер NHTSA недоступен: " + decodeErr.Error()
		} else {
			result.Message = "Данные по этому VIN не найдены (вероятно, автомобиль не для рынка США). Укажите марку и характеристики вручную."
		}
	}

	log.Printf("VIN %s -> make=%q model=%q year=%q source=%s", vin, result.Make, result.Model, result.Year, result.Source)

	s.vinCache.put(vin, result)
	writeJSON(w, http.StatusOK, result)
}
