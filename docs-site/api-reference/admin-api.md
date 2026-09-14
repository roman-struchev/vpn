# Admin API: Управление нодами, пользователями и политиками

Контроллер: `AdminController.java`.  
Базовый путь: `/api/v1/admin`.  
Все эндпоинты в данном разделе требуют роли администратора: <span class="api-badge api-auth-admin">Admin JWT</span> (`role == 'ADMIN'`). При попытке доступа с ролью `USER` сервер возвращает `403 Forbidden`.

---

## 1. GET /api/v1/admin/dashboard (Сводка и деградация ТСПУ)

Возвращает глобальные метрики сервиса (пользователи, активные подписки, остаток на счетах, суммарный трафик, состояние нод, выплаты реферальной программы) и агрегированную матрицу **деградации сети в разрезе операторов и регионов** за последние 24 часа.

* **Метод / Путь**: `GET /api/v1/admin/dashboard`
* **Аутентификация**: <span class="api-badge api-auth-admin">Admin JWT</span>

### Ответ (200 OK — JSON)

```json
{
  "totalUsers": 1420,
  "activeSubscriptions": 890,
  "totalBalanceUsdt": 12450.50,
  "totalTrafficUsedBytes": 54975581388800,
  "onlineNodes": 18,
  "totalNodes": 20,
  "totalReferralBonusesPaidUsdt": 1860.00,
  "totalReferralBonusesCount": 240,
  "telemetryDegradation": [
    {
      "operator": "MTS",
      "region": "RU-MOW",
      "transport": "xhttp",
      "totalReports": 5400,
      "successCount": 5373,
      "failureReportCount": 27,
      "failureRatePercent": 0.50,
      "avgConnectTimeMs": 145,
      "whitelistSuspected": false
    },
    {
      "operator": "Rostelecom",
      "region": "RU-SPE",
      "transport": "grpc",
      "totalReports": 310,
      "successCount": 220,
      "failureReportCount": 90,
      "failureRatePercent": 29.03,
      "avgConnectTimeMs": 420,
      "whitelistSuspected": false
    }
  ]
}
```

---

## 2. Управление пользователями

### 2.1. GET /api/v1/admin/users
Возвращает список всех зарегистрированных пользователей, включая привязанные устройства, рефералов, заработок и активные подписки:

```json
[
  {
    "id": 12,
    "email": "engineer@example.com",
    "telegramId": 12345678,
    "deviceUuid": null,
    "role": "USER",
    "status": "ACTIVE",
    "balanceUsdtMicro": 15000000,
    "referralCode": "REF99XYZ",
    "referredByUserId": 5,
    "deviceCount": 2,
    "referralCount": 8,
    "referralEarningsUsdtMicro": 24000000,
    "activeSubscription": {
      "id": 105,
      "tariffId": "pro",
      "currentPeriodEnd": "2026-10-11T20:00:00Z",
      "trafficUsedBytes": 14285901000,
      "trafficLimitBytes": 107374182400
    }
  }
]
```

### 2.2. POST /api/v1/admin/users/{userId}/balance (Корректировка баланса)
Позволяет вручную пополнить или списать баланс пользователя с записью в финансовый журнал `balance_entries` (`type = 'MANUAL_ADJUSTMENT'`).

* **Request Body**:
  ```json
  {
    "amountMicro": 5000000,
    "description": "Бонус за помощь в тестировании"
  }
  ```
* **Ответ (200 OK)**:
  ```json
  {
    "userId": 12,
    "amountMicro": 5000000,
    "newBalanceMicro": 20000000
  }
  ```

### 2.3. POST /api/v1/admin/users/{userId}/status (Блокировка аккаунта)
Изменяет статус пользователя (`ACTIVE`, `BLOCKED`). При блокировке сервер автоматически пушит `ConfigSync` на все ноды, отзывая ключи доступа.

* **Request Body**: `{"status": "BLOCKED"}`
* **Ответ (200 OK)**: `{"userId": 12, "status": "BLOCKED"}`

### 2.4. POST /api/v1/admin/users/{userId}/subscription/extend (Продление подписки)
* **Request Body**: `{"days": 30}`
* **Ответ (200 OK)**:
  ```json
  {
    "subscriptionId": 105,
    "newPeriodEnd": "2026-11-10T20:00:00Z",
    "extendedDays": 30
  }
  ```

### 2.5. POST /api/v1/admin/users/{userId}/subscription/temporary-tariff (Временный тариф)
Временно переводит пользователя на другой тариф на заданное число дней — например, компенсация или промо-апгрейд — не трогая его реальный биллинговый тариф и автопродление. Пока действует override, именно он определяет лимит устройств, доступный пул серверов и лимит трафика (лимит трафика подписки сразу выставляется по квоте нового тарифа); реальный тариф остаётся прежним для авто-продления. Повторный вызов, пока override уже активен, заменяет его (продлевает/меняет тариф), не теряя исходный лимит трафика для отката. Откат — автоматический, по истечении `days` (минутный джоб `QuotaEnforcementTask`), либо вручную через `.../temporary-tariff/cancel`.

