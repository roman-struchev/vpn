# VPN-сервис — монорепо

Приватный VPN-сервис на VLESS + XHTTP/gRPC + Reality, с упором на устойчивость
к DPI-блокировкам (см. [`docs/research/ru-blocking.md`](docs/research/ru-blocking.md)).
Архитектурные решения и продуктовый план — [`docs/PLAN.md`](docs/PLAN.md).
Текущий статус реализации по фазам (единый источник правды о том, что сделано,
а что нет) — [`docs/ROADMAP_PROGRESS.md`](docs/ROADMAP_PROGRESS.md).

Этот файл — практический гайд: как поднять стек локально и что где настраивать.
Архитектурные «почему» здесь не дублируются — они в `docs/`.

## Структура репозитория

| Каталог | Что это | Как собирается |
|---|---|---|
| `server/` | Spring Boot 4 API + gRPC control-plane + биллинг + встроенный `web/` (fat JAR) | `./gradlew :server:bootRun` (часть корневого Gradle-мультипроекта) |
| `agent/` | TypeScript-демон на нодах: держит gRPC-стрим к серверу, управляет процессом `xray-core` | отдельный npm-проект |
| `web/` | React 18 + Vite лендинг, личный кабинет, Telegram Mini App | отдельный npm-проект; собранный `dist/` копируется в JAR сервера Gradle-таской `copyWebDist` |
| `android/` | Нативный Android-клиент (Java, Material 3, `libXray`) | отдельный Gradle-проект (Groovy DSL) |
| `desktop/` | Electron + React клиент для Windows/macOS (системный прокси) | отдельный npm-проект |
| `e2e/` | Playwright — интеграционные тесты поверх реального сервера+веба (не моки), полный пользовательский флоу | отдельный npm-проект, см. [`e2e/README.md`](e2e/README.md) |
| `design-tokens/` | Общие значения дизайн-токенов (цвета бренда/тёмной темы) — единый источник для `web/` и `desktop/`; Android синхронизируется вручную, см. [`tokens.mjs`](design-tokens/tokens.mjs) | не собирается, импортируется напрямую (`export default {...}`) |
| `proto/` | Protobuf-контракт `server ↔ agent` | генерируется в `server/` и `agent/` при сборке |
| `scripts/install-node.sh` | Установщик агента на VPS-ноду (systemd, sysctl, опционально TLS-сертификат для CDN-нод и `tc`-каппинг для пробного пула) | см. §5 ниже |
| `docs/` | План, статус фаз, исследование блокировок РФ, чеклист магазинов приложений, Google Play readiness | — |

## 1. Быстрый старт (локально)

```bash
# 1. PostgreSQL 17 в Docker
docker compose up -d postgres

# 2. Сервер (HTTP :8080, gRPC :9090) — против настоящей БД, не H2!
#    Юнит-тесты используют H2 с flyway.enabled=false и не проверяют ни
#    миграции, ни реальный старт gRPC — см. docs/ROADMAP_PROGRESS.md §5.
./gradlew :server:bootRun
# В логе должно быть: "Successfully applied N migrations" и
# "gRPC Server started on port 9090"

# 3. Веб (dev-сервер на :3000 с прокси /api -> localhost:8080)
cd web && npm install && npm run dev
```

Веб-клиент в проде отдельно не деплоится — `./gradlew :server:bootJar` уже
включает собранный `web/dist` внутрь JAR (`resources/static`), и Spring Boot
отдаёт его на том же порту 8080, что и API.

### Первый администратор

Сидинга ADMIN-пользователя нет намеренно (`User.role` по умолчанию `"USER"`).
Порядок:
1. Зарегистрируйтесь как обычный пользователь: `POST /api/v1/auth/register`.
2. Вручную повысьте роль в БД: `UPDATE users SET role='ADMIN' WHERE email='...';`
3. Логинтесь тем же email/паролем — JWT будет нести `ROLE_ADMIN`, и
   `/api/v1/admin/**` (защищено в `SecurityConfig`, `hasRole("ADMIN")`)
   станет доступен.

