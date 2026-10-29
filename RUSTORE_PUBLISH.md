# Публикация приложения в RuStore — что нужно сделать

Полный план: доработка приложения, карточка в RuStore и подключение монетизации (Pay SDK + серверные вебхуки).

## Что уже есть

- `targetSdk = 35`, `minSdk = 26` — соответствует требованиям.
- Иконка приложения (`mipmap/ic_launcher`) есть.
- Сервер работает по HTTPS (`diag.alfanomy.ru`, Go, порт 44096, Caddy) — используем его для вебхуков и гейтинга.
- Приложение — чистый Kotlin/Java (без нативных библиотек), 64-битность не проблема.

---

## Этап 0. Что подготовить заранее

- Сейчас `applicationId = com.example.diagnostictool` и подпись **debug**. Для RuStore нужен **release-keystore**, а `applicationId` + подпись должны быть **те же**, что у опубликованного APK (иначе **Pay SDK не заработает**).
- Сразу переименовать в `ru.alfanomy.*` (как у вашего «Чистильщика»). Минус: смена ломает установку/данные на текущих телефонах.
- Сервер `diag.alfanomy.ru` уже есть — на нём делаем вебхуки и гейтинг.

## Этап 1. RuStore Консоль и монетизация

1. Регистрация разработчика (самозанятый/ИП/ООО), договор, реквизиты.
2. Включить **монетизацию** для юрлиц: `Монетизация → включить` (или раздел для нерезидентов).
3. Комиссия **~3,35%** при подключённом Pay SDK (точную цифру смотреть в Консоли).

## Этап 2. Публикация приложения

### 2.1. Технические доработки
- [ ] **Release-подпись.** `signingConfigs.release` в `app/build.gradle.kts`, `keystore.properties` хранить **вне git** (добавить в `.gitignore`).
- [ ] **Release build type.** Собрать подписанный **release APK/AAB** с R8/ProGuard (при необходимости — правила keep).
- [ ] **Без cleartext.** В release HTTP запрещать; у нас cleartext только в debug-манифесте — это правильно.
- [ ] **Версия.** Поднять `versionCode`/`versionName`.
- [ ] **applicationId.** Сменить на `ru.alfanomy.diagnostictool` (или иной `ru.alfanomy.*`).
- [ ] Проверить release-сборку на устройстве.

### 2.2. Карточка
- [ ] Иконка **512×512** (png/jpg, фон **без прозрачности**).
- [ ] **Минимум 3 скриншота**.
- [ ] Категория, возрастной рейтинг.
- [ ] Краткое и полное описание, контакты.
- [ ] **Политика конфиденциальности (URL)** — обязательна.
- [ ] Отправить на модерацию (`Приложения → Добавить приложение → Загрузить версию`).

## Этап 3. Создать товары в Консоли

`Приложение → Монетизация`:
- **Подписка** `Pro` (месяц) — тип `SUBSCRIPTION`.
- **Пакет** `10 диагностик` — тип `CONSUMABLE_PRODUCT` (потребляемый, можно покупать повторно).
- Опционально B2B-подписка.

Скопировать **productId** каждого — они нужны в коде.

## Этап 4. Подключить Pay SDK (Android)

`settings.gradle.kts` (репозитории):
```kotlin
maven { url = uri("https://nexus-external.rustore.ru/repository/maven-rustore-exposed") }
```

`app/build.gradle.kts`:
```kotlin
implementation(platform("ru.rustore.sdk:bom:2026.08.01"))
implementation("ru.rustore.sdk:pay")
```

`AndroidManifest.xml` — id приложения из Консоли и схема deeplink:
```xml
<meta-data android:name="console_app_id_value" android:value="@string/CONSOLE_APPLICATION_ID" />
<meta-data android:name="sdk_pay_scheme_value" android:value="@string/APP_SCHEME" />
```
Плюс intent-filter со своей схемой (`android:scheme="ru.alfanomy.diag"`) и `android:launchMode="singleTop"`.

