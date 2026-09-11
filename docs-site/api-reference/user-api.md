# User API: Профиль, Устройства, Подписки и Регионы

Контроллер: `UserController.java`.  
Базовый путь: `/api/v1/user`.  
Все эндпоинты в данном разделе требуют авторизации <span class="api-badge api-auth-bearer">Bearer JWT</span>.

---

## 1. GET /api/v1/user/profile

Возвращает агрегированную информацию о текущем профиле: идентификатор, баланс в микро-USDT, реферальные ссылки (веб и Telegram deep-link), статус привязки Telegram Stars, признак гостевого аккаунта (`isGuest`), факт использования триала (`hasUsedTrial`) и детальное состояние активной подписки.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `GET /api/v1/user/profile`

### Ответ (200 OK — JSON)

```json
{
  "id": 12,
  "email": "engineer@example.com",
  "role": "USER",
  "balanceUsdtMicro": 15000000,
  "referralCode": "REF99XYZ",
  "referralLink": "https://vpn.struchev.site/?ref=REF99XYZ",
  "referralTelegramLink": "https://t.me/MyVpnBot?start=REF99XYZ",
  "telegramLinked": true,
  "isGuest": false,
  "hasActiveSubscription": true,
  "hasUsedTrial": true,
  "subscription": {
    "id": 105,
    "tariffId": "pro",
    "trafficUsedBytes": 14285901000,
    "trafficLimitBytes": 107374182400,
    "expiresAt": "2026-10-11T20:00:00Z"
  }
}
```

::: note ОСОБЕННОСТЬ ПОЛЯ `subscription`
Если у пользователя нет активной подписки, сервер возвращает явный JSON `null`: `"subscription": null`.
Это исключает ошибку некорректного парсинга пустого объекта в клиентских SPA.
:::

### Пример cURL

```bash
curl -X GET "https://vpn.struchev.site/api/v1/user/profile" \
  -H "Authorization: Bearer <USER_JWT_TOKEN>"
```

---

## 2. GET /api/v1/user/tariffs

Возвращает список доступных для покупки тарифов с ценами в микро-USDT, лимитами устройств и квотами трафика.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span> (или Public)
* **Метод / Путь**: `GET /api/v1/user/tariffs`

### Ответ (200 OK — Массив тарифов)

```json
[
  {
    "id": "trial",
    "name": "Пробный",
    "priceMonthlyUsdtMicro": 0,
    "priceAnnualUsdtMicro": 0,
    "trafficLimitBytes": 1073741824,
    "maxDevices": 1,
    "durationDays": 3,
    "isActive": true
  },
  {
    "id": "basic",
    "name": "Basic",
    "priceMonthlyUsdtMicro": 1000000,
    "priceAnnualUsdtMicro": 10000000,
    "trafficLimitBytes": 21474836480,
    "maxDevices": 2,
    "durationDays": 30,
    "isActive": true
  },
  {
    "id": "pro",
    "name": "Pro",
    "priceMonthlyUsdtMicro": 2000000,
    "priceAnnualUsdtMicro": 20000000,
    "trafficLimitBytes": 107374182400,
    "maxDevices": 5,
    "durationDays": 30,
    "isActive": true
  }
]
```

---

## 3. GET /api/v1/user/regions

Возвращает список доступных серверных регионов (Нидерланды, Германия, Финляндия и др.), в которых есть работающие ноды (`status = 'ONLINE'`), с оценкой нагрузки (`LOW`, `MEDIUM`, `HIGH`). Используется селектором регионов в нативных клиентах Android и Desktop.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `GET /api/v1/user/regions`

### Ответ (200 OK)

```json
{
  "regions": [
    {
      "code": "nl-ams",
      "name": "Амстердам (Нидерланды)",
      "flag": "🇳🇱",
      "congestion": "LOW",
      "activeNodeCount": 4
    },
    {
      "code": "de-fra",
      "name": "Франкфурт (Германия)",
      "flag": "🇩🇪",
      "congestion": "MEDIUM",
      "activeNodeCount": 2
    },
    {
      "code": "fi-hel",
      "name": "Хельсинки (Финляндия)",
      "flag": "🇫🇮",
      "congestion": "LOW",
      "activeNodeCount": 2
    }
  ]
]
```

---

## 4. GET /api/v1/user/subscription/links (VLESS Конфигурации)