### Регистрация первой ноды

1. Как ADMIN дёрните `POST /api/v1/admin/nodes/bootstrap-token` — получите
   одноразовый `bootstrap_token`.
2. На самой VPS выполните `scripts/install-node.sh <server_grpc_host:port> <bootstrap_token>`
   (плюс опционально `[cdn_hostname]` и `[trial_cap_mbps]`, см. §5).
3. Нода сама подключится по gRPC-стриму и зарегистрируется.

## 2. Конфигурация сервера

Всё — через переменные окружения (или `server/src/main/resources/application.yml`
напрямую для локальной разработки). Полный список с default-значениями:

### База данных и безопасность

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/vpn_db` | Строка подключения к Postgres 17 |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `vpn_user` / `vpn_secret` | Креды БД (см. `docker-compose.yml` для локального контейнера) |
| `JWT_SECRET` | тестовый плейсхолдер в `application.yml` | **Обязательно сменить в проде.** HMAC-секрет для JWT (`security.jwt.secret`) |
| `GRPC_SERVER_PORT` | `9090` | Порт gRPC-стрима сервер↔агент (mTLS/аутентификация — см. `docs/PLAN.md` §10) |

### Транспорт (VLESS/Reality/XHTTP/gRPC-фолбэк)

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `VPN_REALITY_DEST` | `dl.google.com:443` | Reality `dest` — легитимный TLS-хост, чей сертификат «крадёт» handshake |
| `VPN_REALITY_SNI` | `dl.google.com,gateway.icloud.com` | Список допустимых SNI через запятую |
| `VPN_GRPC_FALLBACK_PORT` | `8443` | Порт запасного inbound'а gRPC+Reality на прямых нодах (Фаза 9) |
| `VPN_GRPC_FALLBACK_SERVICE_NAME` | `vless-grpc` | Имя gRPC-сервиса для этого inbound'а |
| `VPN_CDN_CERT_DIR` | `/etc/xray/certs` | Где на ноде лежат `<hostname>/{fullchain,privkey}.pem` для CDN-нод (настоящий TLS вместо Reality — см. `scripts/install-node.sh` шаг certbot) |

Сама стратегия (`primaryTransport`, `fingerprint`, тайминги backoff) —
серверная сущность `TransportPolicy`, со scope `operator | region | global`;
меняется через админ-API (`POST /api/v1/admin/...`), без пересборки клиентов
— см. `docs/ROADMAP_PROGRESS.md` §1.5 и Фазу 9.

### Telegram-бот

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | пусто | Токен бота от @BotFather |
| `TELEGRAM_BOT_USERNAME` | `MyVpnBot` | Имя бота (для диплинков рефералки) |
| `TELEGRAM_WEBHOOK_SECRET` | пусто | Секрет для проверки заголовка `X-Telegram-Bot-Api-Secret-Token` на `POST /api/v1/telegram/webhook` |
| `TELEGRAM_MINI_APP_URL` | `https://vpn.example.com` | URL Mini App, который бот открывает |

После деплоя нужно вручную зарегистрировать вебхук в Telegram (`setWebhook`
на `https://<ваш-домен>/api/v1/telegram/webhook`, с тем же `secret_token`,
что и `TELEGRAM_WEBHOOK_SECRET`) — это одноразовый ручной/скриптовый шаг,
не выполняется автоматически при старте сервера.

