# Дорожная карта реализации VPN-сервиса и трекер прогресса

> **Назначение документа**: Этот файл служит единым источником правды о текущем состоянии реализации проекта для разработчиков и AI-агентов. Любой агент может продолжить работу со следующего невыполненного шага `[ ]`, строго следуя архитектурным инвариантам проекта.

---

## 1. Архитектурные правила и инварианты (Обязательно к соблюдению)

1. **Gradle DSL**: Только **Groovy DSL** (`build.gradle`, `settings.gradle`). Kotlin DSL (`.kts`) **запрещен**.
2. **Java & Spring Boot**: Использовать **Java 25** и **Spring Boot 4.1.1+**.
3. **База данных**: Только **PostgreSQL 17** с миграциями через **Flyway 11**. Никакого Redis (устранен как избыточный).
4. **Финансы и баланс**: Баланс пользователя и расчеты ведутся **только в целочисленных микро-USDT** (`1 USDT = 1,000,000 micro-USDT`, тип `Long/int64`). `Float` и `Double` для денежных сумм категорически запрещены.
5. **Протокол и обход блокировок ТСПУ**:
   - Основной транспорт: **VLESS + XHTTP + Reality** с браузерным fingerprint (`firefox` или `edge`, никогда не random).
   - XMUX включен всегда.
   - Серверная политика: смена параметров происходит через сервер (`transport_policy`), а не перекомпиляцию клиентов.
   - **Smart Reconnect Backoff**: Пауза при первом сбое — 15–20 секунд. Смена ноды — только после 2–3 неудач. Это предотвращает удлинение блокировки ТСПУ с 120 до 600 секунд.
6. **Приватность**:
   - Никаких логов посещенных URL, IP-адресов назначения или DNS-запросов (`access.log` в Xray отключен).
   - Хранятся только агрегированные счетчики потребления байтов для квот.
7. **Тестирование**: Все изменения должны сопровождаться unit/integration тестами. Общая проверка:
   ```bash
   ./gradlew test && (cd agent && npm test) && (cd web && npm run build) && ./gradlew :server:bootJar -x test
   ```

---

## 2. Общий статус фаз проекта

| Фаза | Описание | Статус | Прогресс |
|:---|:---|:---:|:---:|
| **Фаза 0** | Каркас: Монорепо, Protobuf, Flyway, Docker Compose, CI/CD, Spring Boot 4+ | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 1** | Связность: Xray XHTTP+Reality, Node Agent (TS/gRPC), Stats API, Backoff | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 2** | Пользователи, подписки, квоты, пулы нод, VLESS-генерация, TRC-20 инвойсы | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 3** | Динамический роутинг, автокарантин, TronGrid сканер, клейм хеша ("Я оплатил") | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 4** | Telegram-бот: вебхук, Telegram Stars (`XTR`), 15% рефералка, автодиагностика | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 5** | Веб-интерфейс: Лендинг, Личный кабинет, Telegram Mini App, i18n (ru/en) | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 6** | Админ-панель: операционный дашборд, пользователи, ноды, политики, аудит-лог | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 7** | Android MVP: Java + `libXray` (.aar), Material 3, логика подключения, конформанс | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 8** | Desktop Windows/macOS: Electron + React, системный прокси, автообновление | **ТЕКУЩАЯ** | 0% `[ ]` |
| **Фаза 9** | Транспортная гибкость: CDN-ноды, gRPC fallback, резервные пулы | **ПРЕДСТОИТ** | 0% `[ ]` |
| **Фаза 10** | Закалка: резервные домены API, DoH, защита от перечисления РКН, релиз | **ПРЕДСТОИТ** | 0% `[ ]` |

---

## 3. Детализация выполненных шагов

