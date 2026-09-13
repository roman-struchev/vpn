---
layout: home

hero:
  name: "Aura VPN"
  text: "Инженерный портал & Спецификация API"
  tagline: "Высокоустойчивый к цензуре VPN-сервис: VLESS + XHTTP + Reality, mTLS gRPC Control Plane, Zero-Logs и независимый крипто-биллинг."
  image:
    src: /logo.svg
    alt: Aura VPN
  actions:
    - theme: brand
      text: ⚡ Спецификация API (REST & gRPC)
      link: /api-reference/overview
    - theme: alt
      text: 🏛 Архитектура системы
      link: /architecture/overview
    - theme: alt
      text: 📱 Клиентские приложения
      link: /clients/overview

features:
  - icon: 🛡
    title: "Антиблокировки DPI / ТСПУ"
    details: "Протокол VLESS с транспортом XHTTP (H2/H3) и маскировкой Reality под реальные TLS-домены. Smart Reconnect Backoff против ловушки адаптации."
    link: /architecture/anti-censorship
  - icon: ⚡
    title: "Исчерпывающий API Reference"
    details: "Полные спецификации Auth, User, Admin, Billing, Handoff SSO, Telegram Webhook и бидирекционального gRPC Stream с JSON-схемами и примерами cURL."
    link: /api-reference/overview
  - icon: 🔒
    title: "Zero-Logs & Изоляция ключей"
    details: "Журнал доступа access.log отключён. Уникальный UUID на тройку (device, user, node). База данных PostgreSQL 17 с 7 Flyway-миграциями."
    link: /architecture/security-and-data
  - icon: 🪙
    title: "Блокчейн-биллинг и Telegram Stars"
    details: "Целочисленные микро-USDT (Long int64), TRC-20, EVM L2 (Arbitrum, Base), уникальный микро-допуск ±0.0004 USDT и безопасный Web Handoff SSO."
    link: /architecture/billing-and-payments
  - icon: 🌐
    title: "Zero Inbound Ports Ноды"
    details: "VPN-ноды не открывают входящих портов управления: агент на TypeScript сам звонит на сервер по исходящему mTLS gRPC каналу."
    link: /architecture/node-orchestration
  - icon: 🔍
    title: "Аудит и правовая база"
    details: "Полный анализ ст. 14.3 КоАП РФ, правила Apple App Store 5.4, чеклист Google Play Data Safety и отчёт технического аудита."
    link: /research-and-compliance/ru-blocking
---

## Быстрый старт по разделам

::: tip ВАЖНОЕ ПРИМЕЧАНИЕ О СТАТУСЕ ДОКУМЕНТАЦИИ
Этот портал документации скомпилирован на основе реальной кодовой базы монорепозитория (`server/`, `agent/`, `android/`, `desktop/`, `web/`, `proto/`). Все файлы портала находятся в рабочей директории без фиксации в git-историю (`git commit` не выполнялся).
:::

### Карта системы

```mermaid
flowchart TD
    subgraph Clients["Клиентская экосистема"]
        A["Android App<br/>(Material 3 + libXray)"]
        D["Desktop App<br/>(Electron + React 18)"]
        W["Web SPA & Mini App<br/>(React 18 + Vite)"]
        ADM["Admin Console<br/>(/admin)"]
        TG["Telegram Bot<br/>(Webhook + Stars)"]
    end

    subgraph Backend["Центральный бэкенд (server/)"]
        S["Spring Boot 4.1 / Java 25<br/>REST API (:8080) & gRPC (:9090)"]
        DB[("PostgreSQL 17<br/>Flyway V1..V7")]
        S --- DB
    end

    subgraph Nodes["Парк VPN-нод (agent/)"]
        N1["Node Agent 1 (TS)<br/>xray-core (XHTTP)"]
        N2["Node Agent 2 (TS)<br/>xray-core (XHTTP + gRPC)"]
        N3["CDN Node<br/>xray-core (TLS behind CDN)"]
    end

    W -->|HTTPS REST| S
    ADM -->|HTTPS REST / JWT| S
    TG -->|HTTPS Webhook| S
    A -->|HTTPS REST & DoH| S
    D -->|HTTPS REST & DoH| S

    N1 -->|"Исходящий mTLS gRPC (:9090)"| S
    N2 -->|"Исходящий mTLS gRPC (:9090)"| S
    N3 -->|"Исходящий mTLS gRPC (:9090)"| S

    A -.->|VLESS+XHTTP+Reality| N1
    D -.->|VLESS+XHTTP+Reality| N2
    A -.->|VLESS+gRPC Fallback| N2
    D -.->|VLESS via CDN Fallback| N3
```

---

### Навигатор по порталу

| Раздел | Ссылка | Ключевые темы |
|---|---|---|
| **API Reference** | [/api-reference/overview](/api-reference/overview) | **Главный раздел**: REST DTO, cURL, коды ошибок, JWT, OpenAPI 3.1, gRPC `agent.proto`. |
| **Аутентификация** | [/api-reference/auth-api](/api-reference/auth-api) | `/register`, `/login`, `/device` (анонимный UUID), `/google`, `/telegram`, `/upgrade`. |
| **Пользовательский API** | [/api-reference/user-api](/api-reference/user-api) | `/profile`, `/devices`, `/subscription/links`, `/regions`, `/tariffs`, `/balance-history`. |
| **Биллинг & Handoff** | [/api-reference/billing-api](/api-reference/billing-api) | `/billing/invoice`, `/purchase`, `/claim-tx`, Web Handoff SSO `/auth/web-handoff`. |
| **Control Plane API** | [/api-reference/admin-api](/api-reference/admin-api) | `/admin/dashboard`, `/nodes`, `/nodes/bootstrap-token`, `/users`, `/policies`, `/crypto/reconcile`. |
| **gRPC Контракт** | [/api-reference/grpc-agent-proto](/api-reference/grpc-agent-proto) | Спецификация `agent.proto`, стрим метрик, добавление пользователей на лету. |
| **Архитектура** | [/architecture/overview](/architecture/overview) | Архитектурный обзор, VLESS+XHTTP+Reality, mTLS оркестрация, Zero-Logs. |
| **Клиенты** | [/clients/overview](/clients/overview) | Специфика Android (VpnService), Desktop (Electron proxy), Web (SPA). |
| **Исследования** | [/research-and-compliance/ru-blocking](/research-and-compliance/ru-blocking) | Механика ТСПУ, модель трёх сигналов по «И», ст. 14.3 КоАП, Google Play Data Safety. |
| **Технический аудит** | [/audit/bugs-and-observations](/audit/bugs-and-observations) | Аудит безопасности: баг double-spend в крипто-платежах, bypass блокировок, 500 NPE. |