### Приём платежей — TRC-20 (Tron) и ERC-20 (EVM-сети)

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `VPN_TRON_DEPOSIT_ADDRESS` | плейсхолдер `TXxxDefault...` | Адрес приёма USDT (TRC-20). Пока не заменён на настоящий — сканер молча пропускает цикл |
| `VPN_BLOCKCHAIN_SCANNER_ENABLED` | `false` | Включить фоновый TronGrid-сканер входящих TRC-20 переводов |
| `VPN_TRONGRID_URL` | `https://api.trongrid.io` | REST-эндпоинт TronGrid |
| `VPN_TRONGRID_API_KEY` | пусто | API-ключ TronGrid (опционально, для более высоких рейт-лимитов) |
| `VPN_TRON_USDT_CONTRACT` | адрес USDT TRC-20 в mainnet | Контракт токена, который сканируется |
| `VPN_EVM_DEPOSIT_ADDRESS` | пусто | Адрес приёма USDT в EVM-сетях (Ethereum/Base/Arbitrum/Polygon — один и тот же 0x-адрес для всех, см. `docs/PLAN.md` §7) |
| `VPN_EVM_SCANNER_ENABLED` | `false` | Включить фоновый EVM-сканер (`eth_getLogs` по событию `Transfer`, без веб3-SDK) |
| `VPN_EVM_CHAIN_NAME` | `ETHEREUM` | Значение `chain`, под которым сканер сверяет/зачисляет инвойсы — должно совпадать с тем, что фронтенд передаёт в `POST /api/v1/user/billing/invoice` |
| `VPN_EVM_RPC_URL` | пусто | JSON-RPC HTTPS эндпоинт нужной EVM-сети (для Base/Arbitrum/Polygon — просто другой RPC URL и `chain-id`, без изменения кода) |
| `VPN_EVM_CHAIN_ID` | `1` (Ethereum mainnet) | chainId сети |
| `VPN_EVM_USDT_CONTRACT` | адрес USDT в Ethereum mainnet | Контракт токена в выбранной сети |
| `VPN_EVM_USDT_DECIMALS` | `6` | Десятичность токена (для автоматического пересчёта в микро-USDT) |
| `VPN_EVM_CONFIRMATIONS` | `12` | Сколько блоков ждать перед тем, как считать перевод подтверждённым |

Оба сканера — best-effort автоматика поверх основного механизма: инвойс с
допуском ±0.0004 USDT (`docs/PLAN.md` §7) и ручной claim
(`POST /api/v1/user/billing/claim-tx`, «я оплатил, вот хеш») остаются
рабочими независимо от сканеров и служат страховкой на случай пропуска.

### Защита от перечисления нод (Фаза 10)

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `VPN_ANTI_ENUM_WINDOW_MINUTES` | `60` | Окно наблюдения за IP-адресами, с которых тянут ссылку подписки |
| `VPN_ANTI_ENUM_MAX_DISTINCT_IPS` | `5` | Порог различных IP в окне, после которого VLESS-ключи аккаунта ротируются (не бан) |

## 3. Конфигурация node-агента (`agent/`)

Через `.env` в `/opt/vpn-node-agent/.env` на самой ноде (создаётся
`scripts/install-node.sh`) или переменные окружения при локальном запуске:

| Переменная | Назначение |
|---|---|
| `CONTROL_PLANE_GRPC` | `host:port` сервера (тот же `GRPC_SERVER_PORT`) |
| `BOOTSTRAP_TOKEN` | Одноразовый токен из `POST /api/v1/admin/nodes/bootstrap-token` |
| `NODE_ID` / `NODE_TOKEN` | Заполняются агентом автоматически после первой успешной регистрации (сохраняются в `.agent-state.json`, путь — `AGENT_STATE_PATH`) |
| `REGION`, `ASN`, `PUBLIC_IP`, `NODE_HOSTNAME` | Метаданные ноды, показываются в админке |
| `XRAY_BIN_PATH`, `XRAY_CONFIG_PATH` | Пути к бинарю/конфигу `xray-core` на ноде |
| `XRAY_STATS_API_URL` | `127.0.0.1:10085` — локальный Stats API самого `xray-core` |
| `HEARTBEAT_INTERVAL_MS`, `STATS_INTERVAL_MS` | Периодичность heartbeat/отправки статистики трафика (по умолчанию 30с — те же 30с, что в `vpn.heartbeat-interval-sec`/`vpn.stats-interval-sec` на сервере) |

## 4. Конфигурация клиентов

- **Android** — `-PapiBaseUrl=https://...` и опционально `-PapiBaseUrlsBackup=https://backup1/,https://backup2/`
  при сборке Gradle. Подробнее и полный список архитектурных заметок — [`android/README.md`](android/README.md).
