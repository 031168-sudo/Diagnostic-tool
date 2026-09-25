# AGENTS.md

## Рабочий процесс

- **Всегда собираем локально.** Сборка идёт в песочнице, а не только в GitHub Actions.
- **Коммит/пуш — только по явной команде пользователя** («коммить», «сохрани в GitHub» и т.п.). Без команды изменения не коммитим.

## Локальная сборка Android

Gradle и Android SDK не в PATH. Использовать явные пути:

```bash
ANDROID_HOME=/home/gmpony/Android/Sdk \
/home/gmpony/opt/gradle/gradle-8.11.1/bin/gradle :app:assembleDebug
```

- Gradle: `/home/gmpony/opt/gradle/gradle-8.11.1/bin/gradle`
- Android SDK: `/home/gmpony/Android/Sdk`
- Результат: `app/build/outputs/apk/debug/app-debug.apk`
- Офлайн-режим (быстрее, если кэш есть): добавить `--offline`

## Сервер (Go)

```bash
cd server-go && go test ./...
```

## GitHub Actions

`.github/workflows/build.yml` собирает debug APK в облаке как артефакт
`DiagnosticTool-debug`. Это бэкап/CI, а не замена локальной сборке.
