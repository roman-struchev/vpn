# Billing API: Пополнение, Инвойсы, Покупка и Клейм

Контроллеры: `UserController.java`, `BillingService.java`, `BlockchainPaymentService.java`.  
Базовый путь: `/api/v1/user/billing`.  
Все эндпоинты требуют авторизации <span class="api-badge api-auth-bearer">Bearer JWT</span>.

---

## 1. POST /api/v1/user/billing/invoice (Создание крипто-инвойса)

Формирует депозитный инвойс для пополнения лицевого счета в криптовалюте USDT. Чтобы однозначно идентифицировать входящий платеж на едином системном кошельке без использования индивидуальных смарт-контрактов, бэкенд вычисляет уникальный случайный микро-хвост (алгоритм толерантности).

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `POST /api/v1/user/billing/invoice`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание | Пример |
|---|---|:---:|---|---|
| `baseAmountUsdtMicro` | `integer (int64)` | Да | Базовая сумма пополнения в микро-USDT | `10000000` ($10 USDT) |
| `chain` | `string` | Нет | Блокчейн: `TRON`, `ETHEREUM`, `BASE`, `ARBITRUM`, `POLYGON` (default: `TRON`) | `"TRON"` |

```json
{
  "baseAmountUsdtMicro": 10000000,
  "chain": "TRON"
}
```

### Ответ (200 OK — CryptoInvoice DTO)

```json
{
  "invoiceId": 789,
  "chain": "TRON",
  "recipientAddress": "TYDzsYUEpvnYmQK4zGP9s217x5MRxurGeB",
  "expectedAmountUsdtMicro": 10342000,
  "expectedAmountUsdt": 10.342,
  "deltaStepMicro": 1000,
  "toleranceMinMicro": 10341600,
  "toleranceMaxMicro": 10342400,
  "status": "PENDING",
  "expiresAt": "2026-09-12T20:30:00Z"
}
```

::: tip МАТЕМАТИКА МИКРО-ТОЛЕРАНТНОСТИ
* Базовая сумма: $10.00$ USDT (`10000000 micro-USDT`).
* Добавленный псевдослучайный хвост: $k = 342$, шаг $\Delta = 1\,000\text{ micro-USDT}$ ($0.001$ USDT).
* Ожидаемая сумма к переводу: $10.342000$ USDT (`10342000 micro-USDT`).
* Окно допуска: $\pm 400\text{ micro-USDT}$ ($\pm 0.0004$ USDT), диапазон `[10341600, 10342400]`.
* Окна инвойсов с разными $k$ **не пересекаются**, что гарантирует отсутствие коллизий между пользователями.
:::

### Пример cURL

```bash
curl -X POST "https://vpn.struchev.site/api/v1/user/billing/invoice" \
  -H "Authorization: Bearer <USER_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "baseAmountUsdtMicro": 10000000,
    "chain": "TRON"
  }'
```

---

## 2. POST /api/v1/user/billing/purchase (Списание с баланса и покупка подписки)

Активирует или продлевает подписку на выбранный тариф, списывая средства с баланса пользователя в БД. Поддерживает месячный и годовой биллинговый цикл (скидка ~17% при годовой оплате).

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `POST /api/v1/user/billing/purchase`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание | Пример |
|---|---|:---:|---|---|
| `tariffId` | `string` | Да | Идентификатор тарифа (`basic`, `pro`, `trial`) | `"pro"` |
| `isAnnual` | `boolean` | Нет | Флаг годового периода (скидка ~17%) | `true` |

```json
{
  "tariffId": "pro",
  "isAnnual": true
}
```

### Успешный ответ (200 OK)

```json
{
  "status": "SUCCESS",
  "subscriptionId": 210,
  "tariffId": "pro",
  "expiresAt": "2027-09-11T21:00:00Z",
  "trafficLimitBytes": 107374182400
}
```

### Ответ при нехватке средств (400 Bad Request — Структурированный Shortfall)

Сервер возвращает точные финансовые данные дефицита в микро-USDT, что позволяет клиентским интерфейсам сразу открыть модальное окно пополнения на недостающую сумму:

```json
{
  "error": "INSUFFICIENT_BALANCE",
  "requiredUsdtMicro": 20000000,
  "currentUsdtMicro": 15000000,
  "shortfallUsdtMicro": 5000000
}
```

### Коды ошибок

