# Дорожная карта реализации VPN-сервиса и трекер прогресса

> **Назначение документа**: Этот файл служит единым источником правды о текущем состоянии реализации проекта для разработчиков и AI-агентов. Любой агент может продолжить работу со следующего невыполненного шага `[ ]`, строго следуя архитектурным инвариантам проекта.

---

## 1. Архитектурные правила и инварианты (Обязательно к соблюдению)

1. **Gradle DSL**: Только **Groovy DSL** (`build.gradle`, `settings.gradle`). Kotlin DSL (`.kts`) **запрещен**.
2. **Java & Spring Boot**: Использовать **Java 25** и **Spring Boot 4.1.1+**.
3. **База данных**: Только **PostgreSQL 17** с миграциями через **Flyway** (версия управляется BOM `spring-boot-dependencies` — на Spring Boot 4.1.1 это 12.4.0; зависимость — `org.springframework.boot:spring-boot-starter-flyway`, **не** голый `org.flywaydb:flyway-core`, см. Фазу 10 §«критические баги» — без стартера Spring-автоконфигурация Flyway не подключается и миграции молча не выполняются). Никакого Redis (устранен как избыточный).
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
| **Фаза 8** | Desktop Windows/macOS: Electron + React, системный прокси, автообновление | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 9** | Транспортная гибкость: CDN-ноды, gRPC fallback, резервные пулы | **ВЫПОЛНЕНО** | 100% `[x]` |
| **Фаза 10** | Закалка: резервные домены API, DoH, защита от перечисления РКН, релиз | **ВЫПОЛНЕНО** | 100% `[x]` |

Все 10 фаз из этой дорожной карты выполнены. Оставшаяся работа — не код, а операционные шаги (провижининг доменов/серверов, верификация Google Play аккаунта, юрлицо для iOS) и обычное сопровождение. См. §4 «Что сейчас в работе» — там нет открытых `[ ]` пунктов внутри фаз 0–10, только внешние по отношению к коду шаги, перечисленные в записях о Фазе 10.

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

