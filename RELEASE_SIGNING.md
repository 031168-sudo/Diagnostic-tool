# Release-подпись и как ключ попал на сервер

Документ описывает release-keystore проекта и процедуру, которой ключ был перенесён на домашний сервер (Ubuntu, `gmpony@gmpony.alfanomy.ru`), чтобы релизные сборки подписывались одним и тем же ключом.

> В файле **нет** паролей и самих ключей — только пути и команды. Пароль лежит только в `keystore.properties` на устройствах/сервере.

## 1. Что за ключ

- Это release-keystore для публикации приложения (`applicationId = ru.alfanomy.diagnostictool`) в RuStore.
- Создан утилитой `keytool` из JDK 17.
- Параметры: alias `alfanomy-diagnostic`, тип `RSA` 2048 бит, срок действия 10000 дней, владелец `CN=Alfa Diagnostic, O=Alfanomy, C=RU`.
- Отпечатки (нужны магазину):
  - SHA-256: `64:46:95:8C:BD:A0:32:80:F9:58:84:30:D4:76:0F:33:28:FB:4C:61:C2:F5:F9:1C:0A:31:AD:0C:81:9B:EC:EB`
  - SHA-1: `A9:DB:5F:83:B9:2C:6C:C8:A7:51:0B:D0:5F:24:C8:70:00:19:AC:7A`

## 2. Где что лежит

| | Путь |
|---|---|
| Keystore (Windows) | `C:\Users\user\.android-keystores\alfanomy-diagnostic-release.jks` |
| Keystore (сервер) | `/home/gmpony/.android-keystores/alfanomy-diagnostic-release.jks` |
| Пароли (Windows) | `Diagnostic-tool\keystore.properties` (в `.gitignore`) |
| Пароли (сервер) | `/home/gmpony/Diagnostic-tool/keystore.properties` (в `.gitignore`) |

Keystore специально хранится **вне репозитория** (`~/.android-keystores`), а `keystore.properties` закрыт `.gitignore`, поэтому в git не попадает.

## 3. Как создавался ключ (Windows)

```powershell
$keytool = "$env:JAVA_HOME\bin\keytool.exe"
& $keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\.android-keystores\alfanomy-diagnostic-release.jks" `
  -alias alfanomy-diagnostic -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <пароль> -keypass <пароль> `
  -dname "CN=Alfa Diagnostic, O=Alfanomy, C=RU"
```

Пароль был сгенерирован случайно и записан в `keystore.properties` (в git не коммитится).

## 4. Как ключ попал на сервер

Перенос выполнялся с рабочего компьютера по SSH/SCP (вход по ключу `~/.ssh/id_ed25519` на порт 22 домена `gmpony.alfanomy.ru`).

**Шаг 1. Каталог для ключа на сервере:**
```bash
ssh gmpony@gmpony.alfanomy.ru "mkdir -p /home/gmpony/.android-keystores"
```

**Шаг 2. Копирование самого ключа (без пароля в командной строке):**
```bash
scp "C:\Users\user\.android-keystores\alfanomy-diagnostic-release.jks" \
    gmpony@gmpony.alfanomy.ru:/home/gmpony/.android-keystores/alfanomy-diagnostic-release.jks
```

**Шаг 3. Создание `keystore.properties` на сервере.**
Сначала на сервер во временный файл кладётся локальный `keystore.properties` (в нём пароль), затем в нём подменяется путь `storeFile` на серверный и файл переносится в корень проекта:
```bash
scp "C:\Users\user\Documents\Default Project\Diagnostic-tool\keystore.properties" \
    gmpony@gmpony.alfanomy.ru:/tmp/ks.properties

ssh gmpony@gmpony.alfanomy.ru "
  sed 's#^storeFile=.*#storeFile=/home/gmpony/.android-keystores/alfanomy-diagnostic-release.jks#' \
      /tmp/ks.properties > /home/gmpony/Diagnostic-tool/keystore.properties &&
  rm -f /tmp/ks.properties &&
  chmod 600 /home/gmpony/Diagnostic-tool/keystore.properties \
            /home/gmpony/.android-keystores/alfanomy-diagnostic-release.jks
"
```

Итоговый `/home/gmpony/Diagnostic-tool/keystore.properties`:
```
storeFile=/home/gmpony/.android-keystores/alfanomy-diagnostic-release.jks
storePassword=<пароль>
keyAlias=alfanomy-diagnostic
keyPassword=<пароль>
```

## 5. Как подпись подключается к сборке

В `app/build.gradle.kts` при конфигурации project читается `keystore.properties` из корня проекта (`rootProject.file("keystore.properties")`), и на его основе настраивается `signingConfigs.release`, который назначен типу сборки `release`. Поэтому отдельная настройка под каждый компьютер/сервер не нужна — достаточно, чтобы рядом с проектом лежал корректный `keystore.properties`.

## 6. Проверка, что всё работает

На сервере:
```bash
cd /home/gmpony/Diagnostic-tool
ANDROID_HOME=/home/gmpony/Android/Sdk \
  /home/gmpony/opt/gradle/gradle-8.11.1/bin/gradle :app:assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
$(ls -d /home/gmpony/Android/Sdk/build-tools/*/ | sort -V | tail -1)apksigner \
  verify --print-certs "$APK"
```
Результат: сборка `BUILD SUCCESSFUL`, а `apksigner` показывает владельца `CN=Alfa Diagnostic, O=Alfanomy, C=RU` и SHA-256 `64:46:95:8C:...:EC:EB` — тот же ключ.

## 7. Безопасность и обслуживание

- **Никогда** не коммитить keystore и пароли. `.gitignore` исключает `keystore.properties`, `*.jks`, `*.keystore`.
- Сделать **резервную копию** keystore и пароля (менеджер паролей). Потеря ключа = невозможность выпускать обновления в RuStore.
- `git clean -fdx` в `~/Diagnostic-tool` удалит `keystore.properties` (сам keystore в `~/.android-keystores` не пострадает) — файл можно восстановить по шагу 3.
- При смене сервера/пользователя повторить шаги 4 (и при необходимости 3).