* **Request Body**: `{"tariffId": "pro", "days": 7}`
* **Ответ (200 OK)**:
  ```json
  {
    "subscriptionId": 105,
    "overrideTariffId": "pro",
    "overrideExpiresAt": "2026-09-21T20:00:00Z"
  }
  ```

### 2.6. POST /api/v1/admin/users/{userId}/subscription/temporary-tariff/cancel (Отмена временного тарифа)
Немедленно откатывает активный override — реальный тариф и лимит трафика восстанавливаются до значений, которые были до выдачи.

* **Ответ (200 OK)**: `{"subscriptionId": 105, "cancelled": true}`

---

## 3. Управление нодами и оркестрация

### 3.1. POST /api/v1/admin/nodes/bootstrap-token (Выпуск токена ноды)
Генерирует криптографический bootstrap-токен для инициализации новой VPN-ноды скриптом установки `scripts/install-node.sh`. Токен можно использовать многократно, пока не истёк `validHours` — удобно, чтобы одним токеном поднять сразу несколько VPS.

* **Query Параметры**:
  * `pool`: целевой пул (`paid`, `trial`, `quarantine`) — default: `paid`
  * `type`: тип узла (`direct`, `cdn`) — default: `direct`
  * `validHours`: время действия токена в часах — default: `24`

* **Ответ (200 OK)**:
  ```json
  {
    "token": "bst_9f14b620e79148d98d844c8032c1fa01",
    "assignedPool": "paid",
    "assignedType": "direct",
    "expiresAt": "2026-09-12T21:00:00Z"
  }
  ```

### 3.2. GET /api/v1/admin/nodes (Список нод и телеметрия железа)
Возвращает список всех нод с данными последнего heartbeat (CPU tick-diff, RAM, аптайм, активные пользователи, throughput):

```json
[
  {
    "id": 1,
    "hostname": "node-ams-01.struchev.site",
    "publicIp": "194.87.12.34",
    "region": "nl-ams",
    "asn": "AS24940",
    "pool": "paid",
    "type": "direct",
    "status": "ONLINE",
    "cpuPercent": 14.5,
    "cpuCount": 4,
    "memoryUsedBytes": 1073741824,
    "memoryTotalBytes": 4294967296,
    "recentEgressBytesPerSec": 45000000,
    "activeConnections": 185,
    "lastHeartbeat": "2026-09-11T21:15:30Z"
  }
]
```

### 3.3. POST /api/v1/admin/nodes/{nodeId}/pool (Перемещение ноды в пул)
Переводит узел в пул `trial`, `paid` или `quarantine`. Изменение немедленно синхронизируется на все клиентские селекторы.

* **Query Параметр**: `pool=quarantine`
* **Ответ (200 OK)**: `{"nodeId": 1, "pool": "quarantine"}`

### 3.4. POST /api/v1/admin/nodes/{nodeId}/command (Управляющие команды агенту)
Отправляет бинарную управляющую команду по gRPC каналу агента ноды:
* `COMMAND_TYPE_RESTART_XRAY`: мягкий перезапуск дочернего процесса `xray-core` без разрыва mTLS-сессии агента.
* `COMMAND_TYPE_RECONCILE`: принудительная сверка списка клиентов ноды с базой данных сервера.
* `COMMAND_TYPE_DRAIN`: вывод ноды из обслуживания (новые клиенты не подключаются, старые дорабатывают).

```bash
curl -X POST "https://vpn.struchev.site/api/v1/admin/nodes/1/command?type=COMMAND_TYPE_RESTART_XRAY" \
  -H "Authorization: Bearer <ADMIN_JWT_TOKEN>"
```

---

## 4. Политики транспорта и регламентные задачи

### 4.1. GET /api/v1/admin/policies & POST /api/v1/admin/policies
Просмотр и изменение динамических политик `transport_policy` (TLS SNI, Reality destination, Fallback port). При сохранении сервер автоматически пушит обновление конфигураций на весь парк нод:

```json
{
  "id": 1,
  "transport": "xhttp",
  "realityDest": "dl.google.com:443",
  "realityServerNames": ["dl.google.com"],
  "tlsFingerprint": "firefox",
  "xhttpPath": "/api/v1/stream",
  "grpcFallbackEnabled": true,
  "grpcFallbackPort": 8443
}
```

### 4.2. POST /api/v1/admin/tasks/enforce-quotas
Ручной запуск фоновой задачи проверки квот: отключает истекшие подписки и клиентов, превысивших месячный лимит трафика.

### 4.3. POST /api/v1/admin/crypto/reconcile (Ручная сверка платежа)
Позволяет администратору принудительно провести блокчейн-платеж, если автоматический сканер не распознал перевод:

```json
{
  "chain": "TRC20",
  "amountMicro": 10342000,
  "txHash": "manual_reconcile_998124"
}
```