### [x] Фаза 0: Инфраструктурный каркас
- [x] Создана структура монорепо (`server/`, `agent/`, `proto/`, `web/`, `scripts/`, `docs/`).
- [x] Настроен Gradle 8.12 с **Groovy DSL** (`build.gradle`, `settings.gradle`).
- [x] Настроен **Spring Boot 4.1.1** с таргетом на **Java 25**.
- [x] Описан протокол взаимодействия gRPC / Protobuf v3: [`proto/agent_service.proto`](file:///Users/roman.struchev/git/vpn/vpn/proto/agent_service.proto).
- [x] Развернуты миграции Flyway 11 с таблицами: `users`, `subscriptions`, `nodes`, `device_node_keys`, `crypto_invoices`, `balance_entries`, `transport_policies`, `conn_telemetry`.
- [x] Настроен `docker-compose.yml` на чистый **PostgreSQL 17** без Redis.
- [x] Настроен GitHub Actions CI/CD workflow (`.github/workflows/ci.yml`).

### [x] Фаза 1: Узел (Node Agent) и транспорт XHTTP + Reality
- [x] Реализован Node.js TypeScript демон [`agent/src/index.ts`](file:///Users/roman.struchev/git/vpn/vpn/agent/src/index.ts).
- [x] Генерация конфигурации Xray [`agent/src/xray/config-builder.ts`](file:///Users/roman.struchev/git/vpn/vpn/agent/src/xray/config-builder.ts): VLESS inbounds, XHTTP settings, Reality (PBK, dest, serverNames).
- [x] Сбор статистики через gRPC Xray Stats API (`StatsService/QueryStats`) с флагом `reset: true` ([`agent/src/xray/stats-collector.ts`](file:///Users/roman.struchev/git/vpn/vpn/agent/src/xray/stats-collector.ts)).
- [x] Двунаправленный стрим `ConnectStream` агент <-> сервер с реконсиляцией по SHA-256 хешу конфигурации.
- [x] Экспоненциальный Backoff переподключения с джиттером.
- [x] Bash-скрипт автоустановки ноды [`scripts/install-node.sh`](file:///Users/roman.struchev/git/vpn/vpn/scripts/install-node.sh) (sysctl bbr/fq, Xray-core, systemd сервис).
- [x] Dockerfile агента [`agent/Dockerfile`](file:///Users/roman.struchev/git/vpn/vpn/agent/Dockerfile).

### [x] Фаза 2: Аккаунты, квоты, подписки и инвойсы
- [x] Реализована служба управления нодами и токенами бутстрапа [`NodeManagementService.java`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/service/NodeManagementService.java).
- [x] Генерация уникальных ключей UUID для тройки `(user, device, node)` ([`DeviceNodeKeyRepository`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/repository/DeviceNodeKeyRepository.java)).
- [x] Планировщик контроля квот [`QuotaEnforcementTask.java`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/task/QuotaEnforcementTask.java): деактивация клиентов при исчерпании лимита и пуш изменений на ноды.
- [x] Экспорт VLESS ссылки с параметрами XHTTP + Reality (`/api/v1/subscription/export/{token}`).
- [x] Инвойсы TRC-20 с плавающим окном допуска (±0.0004 USDT) для однозначной идентификации депозита без коллизий.

### [x] Фаза 3: Динамический роутинг, автокарантин, сканер блокчейна
- [x] Динамический подбор нод с учетом провайдера (ASN) и региона клиента ([`RoutingService.java`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/service/RoutingService.java)).
- [x] Сбор телеметрии обрывов соединения `conn_telemetry` через REST API.
- [x] Автоматический перевод деградирующих нод в пул `quarantine`.
- [x] Фоновый планировщик сканирования TronGrid API [`BlockchainScannerTask.java`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/task/BlockchainScannerTask.java) для TRC-20 USDT.
- [x] Эндпоинт самообслуживания **«Я оплатил, вот хеш»** `POST /api/v1/user/billing/claim-tx` с защитой от повторного клейма.

### [x] Фаза 4: Telegram-бот и платежи Stars
- [x] Контроллер вебхука Telegram [`TelegramBotController.java`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/controller/TelegramBotController.java).
- [x] Сервис Telegram [`TelegramBotService.java`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/service/TelegramBotService.java):
  - Автоонбординг по `/start`, выдача 3-дневного триала, привязка реферера.
  - Команда `/vpn`: выдача статуса подписки и VLESS ссылки.
  - Инвойсы Telegram Stars (`XTR`) по курсу $0.02 / Star.
  - Обработка `pre_checkout_query` и `successful_payment`.
  - Автоматическое начисление **15% реферального вознаграждения** пригласителю с Telegram-уведомлением.
  - Команда `/diag` с честной диагностикой белых списков оператора.

### [x] Фаза 5: Веб-клиент и Telegram Mini App
- [x] React 18 + Vite 6 + Tailwind CSS в [`web/`](file:///Users/roman.struchev/git/vpn/vpn/web/).
- [x] Полная двуязычная поддержка (`ru` / `en`) в [`i18n.ts`](file:///Users/roman.struchev/git/vpn/vpn/web/src/i18n.ts).
- [x] Лендинг с позиционированием по приватности и анти-DPI свойствам протокола ([`LandingView.tsx`](file:///Users/roman.struchev/git/vpn/vpn/web/src/components/LandingView.tsx)).
- [x] Личный кабинет ([`DashboardView.tsx`](file:///Users/roman.struchev/git/vpn/vpn/web/src/components/DashboardView.tsx)):
  - Прогресс-бар расхода квоты трафика.
  - Копирование VLESS ссылки и всплывающий **QR-код** для сканирования.
  - Управление устройствами: добавление и моментальный отзыв.
  - Пополнение баланса TRC-20 USDT с QR-кодом и форма «Я оплатил, вот хеш».
  - Покупка и продление тарифов с баланса.
  - Реферальный блок с персональной ссылкой.
- [x] Telegram Mini App: авто-авторизация через `Telegram.WebApp.initData` и интеграция в бота.
- [x] Gradle-таска `copyWebDist` для автоматической сборки фронтенда внутрь Spring Boot JAR (`resources/static`).

### [x] Фаза 6: Административная панель и операционный дашборд
- [x] Расширен [`AdminController.java`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/controller/AdminController.java):
  - `GET /api/v1/admin/dashboard`: метрики в реальном времени (пользователи, активные подписки, балансы, трафик, ноды, деградация сети `operator × region × transport`).
  - Управление пользователями: список, ручная корректировка баланса (`MANUAL_ADJUSTMENT`), блокировка (`BLOCKED`) со сбросом конфигурации, продление подписок.
  - Управление нодами: смена пула (`paid`, `trial`, `quarantine`), изменение статуса (`ONLINE`, `DRAINING`, etc.), форсированный синк, отправка команд агенту.
  - Управление правилами транспорта: просмотр и создание `TransportPolicy`.
  - Ручная реконсиляция депозитов `POST /api/v1/admin/crypto/reconcile`.
- [x] Тесты административного контроллера в [`AdminControllerTest.java`](file:///Users/roman.struchev/git/vpn/vpn/server/src/test/java/com/vpn/server/AdminControllerTest.java) (14 unit тестов).

### [x] Фаза 7: Нативный Android-клиент (MVP)
*Требования по PLAN.md §5 и §9*. Отдельный Gradle-проект [`android/`](file:///Users/roman.struchev/git/vpn/vpn/android) (Groovy DSL, не часть корневого multi-project — как `agent/` и `web/`), см. [`android/README.md`](file:///Users/roman.struchev/git/vpn/vpn/android/README.md).
- [x] **Язык и стек**: Нативная Java 17, AGP 8.7.3, Material 3 (`Theme.Material3.DayNight`) с обязательной тёмной темой и брендовыми токенами из `web/tailwind.config.js`.
- [x] **Ядро VPN**: Интеграция `libXray` v26.9.9 (.aar) через [`XrayInvoker`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/vpn/xray/XrayInvoker.java) (raw `invoke(String)` JSON envelope, API version 3) и [`XrayVpnService`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/vpn/XrayVpnService.java) под Android `VpnService`; TUN fd передаётся через `env["xray.tun.fd"]` конфига Xray-core (контракт `proxy/tun` для Android), сокеты защищены через `DialerController`/`protect()`. `.aar` не коммитится в git (~95MB); подтягивается [`scripts/fetch-libxray.sh`](file:///Users/roman.struchev/git/vpn/vpn/android/scripts/fetch-libxray.sh).
- [x] **Логика подключения**:
  - Клиент получает ноды/политику через `GET /api/v1/client/config` и VLESS-ссылки через `GET /api/v1/user/subscription/links` (парсинг в [`VlessUri`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/vpn/xray/VlessUri.java), конфиг собирается в [`XrayConfigFactory`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/vpn/xray/XrayConfigFactory.java)).
  - Стейт-машина [`ConnectionStateMachine`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/vpn/state/ConnectionStateMachine.java): `Disconnected → Connecting → Connected → Reconnecting → Error` + отдельное состояние `OperatorBlocked`.
  - **Честный экран блокировки**: [`CensorshipProbeService`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/vpn/CensorshipProbeService.java) — HTTP-пробы к gosuslugi.ru и внешнему тест-хосту вне туннеля, решение в чистой функции [`CensorshipVerdict`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/vpn/CensorshipVerdict.java).
  - **Smart Reconnect Backoff** в [`ReconnectBackoffPolicy`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/vpn/ReconnectBackoffPolicy.java): пауза 15–20с (клэмп), ротация ноды только после 2–3 неудач подряд, fingerprint (`firefox`/`edge`) зафиксирован на сессию.
  - Встроенный DoH через `okhttp-dnsoverhttps` для REST-клиента ([`DohDns`](file:///Users/roman.struchev/git/vpn/vpn/android/app/src/main/java/com/vpn/android/api/DohDns.java)) и через `dns`-блок Xray-конфига для системного DNS внутри туннеля.
- [x] **UI**: экраны логина/регистрации (email+password, тот же `/api/v1/auth/*` что у веба), подключения (квота, honest-блокировка), устройств, профиля — Material 3, RecyclerView, ViewBinding.
- [x] **CI / Тесты**: 29 unit-тестов (JUnit, без Robolectric/эмулятора) на state machine, backoff-инварианты, vless-парсинг, censorship-вердикт, xray-config factory — `android/app/src/test/`. `lintDebug` чист от ошибок. Job `android` добавлен в [`.github/workflows/gradlew-publish-and-deploy.yml`](file:///Users/roman.struchev/git/vpn/vpn/.github/workflows/gradlew-publish-and-deploy.yml).
- [x] Собранный debug APK вручную проверен на эмуляторе (Pixel API 34, arm64): установка, запуск, рендер Material 3 UI, переключение режимов логина, валидация формы — без крашей.
- **Не сделано в этом MVP**: нет on-device/инструментальных тестов для самого VPN-туннеля (проверено ревью кода + модульными тестами чистой логики, но не подключением к реальной ноде); нет входа через Telegram Mini App (только email/password); подпись релиза и публикация в Google Play — вне рамок MVP.

### [ ] Фаза 8: Desktop-клиент для Windows и macOS
*Требования по PLAN.md §5*:
- [ ] **Стек**: Electron-vite + React + TypeScript.
- [ ] **Дизайн**: Использование дизайн-токенов и компонентов из веб-кабинета (единый брендинг).
- [ ] **Режим MVP**: Системный прокси (SOCKS5/HTTP через локальный `xray-core`). Без TUN-драйверов, без прав администратора и без необходимости сертификата подписи кода (схема из `aurapad`).
- [ ] **Автообновление без сертификата**: `electron-updater` + GitHub Releases (`latest.yml`, `mac.notarize: false`, NSIS инсталлятор для Windows).

### [ ] Фаза 9: Транспортная гибкость и стрессоустойчивость
- [ ] Поддержка резервного транспорта gRPC + TLS при деградации XHTTP.
- [ ] Поддержка CDN-нод для обхода жестких блокировок по IP.
- [ ] Автоматическая ротация резервных пулов адресов.

### [ ] Фаза 10: Закалка перед запуском
- [ ] Пул резервных доменов для API сервера и DoH-резолвинг.
- [ ] Алгоритм детекта перечисления нод (anti-scraping): карантин аккаунтов, запрашивающих подписку со множества подозрительных IP.
- [ ] Ограничение скорости на уровне пробных нод (`fq_codel` / `tc`).
- [ ] Подготовка к верификации Google Play Developer.

---

## 5. Шпаргалка для разработчика / агента

### Запуск и проверка сборки
```bash
# 1. Серверные тесты (JUnit 5 + Spring Boot)
./gradlew test

# 2. Тесты агента ноды (Vitest)
cd agent && npm test

# 3. Сборка фронтенда (Vite + TypeScript)
cd web && npm run build

# 4. Сборка единого fat-JAR
./gradlew :server:bootJar -x test

# 5. Android: unit-тесты (JDK 17-21 required for the Gradle 8.10 wrapper; see android/README.md)
cd android && ./scripts/fetch-libxray.sh && ./gradlew :app:testDebugUnitTest
```

### Запуск локальной инфраструктуры
```bash
# Запуск PostgreSQL 17
docker compose up -d postgres
```