- **Desktop** — переменные окружения `VPN_API_BASE_URL` и `VPN_API_BASE_URLS_BACKUP`
  при `npm run dev`/сборке. Подробнее — [`desktop/README.md`](desktop/README.md).

Оба клиента получают транспортную политику (какой транспорт стартовый,
фингерпринт, тайминги backoff) с сервера через `GET /api/v1/client/config` —
менять её нужно через `TransportPolicy` в БД/админку, а не пересборкой клиента.

## 5. Установка ноды в проде

```bash
# Прямая Reality-нода:
scripts/install-node.sh vpn.example.com:9090 bst_abc12345

# CDN-нода (настоящий TLS вместо Reality, certbot standalone + автопродление):
scripts/install-node.sh vpn.example.com:9090 bst_abc12345 edge.example.com

# Пробная нода с общим потолком канала (tc htb + fq_codel, systemd-юнит на автозапуск):
scripts/install-node.sh vpn.example.com:9090 bst_abc12345 "" 50
```

После установки — зарегистрировать ноду в нужном пуле через админ-API
(`POST /api/v1/admin/nodes/{id}/pool`, значения `paid` / `trial` / `quarantine` / `reserve`).
Подробности — `docs/ROADMAP_PROGRESS.md`, Фазы 2, 3, 9, 10.

## 6. Тесты и сборка — полная шпаргалка

```bash
# Сервер (JUnit 5 + Spring Boot, против H2 — не ловит миграции/gRPC-скейв, см. выше)
./gradlew test

# Нода-агент (Vitest)
cd agent && npm test

# Веб (сборка + типы)
cd web && npm run build

# Единый fat-JAR сервера (включает web/dist)
./gradlew :server:bootJar -x test

# Android (нужен JDK 17-21 для Gradle-wrapper 8.10, см. android/README.md)
cd android && ./scripts/fetch-libxray.sh && ./gradlew :app:testDebugUnitTest && ./gradlew :app:lintDebug

# Desktop
cd desktop && npm install && npm run typecheck && npm test

# E2E — требует реально поднятого стека (см. §1): docker compose up -d postgres,
# ./gradlew :server:bootRun, cd web && npm run dev — только после этого:
cd e2e && npm install && npx playwright install chromium && npm test
```

Все команды выше, кроме e2e, — юнит/компонентные тесты с моками (H2, моки
репозиториев). Они не заменяют e2e: несколько реальных багов (403 на
публичной странице тарифов для анонимных пользователей, падение личного
кабинета при отсутствии подписки, необратимая поломка списка устройств,
нерабочее пополнение баланса) были невидимы всему юнит-покрытию и нашлись
только прогоном `e2e/` против настоящего Postgres+сервера+браузера — см.
`docs/ROADMAP_PROGRESS.md`, «Пост-Фаза-10: e2e-набор». Гонять `e2e/` стоит
не только вручную по запросу, а как часть обычной проверки перед релизом.

## 7. Дальнейшая документация

| Файл | О чём |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | Продуктовые и архитектурные решения, тарифы, риски, дорожная карта по срокам |
| [`docs/ROADMAP_PROGRESS.md`](docs/ROADMAP_PROGRESS.md) | Статус каждой фазы, что именно сделано и где, известные пробелы |
| [`docs/research/ru-blocking.md`](docs/research/ru-blocking.md) | Обоснование выбора транспортов (почему XHTTP+Reality, почему gRPC — запасной) |
| [`docs/stores-and-liability.md`](docs/stores-and-liability.md) | Чеклист для магазинов приложений и юридические риски (РФ-реклама, VPN-правила) |
| [`docs/google-play-readiness.md`](docs/google-play-readiness.md) | Маппинг чеклиста на код, черновик политики конфиденциальности, Data Safety форма |
| [`android/README.md`](android/README.md) | Сборка, архитектура и известные пробелы Android-клиента |
| [`desktop/README.md`](desktop/README.md) | Сборка, архитектура и известные пробелы Desktop-клиента |
