# Diagnostic Tool server

Отдельный backend проекта «Диагностика машины». Он не использует серверы, домены или инфраструктуру других проектов.

## Возможности

- принимает `audio.wav`, `obd.csv`, `gps.csv`, `sensors.csv`, `session.json` и данные автомобиля;
- анализирует WAV и синхронизированные OBD/GPS/датчики через OpenAI;
- возвращает прогресс и дополнительные вопросы;
- принимает ответы пользователя;
- поддерживает последующий диалог с ИИ через `/v1/diagnostics/:id/chat`;
- хранит состояние диагностики на диске;
- отдаёт итоговое техническое заключение.

## Развёртывание

Это самостоятельный сервер. Для него нужен отдельный VPS/хост с Docker или Node.js 20+.

### Docker

```bash
cd server
cp .env.example .env
# указать OPENAI_API_KEY в .env
docker compose up -d --build
```

Проверка:

```text
GET http://<адрес-сервера>:8080/health
```

Должен вернуться JSON с `ok: true`.

### Без Docker

```bash
cd server
npm install
cp .env.example .env
node server.mjs
```

Ключ OpenAI хранится только на этом сервере и не попадает в APK.

## Подключение Android

Адрес API задаётся **только параметром сборки** `diagnosticApiUrl`. Например:

```bash
gradle assembleDebug -PdiagnosticApiUrl=https://<ваш-отдельный-домен>
```

В исходниках APK нет адреса стороннего проекта. Если параметр не задан, приложение явно сообщает, что сервер диагностики не настроен.

Backend находится в `Diagnostic-tool/server` и предназначен только для приложения диагностики.
