package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"time"
)

type ChatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type DeepSeek struct {
	http    *http.Client
	baseURL string
	apiKey  string
	model   string
}

func NewDeepSeek(baseURL, apiKey, model string) *DeepSeek {
	return &DeepSeek{
		http:    &http.Client{Timeout: 15 * time.Minute},
		baseURL: strings.TrimRight(baseURL, "/"),
		apiKey:  apiKey,
		model:   model,
	}
}

func (d *DeepSeek) Enabled() bool {
	return d != nil && d.apiKey != "" && d.baseURL != ""
}

func (d *DeepSeek) Chat(ctx context.Context, messages []ChatMessage, jsonMode bool) (string, error) {
	body := map[string]any{
		"model":    d.model,
		"messages": messages,
		"stream":   false,
	}
	if jsonMode {
		body["response_format"] = map[string]string{"type": "json_object"}
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.baseURL+"/chat/completions", bytes.NewReader(payload))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+d.apiKey)

	resp, err := d.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		var wrap struct {
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		if json.Unmarshal(data, &wrap) == nil && wrap.Error.Message != "" {
			return "", fmt.Errorf("%s", wrap.Error.Message)
		}
		return "", fmt.Errorf("DeepSeek HTTP %s: %s", resp.Status, strings.TrimSpace(string(data)))
	}

	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(data, &out); err != nil {
		return "", err
	}
	if len(out.Choices) == 0 {
		return "", fmt.Errorf("DeepSeek вернул пустой ответ")
	}
	return out.Choices[0].Message.Content, nil
}

type Transcriber struct {
	http    *http.Client
	baseURL string
	apiKey  string
	model   string
	lang    string
}

func (t *Transcriber) Enabled() bool {
	return t != nil && t.baseURL != "" && t.model != ""
}

func (t *Transcriber) Transcribe(ctx context.Context, filename string, content []byte) (string, error) {
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	if err := w.WriteField("model", t.model); err != nil {
		return "", err
	}
	if t.lang != "" {
		if err := w.WriteField("language", t.lang); err != nil {
			return "", err
		}
	}
	part, err := w.CreateFormFile("file", filename)
	if err != nil {
		return "", err
	}
	if _, err := part.Write(content); err != nil {
		return "", err
	}
	if err := w.Close(); err != nil {
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, t.baseURL+"/audio/transcriptions", &buf)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", w.FormDataContentType())
	if t.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+t.apiKey)
	}

	resp, err := t.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP %s: %s", resp.Status, strings.TrimSpace(string(data)))
	}
	var out struct {
		Text string `json:"text"`
	}
	if err := json.Unmarshal(data, &out); err != nil {
		return "", err
	}
	return strings.TrimSpace(out.Text), nil
}
