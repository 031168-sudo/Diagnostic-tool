# Alfa Diagnostic server

Backend for the in-app AI diagnostic dialogue.

## Flow

1. Android uploads `audio.wav`, `obd.csv`, `gps.csv`, `sensors.csv`, `session.json` and vehicle data to `POST /v1/diagnostics`.
2. The server analyses the WAV locally, optionally transcribes speech with OpenAI, and sends the synchronized diagnostic data to the OpenAI Responses API.
3. The server returns a `question` state when the model needs an additional check or answer.
4. Android posts the answer to `POST /v1/diagnostics/:id/messages`.
5. When analysis is complete, `GET /v1/diagnostics/:id` contains the final conclusion text. Android renders that text into a PDF and stores it in the diagnostic history.

## Deployment

Node.js 20+ is recommended.

```bash
cd server
npm install
cp .env.example .env
# put the API key into .env
node server.mjs
```

The OpenAI key stays only on the server. It is deliberately not included in the Android application.

The Android client currently expects the API at:
`https://m.alfanomy.ru/diagnostic-api`

If the server is deployed at another address, change `BASE_URL` in `DiagnosticApi.kt` before the production build.