Генерирует и возвращает персонализированный список ссылок `vless://...` для нативных клиентов сервиса. Вызов защищён алгоритмом **Anti-Enumeration**: если один аккаунт запрашивает ссылки более чем с 5 разных IP-адресов за 60 минут, ключи доступа на нодах немедленно ротируются.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `GET /api/v1/user/subscription/links`
* **Query Параметры**:
  * `region` (*optional*, `string`): код запрашиваемого региона (например, `nl-ams`). Если ноды в данном регионе недоступны, сервер автоматически возвращает глобальный пул (`requestedRegionAvailable: false`).

### Ответ (200 OK — Региональный запрос)

```json
{
  "count": 2,
  "requestedRegion": "nl-ams",
  "requestedRegionAvailable": true,
  "links": [
    "vless://9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d@194.87.12.34:443?type=xhttp&path=%2Fapi%2Fv1%2Fstream&host=dl.google.com&security=reality&pbk=Z1X...&fp=firefox&sni=dl.google.com&sid=a1b2c3d4#NextGen-AMS-01",
    "vless://9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d@194.87.12.35:443?type=xhttp&path=%2Fapi%2Fv1%2Fstream&host=dl.google.com&security=reality&pbk=Z1X...&fp=edge&sni=dl.google.com&sid=a1b2c3d4#NextGen-AMS-02"
  ]
}
```

### Коды ошибок

| HTTP Код | Ошибка | Причина |
|---|---|---|
| `400 Bad Request` | `User has no active subscription` | Подписка отсутствует или истекла |
| `403 Forbidden` | `Account suspended` | Аккаунт заблокирован администратором |

---

## 5. GET /api/v1/user/devices

Возвращает список всех зарегистрированных устройств пользователя (платформа, дата первого добавления, статус активности).

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `GET /api/v1/user/devices`

### Ответ (200 OK)

```json
[
  {
    "id": 14,
    "deviceName": "MacBook Pro M3",
    "platform": "MACOS",
    "isActive": true,
    "createdAt": "2026-09-01T14:20:00Z",
    "lastSeenAt": "2026-09-11T19:30:00Z"
  },
  {
    "id": 15,
    "deviceName": "Pixel 9 Pro",
    "platform": "ANDROID",
    "isActive": true,
    "createdAt": "2026-09-05T09:15:00Z",
    "lastSeenAt": "2026-09-11T20:10:00Z"
  }
]
```

---

## 6. POST /api/v1/user/devices (Регистрация устройства)

Регистрирует новое устройство пользователя в рамках лимита по активному тарифу (`maxDevices`). При превышении лимита старейшее неактивное устройство отзывается, либо запрос отклоняется (в зависимости от политики тарифа).

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `POST /api/v1/user/devices`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `deviceName` | `string` | Да | Человекочитаемое имя устройства (например: "Work Laptop") |
| `platform` | `string` | Да | Платформа: `ANDROID`, `MACOS`, `WINDOWS`, `LINUX`, `IOS` |

```json
{
  "deviceName": "ThinkPad Carbon",
  "platform": "LINUX"
}
```

### Ответ (200 OK)

```json
{
  "status": "CREATED",
  "deviceId": 16,
  "deviceName": "ThinkPad Carbon",
  "platform": "LINUX"
}
```

---

## 7. DELETE /api/v1/user/devices/{deviceId} (Отзыв устройства)

Отзывает регистрацию устройства. Сервер немедленно отправляет `ClientDeltaUpdate` (gRPC) на все ноды, удаляя UUID ключа этого устройства из памяти `xray-core` без разрыва остальных сессий.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `DELETE /api/v1/user/devices/{deviceId}`

### Ответ (200 OK)

```json
{
  "status": "REVOKED",
  "deviceId": 16
}
```

---

## 8. POST /api/v1/user/devices/{deviceId}/touch

Вызывается нативными клиентами при успешном установлении туннеля. Обновляет временную метку последней активности устройства (`lastSeenAt`) для предотвращения автоматической очистки неактивных слотов.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `POST /api/v1/user/devices/{deviceId}/touch`

### Ответ (200 OK)

```json
{
  "status": "OK"
}
```

*При возврате `404 Not Found` клиент обязан повторить регистрацию через `POST /api/v1/user/devices`.*

---

## 9. POST /api/v1/user/telegram-link (Привязка Telegram)

Генерирует одноразовый связующий код и deep-link для Telegram-бота, позволяя прикрепить аккаунт мессенджера к существующему веб-аккаунту для оплаты через Telegram Stars.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span>
* **Метод / Путь**: `POST /api/v1/user/telegram-link`

### Ответ (200 OK)

```json
{
  "code": "lnk_98ef1234abcd",
  "deepLink": "https://t.me/MyVpnBot?start=link_lnk_98ef1234abcd"
}
```