| HTTP Код | Поле error | Описание |
|---|---|---|
| `400 Bad Request` | `INSUFFICIENT_BALANCE` | Недостаточно средств на балансе лицевого счета |
| `400 Bad Request` | `Trial tariff already used` | Повторная попытка активации бесплатного триала |
| `400 Bad Request` | `Tariff not found or inactive` | Неверный идентификатор тарифа |

---

## 3. POST /api/v1/user/billing/claim-tx (Ручной клейм транзакции)

Механизм мгновенного зачисления «Я оплатил, вот хеш транзакции». Позволяет пользователю не ожидать следующего 30-секундного цикла сканера блокчейна:

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `POST /api/v1/user/billing/claim-tx`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание | Пример |
|---|---|:---:|---|---|
| `txHash` | `string` | Да | Хеш транзакции в блокчейне | `"a4f89d...12cb"` |
| `amountMicro` | `integer (int64)` | Да | Переведенная сумма в микро-USDT | `10342000` |
| `chain` | `string` | Нет | Блокчейн сеть (default: `TRON`) | `"TRON"` |

```json
{
  "chain": "TRON",
  "txHash": "9bfa58d249d6389710f279169622d1df7b6ebba5a0df95a5fbc40d4ff58f6920",
  "amountMicro": 10342000
}
```

### Ответ (200 OK)

```json
{
  "status": "CLAIMED",
  "amountMicro": 10342000,
  "balanceAfterMicro": 25342000,
  "txHash": "9bfa58d249d6389710f279169622d1df7b6ebba5a0df95a5fbc40d4ff58f6920"
}
```

### Коды ошибок

| HTTP Код | Ошибка | Причина |
|---|---|---|
| `400 Bad Request` | `Transaction already processed` | Хеш транзакции уже использован ранее |
| `400 Bad Request` | `Transaction not found or unconfirmed` | Транзакция не найдена в блокчейне или ожидает подтверждения |
| `400 Bad Request` | `Recipient address mismatch` | Перевод отправлен на чужой кошелёк |

---

## 4. GET /api/v1/user/invoices (Журнал инвойсов)

Возвращает список всех криптовалютных инвойсов пользователя с историей статусов (`PENDING`, `PAID`, `EXPIRED`).

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `GET /api/v1/user/invoices`

### Ответ (200 OK)

```json
[
  {
    "id": 789,
    "chain": "TRON",
    "recipientAddress": "TYDzsYUEpvnYmQK4zGP9s217x5MRxurGeB",
    "baseAmountUsdtMicro": 10000000,
    "expectedAmountUsdtMicro": 10342000,
    "actualAmountUsdtMicro": 10342000,
    "status": "PAID",
    "txHash": "9bfa58d249d6389710f279169622d1df...",
    "createdAt": "2026-09-11T18:00:00Z",
    "expiresAt": "2026-09-12T18:00:00Z"
  }
]
```

---

## 5. GET /api/v1/user/balance-history (Бухгалтерский журнал движения средств)

Возвращает полный реестр операций по лицевому счёту (`balance_entries`): входящие депозиты, оплаты тарифов, реферальные начисления (15% за оплату реферала и 10% велком-бонус) и ручные корректировки администратора.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `GET /api/v1/user/balance-history`

### Ответ (200 OK)

```json
[
  {
    "id": 1402,
    "type": "SUBSCRIPTION_PAYMENT",
    "amountUsdtMicro": -20000000,
    "balanceAfterMicro": 5342000,
    "description": "Покупка тарифа Pro (годовой)",
    "referenceId": "sub_210",
    "createdAt": "2026-09-11T21:00:00Z"
  },
  {
    "id": 1395,
    "type": "REFERRAL_BONUS",
    "amountUsdtMicro": 3000000,
    "balanceAfterMicro": 25342000,
    "description": "Реферальное вознаграждение 15% за пользователя ID 92",
    "referenceId": "ref_92_sub_209",
    "createdAt": "2026-09-11T19:45:00Z"
  },
  {
    "id": 1380,
    "type": "DEPOSIT",
    "amountUsdtMicro": 10342000,
    "balanceAfterMicro": 22342000,
    "description": "Пополнение баланса TRC-20 USDT",
    "referenceId": "9bfa58d249d6389710f279169622d1df...",
    "createdAt": "2026-09-11T18:05:00Z"
  }
]
```
