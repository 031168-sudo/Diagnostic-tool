package main

import (
	"context"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

var (
	existNameRe    = regexp.MustCompile(`(?s)<h1[^>]*class="car-info__car-name"[^>]*>\s*([^<]+?)\s*</h1>`)
	existYearRe    = regexp.MustCompile(`(?s)car-info__car-years"[^>]*>\s*(\d{4})`)
	existDisplRe   = regexp.MustCompile(`^\d\.\d(L)?$`)
	existDriveRe   = regexp.MustCompile(`^(4WD|AWD|2WD|FWD|RWD|4X4|4X2)$`)
)

const browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

var errNotInExist = errors.New("автомобиль не найден в каталоге exist")

type existCar struct {
	Make   string
	Model  string
	Year   string
	Engine string
	Drive  string
}

func parseExistName(name string) existCar {
	var c existCar
	fields := strings.Fields(name)
	if len(fields) == 0 {
		return c
	}
	c.Make = strings.ToUpper(fields[0])
	modelParts := []string{}
	for _, f := range fields[1:] {
		up := strings.ToUpper(f)
		switch {
		case existDriveRe.MatchString(up):
			c.Drive = up
		case existDisplRe.MatchString(up):
			c.Engine = strings.TrimSuffix(up, "L") + "L"
		default:
			modelParts = append(modelParts, f)
		}
	}
	c.Model = strings.Join(modelParts, " ")
	return c
}

func (s *Server) lookupExist(ctx context.Context, vin string) (existCar, error) {
	u := "https://www.exist.ru/Price/Empty.aspx?q=" + url.QueryEscape(vin)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return existCar{}, err
	}
	req.Header.Set("User-Agent", browserUserAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return existCar{}, err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return existCar{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return existCar{}, errNotInExist
	}

	html := string(data)
	nameMatch := existNameRe.FindStringSubmatch(html)
	log.Printf("exist %s -> status=%d len=%d found=%v", vin, resp.StatusCode, len(data), nameMatch != nil)
	if nameMatch == nil {
		return existCar{}, errNotInExist
	}

	car := parseExistName(strings.TrimSpace(nameMatch[1]))
	if car.Model == "" && car.Make == "" {
		return existCar{}, errNotInExist
	}
	if ym := existYearRe.FindStringSubmatch(html); ym != nil {
		car.Year = ym[1]
	}
	return car, nil
}