## Этап 5. Код покупки в приложении

```kotlin
// каталог (цены берутся из RuStore)
RuStorePayClient.instance.getProductInteractor()
    .getProducts(listOf(ProductId("pro_month"), ProductId("pack10")))
    .addOnSuccessListener { products -> /* показать цены */ }
    .addOnFailureListener { /* error */ }

// покупка (подписки — только ONE_STEP)
RuStorePayClient.instance.getPurchaseInteractor()
    .purchase(ProductPurchaseParams(ProductType.SUBSCRIPTION, ProductId("pro_month")))
```

Обязательно:
- обработать deeplink — `IntentInteractor.proceedIntent(intent)`;
- учесть покупки, незавершённые с прошлого запуска (`getPurchases`, затем `updateAcknowledgementState`).

## Этап 6. Сервер (Go) — главное

1. В Консоли: `Монетизация → Уведомления на сервер` → URL `https://diag.alfanomy.ru/rustore/notify` (https обязателен).
2. Получить **ключ AES-256** (показывается один раз) → в `.env` сервера.
3. Обработчик: принять тело, **расшифровать AES-256**, проверить покупку через RuStore API, начислить права по `user_id`.
4. **Гейтинг** в `handleUpload` (`server-go/main.go:176`): перед анализом проверять лимит/подписку и списывать попытку. **Сервер — источник истины.**

## Этап 7. Аккаунты (реализовано: минимальный аккаунт + код переноса)

Решение: свой аккаунт **без пароля** (не VK ID — чтобы не мешало переезду в Google Play). Личность не зависит от магазина; подписка/кредиты хранятся на нашем сервере по `user_id`.

- При первом запуске приложение анонимно регистрируется → получает `account_id` + `account_token` (хранит локально, `AccountStore`).
- Перенос на другой телефон: **код переноса** (8 символов, действует 20 минут, одноразовый). Вводится на новом устройстве в разделе «Аккаунт».
- Эндпоинты сервера (Go): `POST /v1/account/register`, `GET /v1/account/me`, `POST /v1/account/transfer/create`, `POST /v1/account/transfer/redeem`. Хранилище — `accounts.json` в `DATA_DIR`.
- При покупке в RuStore Pay в `user_id` передаётся наш `account_id` → вебхук начисляет кредиты/подписку на него.

Ограничение варианта: потерял код и оба устройства — потерял баланс. Позже можно добавить телефон+SMS как дополнительный способ.

## Этап 8. Тестирование (Sandbox)

В Консоли: `Уведомления на сервер → Для тестовых платежей → Подключить`, отдельный URL, кнопка **Проверить**. Прогнать: покупка, возврат, истечение подписки.

## Этап 9. Релиз

Опубликовать новую версию с биллингом, включить реальные уведомления, убрать sandbox.

---

## Разрешения и конфиденциальность

- [ ] Обоснование опасных разрешений:
  - `RECORD_AUDIO` — запись звука для диагностики;
  - `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — GPS и BLE-сканирование;
  - `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (+ legacy `BLUETOOTH`, `BLUETOOTH_ADMIN`) — связь с OBD-адаптером ELM327;
  - `INTERNET` — отправка сессии на сервер и обращение к ИИ.
- [ ] Указать, какие данные собираются (звук, OBD, GPS, датчики) и куда отправляются (наш сервер → DeepSeek API).
- [ ] API-ключ модели хранится только на сервере (в приложение не попадает).
- [ ] Описать хранение/удаление сессий на устройстве.

## Что нужно от владельца

- [ ] Аккаунт RuStore (и подпись разработчика).
- [ ] Хранилище для release-keystore и его пароль.
- [ ] Тексты, скриншоты, иконка 512×512.
- [ ] URL политики конфиденциальности.
- [ ] Решение по смене `applicationId` (`ru.alfanomy.*`).
- [ ] productId товаров и ключ AES-256 из Консоли.