### [x] Фаза 8: Desktop-клиент для Windows и macOS
*Требования по PLAN.md §5*. Отдельный npm-проект [`desktop/`](file:///Users/roman.struchev/git/vpn/vpn/desktop) (как `agent/` и `web/`), см. [`desktop/README.md`](file:///Users/roman.struchev/git/vpn/vpn/desktop/README.md).
- [x] **Стек**: Electron-vite 5 + React 18 + TypeScript, electron-builder + electron-updater.
- [x] **Дизайн**: тот же брендовый набор цветов, что в `web/tailwind.config.js` и Android `colors.xml` — значения вынесены в единый источник [`design-tokens/tokens.mjs`](file:///Users/roman.struchev/git/vpn/vpn/design-tokens/tokens.mjs) (Пост-Фаза-10, см. ниже); полноценного общего пакета `ui/` из PLAN.md §9 по-прежнему нет — web/desktop на React+Tailwind, Android нативный на Java/Material3, общего компонентного рантайма между ними и не может быть, только общие значения токенов.
- [x] **Режим MVP — системный прокси, не TUN**: локальный дочерний процесс `xray-core` (`Xray-core` releases, бинарь `xray`, подтягивается [`scripts/fetch-xray-core.mjs`](file:///Users/roman.struchev/git/vpn/vpn/desktop/scripts/fetch-xray-core.mjs), не коммитится в git) слушает SOCKS5+HTTP на `127.0.0.1`; ОС-прокси переключается через [`systemProxy.ts`](file:///Users/roman.struchev/git/vpn/vpn/desktop/src/main/proxy/systemProxy.ts) (`networksetup` на macOS, реестр `Internet Settings` + `rundll32`-рефреш на Windows, `gsettings` для GNOME на Linux).
- [x] **Логика подключения**: [`vpnController.ts`](file:///Users/roman.struchev/git/vpn/vpn/desktop/src/main/vpn/vpnController.ts) — тот же контракт, что и в Android-клиенте: `connectionState.ts` (стейт-машина + `OperatorBlocked`), `reconnectBackoffPolicy.ts` (15–20с, смена ноды после 2–3 неудач, фиксированный fingerprint на сессию), `censorshipVerdict.ts`/`censorshipProbe.ts` (честный экран блокировки), `xrayConfigFactory.ts` (VLESS+XHTTP+Reality, XMUX всегда включён, `dns`-блок с DoH для трафика через прокси).
- [x] **Автообновление без сертификата**: `electron-updater` + `publish: provider: github` в `package.json`, `mac.notarize: false`, NSIS для Windows — схема идентична `aurapad`.
- [x] **CI / Тесты**: unit-тесты (Vitest, framework-free `src/shared/`) — `desktop/test/`. Jobs `web` и `desktop` добавлены в [`.github/workflows/gradlew-publish-and-deploy.yml`](file:///Users/roman.struchev/git/vpn/vpn/.github/workflows/gradlew-publish-and-deploy.yml).
- [x] Реально собран и вручную проверен на этой машине: `npm run dev` (Electron-окно, вход, DevTools-логи IPC) и `npx electron-builder --mac --dir` (несигнированный `.app`, запущен как отдельный процесс, меню/иконка Dock показывают правильное имя `NextGen VPN`).
- **Не сделано в этом MVP**: нет TUN-режима (сознательно, см. `desktop/README.md`); нет DoH для собственных REST-запросов приложения (только для трафика внутри туннеля); Linux-прокси автоматизирован только для GNOME; автообновление не проверялось против реального GitHub Releases (релизов ещё не было). (Дедупликация значений дизайн-токенов между web/desktop/Android закрыта в Пост-Фазе-10.)

### [x] Фаза 9: Транспортная гибкость и стрессоустойчивость
- [x] **Резервный транспорт gRPC + Reality при деградации XHTTP** (не gRPC+голый TLS — см. [`docs/research/ru-blocking.md`](file:///Users/roman.struchev/git/vpn/vpn/docs/research/ru-blocking.md), где gRPC+Reality уже описан как «⚠️ запасной»; голая TLS ломает механику Reality). Прямые ноды (`type=direct`) теперь всегда слушают **два** inbound'а одновременно — XHTTP на 443 и gRPC на 8443 (`vpn.grpc-fallback.port`), с одними и теми же Reality-ключами и client UUID:
  - Протокол [`proto/vpn/agent/v1/agent.proto`](file:///Users/roman.struchev/git/vpn/vpn/proto/vpn/agent/v1/agent.proto): `ConfigSync.fallback_inbound`, `InboundConfig.grpc_settings`/`tls_settings`.
  - [`NodeManagementService.buildNodeConfigSync`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/service/NodeManagementService.java) строит оба inbound'а для прямых нод.
  - [`agent/src/xray/config-builder.ts`](file:///Users/roman.struchev/git/vpn/vpn/agent/src/xray/config-builder.ts) добавляет второй `vless-inbound-fallback` при наличии `fallbackInbound` (раньше поле `inbound.transport` вообще игнорировалось — это было мёртвым кодом).
  - `GET /api/v1/client/config` теперь отдаёт `grpcFallbackPort`/`grpcFallbackServiceName` на ноду ([`DynamicRoutingService`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/java/com/vpn/server/service/DynamicRoutingService.java)).
  - Оба клиента (Android, Desktop) реализуют переключение транспорта чистой логикой `TransportFallbackPolicy`: перебор всех нод на стартовом транспорте → при исчерпании переключение на второй → при исчерпании обоих — проба цензуры и честный экран блокировки (как раньше). Стартовый транспорт теперь учитывает `transport_policy.primaryTransport` (`RoutingConfigResponse.primaryTransport`, приходит с `GET /api/v1/client/config` с учётом region/operator/global-скоупа) — если админ выставит его в `GRPC` для конкретного региона/оператора, оба клиента стартуют сразу с gRPC+Reality (с защитой: если ни одна нода не отдаёт `grpcFallbackPort`, откат на XHTTP).
- [x] **Поддержка CDN-нод**: поле `Node.type='cdn'` уже существовало с Фазы 2, но реально **не работало** — при отключённой Reality inbound получал `security: 'none'`, то есть CDN-ноды отдавали VLESS без какого-либо TLS. Исправлено: CDN-ноды теперь получают настоящий TLS (`InboundConfig.tls_settings`, домен = `node.hostname`, путь к сертификату по конвенции `/etc/xray/certs/<hostname>/{fullchain,privkey}.pem`, настраивается через `vpn.cdn.cert-dir`). [`scripts/install-node.sh`](file:///Users/roman.struchev/git/vpn/vpn/scripts/install-node.sh) получил необязательный шаг `[7/7]` — certbot в standalone-режиме с `--deploy-hook`, синхронизирующим сертификат с этим путём при каждом продлении. Автоматического подбора/провижининга самого CDN (домен, DNS, выбор провайдera) — сознательно нет, см. `docs/research/ru-blocking.md` про ненадёжность Cloudflare в РФ и необходимость выбора «по замерам, а не по популярности».
- [x] **Автоматическая ротация резервных пулов**: новый пул `reserve` (просто значение `Node.pool`, без миграции схемы) — ноды-«дублёры», не отдаваемые клиентам (`DynamicRoutingService.getRoutingConfig` их фильтрует). При авто-карантине ноды (`checkAndQuarantineNode`, уже существовало с Фазы 3) сервис теперь ищет `reserve`-ноду в том же регионе (`NodeRepository.findByPoolAndRegionAndStatus`) и молча повышает её в пул, который освободила закарантиненная нода (`DynamicRoutingService.promoteReserveNode`). Новые client-ключи для повышенной ноды создаются лениво при следующем экспорте ссылок — тот же механизм, что уже используется для любой новой ноды.
- [x] **Тесты**: agent (+2, gRPC-инбаунд и CDN-TLS ветка в `config-builder.test.ts`), server (+4: `NodeManagementServiceTest` gRPC/CDN, `DynamicRoutingServiceTest` grpc-поля/reserve-promotion), Android (+7: `TransportFallbackPolicyTest` + `XrayConfigFactoryTest` grpc-кейсы, итого 36 unit-тестов), Desktop (+7: `transportFallbackPolicy.test.ts` + grpc-кейсы в `xrayConfigFactory.test.ts`, итого 35). Все зелёные, `./gradlew :server:test`, `npm test` (agent/desktop), `:app:testDebugUnitTest`+`:app:lintDebug`+`:app:assembleDebug` (android) прогнаны локально.
- **Не сделано**: gRPC-фолбэк не предлагается CDN-нодам (у них своя транспортная маскировка через CDN); нет автоматического подбора CDN-провайдера/домена; `reserve`-пул нужно наполнять вручную через существующий admin API (`POST /admin/nodes/{id}/pool`), автоматического «выращивания» резерва нет.

### [x] Фаза 10: Закалка перед запуском
- [x] **Пул резервных доменов для API сервера и DoH-резолвинг**: новый класс `ApiHostRotation` (чистая логика, есть и в Android — `api/ApiHostRotation.java`, и в Desktop — `src/shared/apiHostRotation.ts`) перебирает бэкап-домены при сетевой ошибке (не при обычном HTTP-ответе 4xx/5xx — это не проблема связности). Android уже имел DoH для своих REST-запросов с Фазы 7 (`DohDns.java`); Desktop получил его только сейчас — [`src/main/api/dohDispatcher.ts`](file:///Users/roman.struchev/git/vpn/vpn/desktop/src/main/api/dohDispatcher.ts) ставит глобальный `undici`-диспетчер, резолвящий хосты через DoH JSON API Cloudflare (не бинарный RFC 8484 — проще и без парсинга DNS-пакетов), с фолбэком на системный резолвер при сбое DoH.
- [x] **Защита от перечисления нод (anti-scraping)**: два независимых, но связанных изменения:
  1. **Исправлен реальный IDOR**: `GET /api/v1/subscription/export/{userId}` принимал сырой последовательный ID пользователя **без какой-либо аутентификации** — кто угодно мог перебирать `userId=1,2,3...` и вытаскивать VLESS-ключи любого пользователя на всех нодах. Теперь путь — непредсказуемый `UUID` (`users.subscription_token`, миграция [`V2__anti_enumeration.sql`](file:///Users/roman.struchev/git/vpn/vpn/server/src/main/resources/db/migration/V2__anti_enumeration.sql)); эндпоинт остаётся публичным намеренно (это подписочная ссылка для сторонних клиентов вроде v2rayNG, которые не умеют логиниться), но по непредсказуемому токену, а не по ID.
  2. **`AntiEnumerationService`**: фиксирует IP каждого запроса за ссылками подписки (и публичного `/export/{token}`, и авторизованного `/api/v1/user/subscription/links`) в новой таблице `subscription_access_log`; если один аккаунт тянут более чем с `vpn.anti-enum.max-distinct-ips` (по умолчанию 5) различных IP за `vpn.anti-enum.window-minutes` (по умолчанию 60) минут — паттерн из PLAN.md §6 («аккаунт тянет ссылку с многих IP»), — все VLESS-ключи этого аккаунта немедленно ротируются (`DeviceNodeKeyRepository.findByDeviceUserId` + новый UUID на каждый ключ), так что уже скачанный список нод протухает. Жёсткая блокировка аккаунта сознательно не используется — ложное срабатывание на мобильном интернете с частой сменой IP не должно банить платящего пользователя.
- [x] **Ограничение скорости на уровне пробных нод**: [`scripts/install-node.sh`](file:///Users/roman.struchev/git/vpn/vpn/scripts/install-node.sh) получил необязательный 4-й аргумент `trial_cap_mbps` — генерирует `/usr/local/bin/vpn-apply-trial-cap.sh` (автоопределение интерфейса через `ip route get`, `tc qdisc htb` с потолком + `fq_codel` внутри для честного разделения между потоками — ровно как в PLAN.md §4) и systemd-юнит `vpn-trial-cap.service`, применяющий его при каждой загрузке.
- [x] **Подготовка к верификации Google Play Developer**: новый [`docs/google-play-readiness.md`](file:///Users/roman.struchev/git/vpn/vpn/docs/google-play-readiness.md) — маппинг чеклиста из `stores-and-liability.md` на фактическое состояние кода, готовый (нуждающийся в заполнении плейсхолдеров) черновик политики конфиденциальности и построчный ответ для формы Data Safety, основанный на реальной модели данных, а не на шаблоне. Юрлицо/верификация личности — не код, см. `stores-and-liability.md`.
- [x] **Два критических инфраструктурных бага, найденных и исправленных по пути** (не входили в исходный чеклист Фазы 10, но напрямую относятся к «закалке перед запуском» — без них сервер не запускался бы на чистой БД в проде):
  1. **Flyway-миграции никогда реально не выполнялись.** `server/build.gradle` зависел от `org.flywaydb:flyway-core` напрямую, но Spring Boot 4.x вынес Flyway-автоконфигурацию из монолитного `spring-boot-autoconfigure` в отдельный модуль `org.springframework.boot:spring-boot-flyway` (тот же паттерн, что и `HibernateJpaConfiguration`, переехавший в `org.springframework.boot.hibernate.autoconfigure`). Без этого модуля Hibernate валидировал схему **до** того, как Flyway успевал её создать — падение на «missing table» при любом первом запуске на чистой БД. Юнит-тесты этого не ловили: тестовый профиль использует H2 с `ddl-auto=create-drop` и `flyway.enabled=false`. Обнаружено и исправлено только реальным запуском `./gradlew :server:bootRun` против настоящего Postgres 17 в Docker.
  2. **gRPC-сервер падал при старте** с `AbstractMethodError` — `grpc-netty-shaded` был явно закреплён на `1.71.0` в build.gradle, а `grpc-core`/`grpc-api` резолвились в `1.83.1` через BOM `io.grpc:grpc-bom`, который Spring Boot 4.1.1 подключает как часть `spring-boot-dependencies`. Разные релизы grpc в одном classpath — несовместимый ABI. Исправлено удалением локального закрепления версии для рантайм-зависимостей `io.grpc:*` (теперь полностью управляются BOM); версия для protoc-плагина `protoc-gen-grpc-java` остаётся явной (это отдельный, не управляемый Gradle-плагином-зависимостей артефакт) с комментарием держать её в синхроне.
  3. **Попутно найден и исправлен баг доступа**: `NodeManagementService.buildNodeConfigSync` не проверял `user.status` — пользователь, заблокированный админом (`BLOCKED`), сохранял рабочий VPN-доступ до истечения подписки. Теперь `hasActiveSub` дополнительно требует `"ACTIVE".equalsIgnoreCase(user.getStatus())`.
- [x] **Тесты**: server +5 (`NodeManagementServiceTest` blocked-user, `UserControllerTest` обновлён под новую сигнатуру, миграция и полный старт сервера реально проверены против Postgres 17 в Docker — 66 тестов всего), Android +4 (`ApiHostRotationTest`, 40 тестов всего), Desktop +4 (`apiHostRotation.test.ts`, 39 тестов всего). Все зелёные.
- [x] **Сквозная проверка на реальной инфраструктуре**: `docker compose up -d postgres` + `./gradlew :server:bootRun` — сервер стартовал целиком (HTTP на 8080, gRPC на 9090), регистрация пользователя через `POST /api/v1/auth/register` и получение `subscription_token`, `GET /api/v1/subscription/export/{старый numeric id}` корректно отклоняется, `GET /api/v1/subscription/export/{настоящий token}` доходит до бизнес-логики (400 «нет подписки» — ожидаемо для нового пользователя, не 403/404).
- **Не сделано**: провижининг реальных резервных доменов (нужны настоящие домены/DNS, это не код); листинг Google Play и хостинг политики конфиденциальности по реальному URL (плейсхолдеры в `docs/google-play-readiness.md`); верификация личности разработчика и юрлицо для iOS — организационные шаги вне кода.

### [x] Пост-Фаза-10: закрытие двух пробелов, обнаруженных при аудите готовой кодовой базы

Все 10 фаз уже были помечены выполненными; при сквозном ревью на предмет «а что по плану ещё не реализовано» нашлись два места, где сервер уже отдавал нужные данные (`RoutingConfigResponse.NodeInfo.id`, `RoutingConfigResponse.primaryTransport`), а оба клиента их игнорировали — не новая функциональность, а доведение уже описанного в Фазах 3/9 поведения до действительно рабочего состояния.

1. **Телеметрия клиентов всегда уходила с `nodeId=null`.** `DynamicRoutingService.recordTelemetry`/`checkAndQuarantineNode` были полностью готовы с Фазы 3 и реально завязаны на `nodeId`, но ни Android (`XrayVpnService.reportTelemetry`), ни Desktop (`VpnController.reportTelemetry`) никогда не передавали его — значит, автокарантин по клиентской телеметрии ни разу не срабатывал в проде, только по прямым сигналам агента ноды. Исправлено на обеих платформах одинаково: карта `host → nodeId`, построенная из `RoutingConfigResponse.nodes[]` (тот же паттерн, что уже использовался для карт gRPC-фолбэка), плюс важная тонкость — id упавшей ноды теперь захватывается **до** инкремента индекса текущей ноды в `handleFailure()`, иначе телеметрия ошибочно приписывалась бы уже следующей (ещё не опробованной) ноде.
2. **Клиенты игнорировали серверный `transport_policy.primaryTransport`.** Архитектурный инвариант §1.5 («смена параметров — через сервер, а не пересборку клиентов») не соблюдался для выбора стартового транспорта: `TransportFallbackPolicy` на обеих платформах была жёстко закодирована стартовать с XHTTP, хотя сервер уже резолвил и отдавал `primaryTransport` с учётом operator/region/global-скоупа (`DynamicRoutingService.resolvePolicy`). Исправлено: оба клиента передают в конструктор политики стартовый транспорт, взятый из ответа `/api/v1/client/config`, с защитой от вырожденного случая (`GRPC` без единой рекламируемой gRPC-ноды остаётся на XHTTP).
3. **Тесты**: Android +2 (`TransportFallbackPolicyTest`, 42 теста всего), Desktop +2 (`transportFallbackPolicy.test.ts`, 41 тест всего). `:app:testDebugUnitTest`, `:app:lintDebug`, `npm run typecheck`, `npm test` — все зелёные.
- **Всё ещё не сделано** (осознанно, не в рамках этой правки): `connectTimeMs` в телеметрии всегда `0` — она репортится только при неудаче подключения, а не при успешном коннекте, так что реального замера времени коннекта и базовой линии для доли обрывов пока нет; это отдельная, более инвазивная доработка (меняет семантику `totalReports` в `AdminController.getDashboardMetrics`), см. §6 «Возможные дальнейшие улучшения» в конце документа.

### [x] Пост-Фаза-10: ERC-20/EVM — реализация недостающего рельса из `docs/PLAN.md` §1/§7

`docs/PLAN.md` §1 («Пополнение: TRC-20 и ERC-20 на сайте») и §7 явно фиксируют ERC-20 как принятое решение, а не бэклог — но в коде был реализован только TronGrid-сканер для TRC-20; для EVM-сетей (Ethereum/Base/Arbitrum/Polygon) не было ни отдельного адреса приёма, ни сканера. Хуже того, при создании инвойса без явного `recipientAddress` (`BillingService.createInvoice(userId, chain, amount)`, единственная сигнатура, которую реально вызывает `UserController.createInvoice`) адрес получателя **всегда** брался из `vpn.crypto.tron-deposit-address`, независимо от `chain` — инвойс `chain=ETHEREUM` показал бы пользователю Tron-адрес (base58), на который ни один EVM-кошелёк отправить USDT не может.

1. **Исправлен баг выбора адреса**: `BillingService.resolveDefaultDepositAddress(chain)` теперь возвращает `vpn.crypto.evm-deposit-address` для `ETHEREUM/ERC20/BASE/ARBITRUM/POLYGON` и `vpn.crypto.tron-deposit-address` только для `TRON`; если для запрошенной EVM-сети адрес не сконфигурирован — `createInvoice` бросает `IllegalStateException` вместо тихой генерации нерабочего инвойса.
2. **Добавлен EVM-сканер** (`BlockchainScannerTask.scanEvmChain`) — тот же паттерн, что и `scanTronGrid`, но через голый JSON-RPC (`eth_getLogs` по стандартному топику `Transfer(address,address,uint256)`, без веб3-SDK): отслеживает блоки с учётом `confirmations`, декодирует сумму перевода из `data` лога и пересчитывает её в микро-USDT с учётом децимals токена (`scaleToMicroUsdt`), сверяет с открытыми инвойсами через уже существующий чейн-агностичный `BlockchainPaymentService.processIncomingDeposit`. Один сконфигурированный инстанс — одна EVM-сеть за раз; переключение на Base/Arbitrum/Polygon — это смена `rpc-url`/`chain-id` в конфиге, без нового кода (ровно то, что обещано в `docs/PLAN.md` §7).
3. Новые проперти — `vpn.crypto.evm-deposit-address`, `vpn.blockchain.evm.*` (`enabled`, `chain-name`, `rpc-url`, `chain-id`, `usdt-contract`, `usdt-decimals`, `confirmations`) — задокументированы в новом корневом [`README.md`](file:///Users/roman.struchev/git/vpn/vpn/README.md) §2.
4. **Тесты**: server +9 (`BillingServiceTest` — TRON/EVM выбор адреса + отказ при незаданном EVM-адресе; `BlockchainScannerTaskTest` — disabled/нет-адреса/нет-RPC-URL пропуски + чистые unit-тесты `scaleToMicroUsdt`/`addressToTopic`), 75 тестов всего. `./gradlew :server:test` зелёный.
- **Не сделано** (осознанно): реальный RPC-провайдер и EVM-адрес приёма — это настоящие деньги и инфраструктура, не код, оператор должен сам выбрать провайдера (Infura/Alchemy/публичный RPC) и подставить его перед включением `VPN_EVM_SCANNER_ENABLED=true`; одновременный мониторинг нескольких EVM-сетей сразу (сейчас — один сконфигурированный инстанс).

### [x] Пост-Фаза-10: e2e-набор (Playwright) и четыре бага, реально ломавших сайт для любого пользователя

Юнит-тесты (H2, мок-репозитории) зелёные на 100% всё это время, но ни один из них не мог поймать ни один из следующих багов — все четыре требуют реального Spring-контекста с настоящим Postgres (Hibernate-сессии, `open-in-view: false`) и/или реального браузера, бьющего в реально собранный фронтенд. Найдены и исправлены при первом же настоящем сквозном прогоне: `docker compose up -d postgres` + `./gradlew :server:bootRun` + `cd web && npm run dev` + `cd e2e && npx playwright test`.

1. **Лендинг без логина показывал пустые тарифы.** `GET /api/v1/user/tariffs` не было в `permitAll` списке `SecurityConfig` — 403 для любого анонимного визитора, хотя `App.tsx` дёргает этот эндпоинт ещё до какого-либо логина, чтобы отрисовать блок цен на лендинге. Добавлено в `permitAll` (метод без побочных эффектов, `@GetMapping` без `Authentication`-параметра — безопасно открывать).
2. **Дашборд падал белым экраном у любого пользователя без активной подписки.** `UserController.getProfile` отдавал `"subscription": {}` (пустой объект через `Map.of()`, который не умеет хранить `null`) вместо `null` при отсутствии активной подписки. `DashboardView.tsx` проверял `sub ? sub.tariffId.toUpperCase() : ...` — пустой объект правдив (`{}` truthy), а `tariffId` в нём `undefined` → `Cannot read properties of undefined (reading 'toUpperCase')`, весь `<DashboardView>` падал без ErrorBoundary. Исправлено на обеих сторонах: сервер теперь кладёт настоящий `null` (через `LinkedHashMap`, `Map.of()` заменён), фронтенд теперь дополнительно проверяет `sub && sub.tariffId` вместо голой truthiness — на случай, если какой-то другой путь снова начнёт отдавать пустой объект.
3. **Список устройств необратимо ломался, как только у пользователя появлялось хотя бы одно устройство.** `Device.user` — `@ManyToOne(FetchType.LAZY)` без `@JsonIgnore`; `GET /api/v1/user/devices` сериализует список `Device` напрямую, и Jackson пытается инициализировать ленивый прокси `user` уже после закрытия Hibernate-сессии (`spring.jpa.open-in-view: false`) → `HttpMessageNotWritableException: Could not initialize proxy ... - no session`, которое в данном стеке всплывает как **403** (не 500 — так это выглядит в логе `DefaultHandlerExceptionResolver`, легко спутать с проблемой авторизации). Пустой список сериализовался нормально, поэтому баг был невидим до первого добавленного устройства — стабильно воспроизводится через `curl`. Тот же паттерн уже реально ломал `GET /api/v1/user/invoices` (`CryptoInvoice.user`, идентичная лениво-загружаемая связь) и латентно грозил `BalanceEntry`/`Subscription`, если их когда-нибудь начнут отдавать сырыми списками — добавлен `@JsonIgnore` на `user` во всех четырёх сущностях сразу, не только там, где уже стрельнуло.
   - Попутно найден смежный баг: `DeviceManagementService.getUserDevices` использовал `findByUserId` (без фильтра) вместо `findByUserIdAndIsActiveTrue` — отозванное (`DELETE /devices/{id}`, soft-delete через `isActive=false`) устройство оставалось в списке личного кабинета вечно, с рабочей кнопкой «Отозвать доступ», как будто ничего не произошло. Неиспользуемый `findByUserId` удалён из `DeviceRepository`.
4. **Пополнение баланса через сайт не работало никогда.** `web/src/api.ts` шлёт `{ chain, baseAmountUsdtMicro }` (имя совпадает с полем `CryptoInvoice.baseAmountUsdtMicro`), а `UserController.createInvoice` читал ключ `"amountMicro"` — не существующий в теле запроса. `Long.valueOf(req.get("amountMicro").toString())` на `null` бросал `NullPointerException`, необработанный, всплывавший тем же образом в 403. Сквозная функция «Пополнить баланс (TRC-20 USDT)» была полностью нерабочей с сайта с момента её реализации в Фазе 5 — только API-вызов напрямую (или бот с Stars) реально проводил деньги. Исправлено чтением правильного ключа `baseAmountUsdtMicro`, плюс явная валидация (400 с понятным сообщением вместо голого NPE) на случай отсутствия поля вообще.
5. **e2e-набор**: [`e2e/`](file:///Users/roman.struchev/git/vpn/vpn/e2e) — Playwright, стандалоне npm-проект. `tests/landing.spec.ts` (регресс-тест на баг №1 + переключение языка), `tests/full-user-flow.spec.ts` — один сквозной `test()` с `test.step()`-шагами (не отдельные `test()` — у каждого своя изолированная `BrowserContext`/`localStorage`, а здесь нужен один и тот же браузерный сеанс через весь флоу): регистрация → активация бесплатного пробного тарифа → устройство (добавить+отозвать, регресс на баг №3) → инвойс на пополнение (регресс на баг №4) → логаут → повторный логин с сохранением подписки. Требует реально запущенных `docker compose up -d postgres` + `bootRun` + `web`; см. [`e2e/README.md`](file:///Users/roman.struchev/git/vpn/vpn/e2e/README.md).
6. **Тесты**: server +2 (`UserControllerTest` missing-amount-400, `DeviceManagementServiceTest` active-only-devices регресс), 77 тестов всего. e2e — 3/3 зелёных против реального стека.
- **Вывод, а не только фикс**: все четыре бага были невидимы для всего существовавшего до этого тестового покрытия (H2 + моки) — характерная слепая зона: то, что ломается только при закрытой Hibernate-сессии, реальном Jackson-сериализаторе или реальном браузерном fetch, юнит-тесты с моками принципиально не поймают. `e2e/` теперь можно и стоит гонять регулярно как часть проверки перед релизом, не только вручную по запросу — см. обновлённую шпаргалку в README.md.

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

# 6. Desktop: typecheck + unit-тесты (see desktop/README.md)
cd desktop && npm install && npm run typecheck && npm test
```

### Запуск локальной инфраструктуры
```bash
# Запуск PostgreSQL 17
docker compose up -d postgres

# Полный запуск сервера против реальной БД (не H2/test-профиль!) — так
# нашлись оба критических бага Фазы 10 (Flyway, gRPC). Юнит-тесты используют
# H2 с flyway.enabled=false и НЕ проверяют ни миграции, ни старт gRPC-сервера.
./gradlew :server:bootRun
# Ожидать в логе: "Successfully applied N migrations" и "gRPC Server started on port 9090"
```

Полная конфигурация (все переменные окружения по подсистемам, как поднять
стек с нуля, как получить первого ADMIN'а и зарегистрировать первую ноду) —
в корневом [`README.md`](file:///Users/roman.struchev/git/vpn/vpn/README.md), не дублируется здесь.

---

## 6. Возможные дальнейшие улучшения (не реализовано, кандидаты на следующую итерацию)

Ничего из этого не блокирует запуск — все 10 фаз и оба пост-фазовых фикса
выше самодостаточны. Список для следующего, кто продолжит работу над
проектом, отсортирован примерно по ценности/усилиям:

1. **У админки нет веб-интерфейса.** `docs/PLAN.md` §8 описывает полноценную
   PrimeReact-панель (пользователи, ноды, дашборды, платежи, управление), но
   по факту реализован только REST API (`AdminController`) — им пока можно
   пользоваться только через `curl`/Postman. Это самый крупный оставшийся
   разрыв между планом и кодом, отдельная задача масштаба исходной Фазы 6.
2. **Телеметрия не репортится при успешном подключении**, только при
   неудаче — `connectTimeMs` в БД всегда `0`, а `AdminController`'овская
   «деградация оператор × регион × транспорт» фактически считает только
   абсолютное число сбоев, без базовой линии успешных подключений для
   расчёта настоящей доли отказов. Добавление success-репорта требует
   аккуратно продумать семантику `totalReports` в дашборде, чтобы не
   исказить уже используемую метрику.
3. **Мониторинг нескольких EVM-сетей одновременно.** Сейчас
   `vpn.blockchain.evm.*` — один сконфигурированный инстанс (одна сеть за
   раз); чтобы одновременно принимать, скажем, и Ethereum, и Base, нужно
   параметризовать `BlockchainScannerTask` списком чейнов вместо плоских
   `@Value`-полей (список объектов через env vars неудобен — see текущий
   плоский подход в README §2 — понадобится либо `@ConfigurationProperties`
   с YAML-списком, либо compact-строковый формат).
4. **Глобального обработчика ошибок нет** (`@ControllerAdvice`) — валидационные
   исключения (`IllegalArgumentException`/`IllegalStateException`) сейчас
   долетают до клиента как generic 500 без структурированного тела ошибки, а
   не как явный 400 с понятным сообщением. Затрагивает все контроллеры,
   поэтому это отдельная, преднамеренно не начатая в этой сессии правка.
5. ~~**Общий пакет `ui/`** для дизайн-токенов~~ — **сделано**: значения (`brand`,
   `dark`, `state`) вынесены в [`design-tokens/tokens.mjs`](file:///Users/roman.struchev/git/vpn/vpn/design-tokens/tokens.mjs),
   `web/tailwind.config.js` и `desktop/tailwind.config.js` импортируют его
   напрямую (значения подтверждены идентичными до консолидации — дрейфа
   между платформами не было). Android (`colors.xml`) синхронизируется
   вручную — автогенерация XML из JS-модуля на этапе сборки Gradle/AGP была
   бы непропорционально сложной ради полутора десятков цветовых констант;
   вместо этого `colors.xml` комментарием указывает на канонический файл.
   Полноценного общего пакета компонентов по-прежнему нет и не может быть в
   этой архитектуре — web/desktop на React, Android нативный на Java/Material3.
