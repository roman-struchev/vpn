# VPN-сервис — монорепо

Приватный VPN-сервис на VLESS + XHTTP/gRPC + Reality, с упором на устойчивость
к DPI-блокировкам (см. [`docs/research/ru-blocking.md`](docs/research/ru-blocking.md)).
Архитектура, продуктовая модель и системный дизайн — [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Полный навигатор по документации — [`docs/README.md`](docs/README.md).

Этот файл — практический гайд: как поднять стек локально и что где настраивать.
Архитектурные «почему» здесь не дублируются — они в `docs/`.

## Структура репозитория

| Каталог | Что это | Как собирается |
|---|---|---|
| `server/` | Spring Boot 4 API + gRPC control-plane + биллинг + встроенный `web/` (fat JAR) | `./gradlew :server:bootRun` (часть корневого Gradle-мультипроекта) |
| `agent/` | TypeScript-демон на нодах: держит gRPC-стрим к серверу, управляет процессом `xray-core` | отдельный npm-проект |
| `web/` | React 18 + Vite: лендинг, личный кабинет, Telegram Mini App, веб-панель администратора (`/admin`) | отдельный npm-проект; собранный `dist/` копируется в JAR сервера Gradle-таской `copyWebDist` |
| `android/` | Нативный Android-клиент (Java, Material 3, `libXray`, Google Sign-In, триал без регистрации, выбор региона) | отдельный Gradle-проект (Groovy DSL) |
| `desktop/` | Electron + React клиент для Windows/macOS (системный прокси, трей, Google Sign-In, триал без регистрации, выбор региона) | отдельный npm-проект |
| `e2e/` | Playwright — интеграционные тесты реального стека (регистрация, биллинг, админка, поднятие реального нод-агента и проверка VPN-туннеля) | отдельный npm-проект, см. [`e2e/README.md`](e2e/README.md) |
| `design-tokens/` | Общие значения дизайн-токенов (цвета бренда/тёмной темы) — единый источник для `web/` и `desktop/`; Android синхронизируется вручную, см. [`tokens.mjs`](design-tokens/tokens.mjs) | не собирается, импортируется напрямую (`export default {...}`) |
| `proto/` | Protobuf-контракт `server ↔ agent` | генерируется в `server/` и `agent/` при сборке |
| `scripts/install-node.sh` | Установщик агента на VPS-ноду: Docker-контейнер (`--network host`, `--restart unless-stopped`), sysctl-тюнинг, авто-детект public IP и региона, опционально TLS-сертификат для CDN-нод | см. §5 ниже |
| `docs/` | План, статус фаз, исследование блокировок РФ, чеклист магазинов приложений, Google Play readiness | — |

## 1. Быстрый старт (локально)

```bash
# 1. PostgreSQL 17 в Docker
docker compose up -d postgres

# 2. Сервер (HTTP :8080, gRPC :9090) — против настоящей БД, не H2!
#    Применяются 7 Flyway-миграций (V1..V7). Юнит-тесты используют H2
#    с flyway.enabled=false и не проверяют ни миграции, ни gRPC — см. docs/ARCHITECTURE.md §2 и §6.
./gradlew :server:bootRun
# В логе должно быть: "Successfully applied 7 migrations" и
# "gRPC Server started on port 9090"

# 3. Веб (dev-сервер на :3000 с прокси /api -> localhost:8080)
cd web && npm install && npm run dev
```

Веб-клиент в проде отдельно не деплоится — `./gradlew :server:bootJar` уже
включает собранный `web/dist` внутрь JAR (`resources/static`), и Spring Boot
отдаёт его на том же порту 8080, что и API.

### Первый администратор и веб-панель управления

Сидинга ADMIN-пользователя нет намеренно (`User.role` по умолчанию `"USER"`).
Порядок:
1. Зарегистрируйтесь как обычный пользователь: `POST /api/v1/auth/register` (или в веб-интерфейсе).
2. Вручную повысьте роль в БД: `UPDATE users SET role='ADMIN' WHERE email='...';`
3. Залогиньтесь тем же email/паролем — JWT будет нести `ROLE_ADMIN`, и
   `/api/v1/admin/**` (защищено в `SecurityConfig`, `hasRole("ADMIN")`),
   а также веб-панель по адресу `http://localhost:3000/admin` (или `https://ваш-домен/admin`)
   станут доступны.

Веб-панель администратора (`web/src/admin/`) предоставляет 5 разделов:
- **Дашборд:** ключевые метрики (пользователи, активные подписки, балансы, трафик, ноды, реферальные выплаты) и таблица телеметрии деградации операторов/регионов.
- **Пользователи:** список пользователей, фильтрация, смена статуса (ACTIVE/BLOCKED/SUSPENDED), ручная корректировка баланса, продление подписки, статистика реферальных начислений.
- **Ноды:** статус нод, системные метрики (CPU, память, активные соединения, пропускная способность), смена пула (paid/trial/quarantine/reserve), отправка команд (`COMMAND_TYPE_RESTART_XRAY`), форсированная синхронизация конфигов и выпуск `bootstrap-token`.
- **Платежи:** журнал крипто-инвойсов, детальная выписка балансового леджера (`BalanceEntry`), ручная сверка депозитов.
- **Политики:** управление `TransportPolicy` (выбор стартового транспорта, параметров переподключения и фингерпринтов).

### Регистрация первой ноды

1. Как ADMIN дёрните `POST /api/v1/admin/nodes/bootstrap-token` — получите
   `bootstrap_token` (действует многократно, пока не истёк `validHours`).
2. Запустите на VPS установщик `scripts/install-node.sh` (см. §5 для точной команды
   через `ssh`/`curl` и опционального `[cdn_hostname]`).
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
| `GRPC_SERVER_PORT` | `9090` | Порт gRPC-стрима сервер↔агент (mTLS/аутентификация — см. `docs/ARCHITECTURE.md` §5) |

### Транспорт (VLESS/Reality/XHTTP/gRPC-фолбэк)

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `VPN_REALITY_DEST` | `dl.google.com:443` | Reality `dest` — легитимный TLS-хост, чей сертификат «крадёт» handshake |
| `VPN_REALITY_SNI` | `dl.google.com,gateway.icloud.com` | Список допустимых SNI через запятую |
| `VPN_GRPC_FALLBACK_PORT` | `8443` | Порт запасного inbound'а gRPC+Reality на прямых нодах |
| `VPN_GRPC_FALLBACK_SERVICE_NAME` | `vless-grpc` | Имя gRPC-сервиса для этого inbound'а |
| `VPN_CDN_CERT_DIR` | `/etc/xray/certs` | Где на ноде лежат `<hostname>/{fullchain,privkey}.pem` для CDN-нод (настоящий TLS вместо Reality — см. `scripts/install-node.sh` шаг certbot) |

Сама стратегия (`primaryTransport`, `fingerprint`, тайминги backoff) —
серверная сущность `TransportPolicy`, со scope `operator | region | global`;
меняется через админ-API (`POST /api/v1/admin/...`), без пересборки клиентов
— см. `docs/ARCHITECTURE.md` §4.

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

### Вход через Google

Backend, веб, десктоп и Android умеют логиниться/регистрироваться через
Google (`POST /api/v1/auth/google` принимает `idToken`, который каждый
клиент добывает по-своему). Без ключей ниже кнопка Google либо не
показывается, либо падает с понятной ошибкой — код на это рассчитан, но
фича не работает, пока не заведены реальные OAuth Client ID в
[Google Cloud Console](https://console.cloud.google.com/apis/credentials).

| Переменная / значение | Где | Назначение |
|---|---|---|
| `GOOGLE_OAUTH_CLIENT_ID` | сервер (`vpn.google.client-id`) | Client ID типа **Web application** — сервер сверяет с ним `aud` в присланном id-токене; если пусто, `/api/v1/auth/google` всегда отклоняет запрос (не auth-bypass) |
| `VITE_GOOGLE_CLIENT_ID` | веб (сборка, `web/`) | Тот же Web application Client ID — используется Google Identity Services в `AuthModal.tsx` |
| `google_web_client_id` (строковый ресурс) | Android, `android/app/src/main/res/values/strings.xml` | Тот же Web application Client ID (не Android-тип!) — его требует `GetGoogleIdOption` в Credential Manager. Сейчас там плейсхолдер `REPLACE_WITH_GOOGLE_WEB_CLIENT_ID` |
| — (Android Client ID) | Google Cloud Console | Отдельно нужен OAuth Client ID типа **Android**, привязанный к `com.vpn.android` + SHA-1 отпечатку подписи (и debug, и release — иначе `DEVELOPER_ERROR`/`GetCredentialException`; debug-отпечаток — `./gradlew signingReport`) |
| `GOOGLE_DESKTOP_CLIENT_ID` / `GOOGLE_DESKTOP_CLIENT_SECRET` | desktop (env) | OAuth Client ID типа **Desktop app** (+ его secret — для этого типа клиента Google не считает secret конфиденциальным, поэтому его можно зашивать в приложение). Используется в loopback-флоу через системный браузер (`main/auth/googleOAuth.ts`); `http://127.0.0.1:*` как redirect URI не требует отдельной регистрации для Desktop-типа |

### Приём платежей — TRC-20 (Tron) и ERC-20 (EVM-сети)

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `VPN_TRON_DEPOSIT_ADDRESS` | плейсхолдер `TXxxDefault...` | Адрес приёма USDT (TRC-20). Пока не заменён на настоящий — сканер молча пропускает цикл |
| `VPN_BLOCKCHAIN_SCANNER_ENABLED` | `false` | Включить фоновый TronGrid-сканер входящих TRC-20 переводов |
| `VPN_TRONGRID_URL` | `https://api.trongrid.io` | REST-эндпоинт TronGrid |
| `VPN_TRONGRID_API_KEY` | пусто | API-ключ TronGrid (опционально, для более высоких рейт-лимитов) |
| `VPN_TRON_USDT_CONTRACT` | адрес USDT TRC-20 в mainnet | Контракт токена, который сканируется |
| `VPN_EVM_DEPOSIT_ADDRESS` | пусто | Адрес приёма USDT в EVM-сетях (Ethereum/Base/Arbitrum/Polygon — один и тот же 0x-адрес для всех, см. `docs/ARCHITECTURE.md` §7) |
| `VPN_EVM_SCANNER_ENABLED` | `false` | Включить фоновый EVM-сканер (`eth_getLogs` по событию `Transfer`, без веб3-SDK) |
| `VPN_EVM_CHAIN_NAME` | `ETHEREUM` | Значение `chain`, под которым сканер сверяет/зачисляет инвойсы — должно совпадать с тем, что фронтенд передаёт в `POST /api/v1/user/billing/invoice` |
| `VPN_EVM_RPC_URL` | пусто | JSON-RPC HTTPS эндпоинт нужной EVM-сети (для Base/Arbitrum/Polygon — просто другой RPC URL и `chain-id`, без изменения кода) |
| `VPN_EVM_CHAIN_ID` | `1` (Ethereum mainnet) | chainId сети |
| `VPN_EVM_USDT_CONTRACT` | адрес USDT в Ethereum mainnet | Контракт токена в выбранной сети |
| `VPN_EVM_USDT_DECIMALS` | `6` | Десятичность токена (для автоматического пересчёта в микро-USDT) |
| `VPN_EVM_CONFIRMATIONS` | `12` | Сколько блоков ждать перед тем, как считать перевод подтверждённым |

Оба сканера — best-effort автоматика поверх основного механизма: инвойс с
допуском ±0.0004 USDT (`docs/ARCHITECTURE.md` §7) и ручной claim
(`POST /api/v1/user/billing/claim-tx`, «я оплатил, вот хеш») остаются
рабочими независимо от сканеров и служат страховкой на случай пропуска.

### Web Handoff SSO и Telegram Stars на вебе

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `VPN_PUBLIC_WEB_BASE_URL` | `https://vpn.struchev.site` | Публичный URL веб-кабинета для реферальных ссылок и SSO Handoff (`vpn.web.base-url`) |
| `VPN_SUPPORT_TELEGRAM` | `struchev` | Telegram-аккаунт поддержки: бот (`/support`), заголовок `Support-Url` ссылки-подписки |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | пусто | SMTP для кодов сброса пароля на почту. Без него код приходит только в привязанный Telegram |
| `VPN_MAIL_FROM` | пусто | Адрес отправителя писем (`MailService`) |
| `VPN_WEB_HANDOFF_TTL_SECONDS` | `60` | Срок жизни одноразового кода для бесшовного перехода из мобильного/десктопного клиента в веб-биллинг (`vpn.web-handoff.ttl-seconds`) |

Пополнение баланса через **Telegram Stars** доступно и в веб-кабинете: пользователь генерирует одноразовую ссылку связывания аккаунта (`POST /api/v1/user/telegram-link`), бот привязывает Telegram ID к пользователю и выставляет Stars-инвойс, который зачисляется на общий баланс.

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
| `SERVER_GRPC_URL` | `host:port` сервера (тот же `GRPC_SERVER_PORT`) |
| `BOOTSTRAP_TOKEN` | Одноразовый токен из `POST /api/v1/admin/nodes/bootstrap-token` |
| `NODE_ID` / `NODE_TOKEN` | Заполняются агентом автоматически после первой успешной регистрации (сохраняются в `.agent-state.json`, путь — `AGENT_STATE_PATH`) |
| `PUBLIC_IP` | IP, который сервер вставляет в каждую VLESS-ссылку для этой ноды. `install-node.sh` определяет его сам (интерфейс хоста, иначе внешний echo-сервис) — ошибка тут **фатальна** для установки, в отличие от `REGION` ниже |
| `REGION` | Метка региона ("City, CC"), показывается в админке и используется для группировки нод в клиентском селекторе "авто (лучший доступный)". `install-node.sh` определяет её сам через geo-IP по публичному IP хоста (см. §5); при неудаче — `"default"`, ошибкой установку не роняет |
| `ASN`, `NODE_HOSTNAME` | Остальные метаданные ноды, показываются в админке |
| `XRAY_BIN_PATH`, `XRAY_CONFIG_PATH` | Пути к бинарю/конфигу `xray-core` на ноде |
| `XRAY_STATS_API_URL` | `127.0.0.1:10085` — локальный Stats API самого `xray-core` |
| `HEARTBEAT_INTERVAL_MS`, `STATS_INTERVAL_MS` | Периодичность heartbeat/отправки статистики трафика (по умолчанию 30с — те же 30с, что в `vpn.heartbeat-interval-sec`/`vpn.stats-interval-sec` на сервере) |

## 4. Конфигурация клиентов

- **Android** — `-PapiBaseUrl=https://...` и опционально `-PapiBaseUrlsBackup=https://backup1/,https://backup2/`
  при сборке Gradle. Поддерживает Google Sign-In (Credential Manager), 1-click анонимный триал по UUID устройства без ввода почты (`POST /api/v1/auth/device`), выбор региона с оценкой нагрузки нод (low/medium/high) и Web Handoff SSO для открытия биллинга в браузере. Подробнее — [`android/README.md`](android/README.md).
- **Desktop** — переменные окружения `VPN_API_BASE_URL` и `VPN_API_BASE_URLS_BACKUP`
  при `npm run dev`/сборке. Поддерживает системный трей (меню-бар), Google Sign-In через системный браузер (loopback OAuth), 1-click триал по Device UUID, селектор регионов и бесшовный переход в веб-биллинг по Web Handoff. Подробнее — [`desktop/README.md`](desktop/README.md).

Оба клиента получают транспортную политику (какой транспорт стартовый,
фингерпринт, тайминги backoff) с сервера через `GET /api/v1/client/config` —
менять её нужно через `TransportPolicy` в БД/админку, а не пересборкой клиента.

## 5. Установка ноды в проде

Запускается на чистом сервере (Ubuntu 22.04/24.04, Debian 12) от root, **с уже
установленным Docker** (скрипт это проверяет и падает с понятной ошибкой, если его
нет — сам Docker не ставит). Поднимает node-агент + `xray-core` одним контейнером
(`--network host`, `--restart unless-stopped`) из образа `romanew/vpn-node:latest`,
который публикует `publish-node` job в `.github/workflows/gradlew-publish-and-deploy.yml`
на каждый push в `main`. Никакого systemd-юнита для самого агента — управление и
мониторинг обычным Docker: `docker ps` / `docker logs -f vpn-node-agent` /
`docker stats vpn-node-agent` / `docker restart|stop vpn-node-agent`.

Публичный IP и регион ноды скрипт определяет сам (по IP хоста и geo-IP
соответственно) — руками задавать не нужно. Регион можно переопределить
4-м аргументом, если автоопределение ошиблось или несколько нод одного города
нужно свести в одну группу (группировка регионов — точное совпадение строки).

Репозиторий публичный, поэтому скрипт можно ставить прямо по SSH одной командой
через `curl` (если репозиторий когда-нибудь снова станет приватным — см. `scp`-вариант
ниже):

```bash
# Прямая Reality-нода:
ssh root@<node-ip> 'curl -fsSL https://raw.githubusercontent.com/roman-struchev/vpn/main/scripts/install-node.sh | bash -s -- vpn.example.com:9090 bst_abc12345'

# CDN-нода (настоящий TLS вместо Reality, certbot standalone + автопродление):
ssh root@<node-ip> 'curl -fsSL https://raw.githubusercontent.com/roman-struchev/vpn/main/scripts/install-node.sh | bash -s -- vpn.example.com:9090 bst_abc12345 edge.example.com'

# С принудительным регионом (пустой 3-й аргумент — не CDN-нода):
ssh root@<node-ip> 'curl -fsSL https://raw.githubusercontent.com/roman-struchev/vpn/main/scripts/install-node.sh | bash -s -- vpn.example.com:9090 bst_abc12345 "" "Netherlands, Amsterdam"'
```

Если репозиторий приватный — скопируйте скрипт со своей машины (где уже есть SSH-доступ
и репозиторий) и выполните его так:

```bash
scp scripts/install-node.sh root@<node-ip>:/root/install-node.sh
ssh root@<node-ip> "bash /root/install-node.sh vpn.example.com:9090 bst_abc12345"
```

После установки — зарегистрировать ноду в нужном пуле через админ-API
(`POST /api/v1/admin/nodes/{id}/pool`, значения `paid` / `trial` / `quarantine` / `reserve`).
Подробности — `docs/ARCHITECTURE.md` §5.

## 6. Тесты и сборка — полная шпаргалка

```bash
# Сервер (JUnit 5 + Spring Boot, против H2 — не ловит миграции/gRPC-скейв, см. выше)
./gradlew test

# Нода-агент (Vitest)
cd agent && npm test

# Веб (сборка + проверка типов, включая админ-панель)
cd web && npm run build

# Единый fat-JAR сервера (включает web/dist)
./gradlew :server:bootJar -x test

# Android (нужен JDK 17-21 для Gradle-wrapper 8.10, см. android/README.md)
cd android && ./scripts/fetch-libxray.sh && ./gradlew :app:testDebugUnitTest && ./gradlew :app:lintDebug

# Desktop (проверка типов + тесты логики)
cd desktop && npm install && npm run typecheck && npm test

# E2E — требует реально поднятого стека (см. §1): docker compose up -d postgres,
# ./gradlew :server:bootRun, cd web && npm run dev — только после этого:
cd e2e && npm install && npx playwright install chromium && npm test
```

E2E-тесты в `e2e/tests/` покрывают:
- `landing.spec.ts` — проверка доступности цен и локализации для анонимных пользователей.
- `full-user-flow.spec.ts` — регистрация, активация тарифа, добавление/отзыв устройств, генерация крипто-инвойса, логаут и повторный вход.
- `admin.spec.ts` — проверка всех 5 разделов веб-панели администратора (дашборд, пользователи, ноды, платежи, политики).
- `nodes.spec.ts` — управление нодами и фильтрация.
- `tunnel.spec.ts` — запуск реального экземпляра `agent/` и `xray-core`, выдача реального VLESS/Reality конфига и проверка прохождения HTTP-трафика через поднятый туннель.
- `p2pRelay.spec.ts` — выдача списка реле, владение сигнальными сессиями и отказы брокера.
- `accessControl.spec.ts` — доступ между аккаунтами (чужие устройства, админ-API, подделанный JWT), лимиты тарифа, рефералы, устойчивость к некорректному вводу и канал диагностики.
- `global-teardown.mjs` — автоматическая очистка созданных тестовых аккаунтов из Postgres после прогона.

## 7. Документация проекта

| Файл | О чём |
|---|---|
| [`docs/README.md`](docs/README.md) | Навигатор по всей проектной, продуктовой и исследовательской документации |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Полное архитектурное и системное описание монорепозитория, транспорта, тарифов и рисков |
| [`docs/BUGS_AND_OBSERVATIONS.md`](docs/BUGS_AND_OBSERVATIONS.md) | Аудит безопасности, багов и архитектурных замечаний в кодовой базе |
| [`docs/P2P_RELAY.md`](docs/P2P_RELAY.md) | Подключение через p2p-участников: путь трафика, сигналинг, когда включается, ограничения |
| [`docs/DIAGNOSTICS.md`](docs/DIAGNOSTICS.md) | Сбор ошибок с нод и клиентов: как получить отчёт для анализа и как добавить новую точку сбора |
| [`docs/research/ru-blocking.md`](docs/research/ru-blocking.md) | Обоснование выбора транспортов (почему XHTTP+Reality, почему gRPC — запасной) |
| [`docs/stores-and-liability.md`](docs/stores-and-liability.md) | Чеклист для магазинов приложений и юридические риски (РФ-реклама, VPN-правила) |
| [`docs/google-play-readiness.md`](docs/google-play-readiness.md) | Маппинг чеклиста на код, черновик политики конфиденциальности, Data Safety форма |
| [`android/README.md`](android/README.md) | Сборка, архитектура и актуальное состояние Android-клиента |
| [`desktop/README.md`](desktop/README.md) | Сборка, архитектура и актуальное состояние Desktop-клиента |
| [`e2e/README.md`](e2e/README.md) | Запуск e2e-тестов пользовательского флоу, туннелей и админки |
