# Auth API: Аутентификация, Регистрация и Upgrade

Контроллеры: `AuthController.java`, `WebHandoffController.java`.  
Базовый путь: `/api/v1/auth`.

---

## 1. POST /api/v1/auth/register

Регистрация нового пользователя по связке email и пароль. Новому пользователю автоматически начисляется пробный тариф `trial` (если ранее не использовался) и генерируется персональный реферальный код.

* **Аутентификация**: <span class="api-badge api-auth-public">Public</span> (не требуется)
* **Метод / Путь**: `POST /api/v1/auth/register`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание | Ограничения |
|---|---|:---:|---|---|
| `email` | `string` | Да | Электронная почта пользователя | Валидный RFC 5322 адрес, уникальный |
| `password` | `string` | Да | Пароль пользователя в открытом виде | Мин. 8 символов |
| `referralCode` | `string` | Нет | Реферальный код пригласившего пользователя | 8-значный буквенно-цифровой код |

```json
{
  "email": "engineer@example.com",
  "password": "StrongPassword2026!",
  "referralCode": "REF42ABC"
}
```

### Ответ (200 OK — AuthResponse)

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMiIsImVtYWlsIjoiZW5naW5lZXJAZXhhbXBsZS5jb20iLCJyb2xlIjoiVVNFUiIsImlhdCI6MTc4OTE1MDAwMCwiZXhwIjoxNzkxNzQyMDAwfQ.abcdef...",
  "userId": 12,
  "email": "engineer@example.com",
  "role": "USER",
  "referralCode": "REF99XYZ"
}
```

### Коды ошибок

| HTTP Код | Ошибка | Причина |
|---|---|---|
| `400 Bad Request` | `Email is already in use` | Учётная запись с таким email уже существует в БД |
| `400 Bad Request` | `Invalid email format` | Передан некорректный адрес электронной почты |
| `400 Bad Request` | `Password must be at least 8 characters` | Пароль короче минимальной длины |

### Пример cURL

```bash
curl -X POST "https://vpn.struchev.site/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "engineer@example.com",
    "password": "StrongPassword2026!",
    "referralCode": "REF42ABC"
  }'
```

---

## 2. POST /api/v1/auth/login

Аутентификация существующего пользователя по email и паролю. Если в запросе передан параметр `deviceUuid` (гостевой профиль на текущем устройстве), сервер автоматически выполняет **бесшовное слияние** (`GuestMergeService`): переносит баланс с гостевого аккаунта на целевой и удаляет виртуальную запись гостя.

* **Аутентификация**: <span class="api-badge api-auth-public">Public</span> (не требуется)
* **Метод / Путь**: `POST /api/v1/auth/login`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание | Ограничения |
|---|---|:---:|---|---|
| `email` | `string` | Да | Зарегистрированный email пользователя | Должен существовать в БД |
| `password` | `string` | Да | Пароль аккаунта | Соответствует хешу BCrypt |
| `deviceUuid` | `string` | Нет | UUID устройства с гостевым триалом для слияния | 36-символьный UUIDv4 |

```json
{
  "email": "engineer@example.com",
  "password": "StrongPassword2026!",
  "deviceUuid": "e7b91d22-8354-4f01-9876-123456789abc"
}
```

### Ответ (200 OK — AuthResponse)

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 12,
  "email": "engineer@example.com",
  "role": "USER",
  "referralCode": "REF99XYZ"
}
```

### Коды ошибок

| HTTP Код | Ошибка | Причина |
|---|---|---|
| `400 Bad Request` | `Invalid email or password` | Неверные учётные данные пользователя |
| `403 Forbidden` | `User account is BLOCKED` | Пользователь заблокирован администратором |

### Пример cURL

```bash
curl -X POST "https://vpn.struchev.site/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "engineer@example.com",
    "password": "StrongPassword2026!",
    "deviceUuid": "e7b91d22-8354-4f01-9876-123456789abc"
  }'
```

---

## 3. POST /api/v1/auth/device (Анонимный 1-Click Trial)

Точка входа для быстрого подключения мобильного и десктопного приложения без заполнения формы регистрации. Сервер находит существующий или атомарно создает виртуальный профиль `User` с синтетическим email вида `device_<uuid>@device.local` и выдаёт 3-дневный пробный период.

* **Аутентификация**: <span class="api-badge api-auth-public">Public</span> (не требуется)
* **Метод / Путь**: `POST /api/v1/auth/device`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание | Ограничения |
|---|---|:---:|---|---|
| `deviceUuid` | `string` | Да | Локально сгенерированный UUID клиентского устройства | Валидный UUIDv4 |
| `referralCode` | `string` | Нет | Реферальный код (если приложение открыто по диплинку) | Строка |

```json
{
  "deviceUuid": "e7b91d22-8354-4f01-9876-123456789abc",
  "referralCode": "REF42ABC"
}
```

### Ответ (200 OK — AuthResponse)

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 88,
  "email": "device_e7b91d22-8354-4f01-9876-123456789abc@device.local",
  "role": "USER",
  "referralCode": "REF88DEV"
}
```

### Пример cURL

```bash
curl -X POST "https://vpn.struchev.site/api/v1/auth/device" \
  -H "Content-Type: application/json" \
  -d '{
    "deviceUuid": "e7b91d22-8354-4f01-9876-123456789abc"
  }'
```

---

## 4. POST /api/v1/auth/upgrade (Привязка гостя к постоянному профилю)

Преобразует текущий анонимный гостевой аккаунт (`isGuest: true`) в полноценный аккаунт с реальным email и паролем без потери баланса, существующих подключений и ключей доступа.

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span> (токен гостевого пользователя)
* **Метод / Путь**: `POST /api/v1/auth/upgrade`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание | Ограничения |
|---|---|:---:|---|---|
| `email` | `string` | Да | Постоянный email пользователя | Уникальный в системе |
| `password` | `string` | Да | Пароль для последующего входа | Мин. 8 символов |

```json
{
  "email": "converted.user@example.com",
  "password": "NewPermanentPassword2026!"
}
```

### Ответ (200 OK — AuthResponse)

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 88,
  "email": "converted.user@example.com",
  "role": "USER",
  "referralCode": "REF88DEV"
}
```

### Коды ошибок

| HTTP Код | Ошибка | Причина |
|---|---|---|
| `400 Bad Request` | `Email already registered` | Указанный email занят другим пользователем |
| `400 Bad Request` | `User is not a guest account` | Учётная запись уже имеет привязанные учетные данные |
| `401 Unauthorized` | `Full authentication is required` | Запрос выполнен без JWT токена |

### Пример cURL

```bash
curl -X POST "https://vpn.struchev.site/api/v1/auth/upgrade" \
  -H "Authorization: Bearer <GUEST_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "converted.user@example.com",
    "password": "NewPermanentPassword2026!"
  }'
```

---

## 5. POST /api/v1/auth/google (Google Sign-In)

Авторизация через официальный Google Credential Manager (Android) или локальный OAuth2 loopback (Desktop). Сервер валидирует криптографическую подпись Google ID Token через библиотеку `google-api-client`.

* **Аутентификация**: <span class="api-badge api-auth-public">Public</span> (не требуется)
* **Метод / Путь**: `POST /api/v1/auth/google`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `idToken` | `string` | Да | JWT ID Token, выданный Google OAuth |
| `deviceUuid` | `string` | Нет | UUID устройства для слияния гостевого баланса |
| `referralCode` | `string` | Нет | Реферальный код пригласившего пользователя |

```json
{
  "idToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6Ij...",
  "deviceUuid": "e7b91d22-8354-4f01-9876-123456789abc"
}
```

### Ответ (200 OK — AuthResponse)

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 45,
  "email": "alice@gmail.com",
  "role": "USER",
  "referralCode": "REF45GOO"
}
```

---

## 6. POST /api/v1/auth/telegram (Telegram Mini App Auth)

Аутентификация пользователя внутри веб-интерфейса Telegram Mini App. Сервер валидирует криптографический HMAC-SHA256 хеш строки `initData` с использованием секрета бота `HMAC_SHA256("WebAppData", botToken)`.

* **Аутентификация**: <span class="api-badge api-auth-public">Public</span> (не требуется)
* **Метод / Путь**: `POST /api/v1/auth/telegram`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `initData` | `string` | Да | Сырая строка `window.Telegram.WebApp.initData` |
| `referralCode` | `string` | Нет | Реферальный код (параметр `start_param` из Mini App) |

```json
{
  "initData": "query_id=AAHdF6IQAAAAAN0XohCxxxx&user=%7B%22id%22%3A12345678%2C%22first_name%22%3A%22Ivan%22%7D&auth_date=1789150000&hash=5a2f...",
  "referralCode": "REF42ABC"
}
```

### Ответ (200 OK — AuthResponse)

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 56,
  "email": "tg_12345678@telegram.local",
  "role": "USER",
  "referralCode": "REF56TG"
}
```

---

## 7. POST /api/v1/auth/web-handoff (Выпуск SSO-кода для перехода в Web)

Контроллер: `WebHandoffController.java`.  
Позволяет нативному клиенту (Android, Desktop) выпустить безопасный одноразовый handoff-код с TTL 60 секунд. Клиент открывает внешний системный браузер по ссылке:
`https://vpn.struchev.site/?handoff_code=<CODE>&next=/billing`
Пользователь оказывается сразу авторизован в личном кабинете без повторного ввода пароля (соответствие требованиям Google Play).

* **Аутентификация**: <span class="api-badge api-auth-bearer">Bearer JWT</span> (текущий пользователь)
* **Метод / Путь**: `POST /api/v1/auth/web-handoff`

### Параметры запроса: Отсутствуют (пустое тело)

### Ответ (200 OK)

```json
{
  "code": "hnd_a1f94c3e80914be4b568770c634ddf01",
  "webUrl": "https://vpn.struchev.site",
  "expiresInSeconds": 60
}
```

### Пример cURL

```bash
curl -X POST "https://vpn.struchev.site/api/v1/auth/web-handoff" \
  -H "Authorization: Bearer <USER_JWT_TOKEN>"
```

---

## 8. POST /api/v1/auth/web-handoff/exchange (Обмен SSO-кода на JWT)

Вызывается фронтендом в браузере при обнаружении query-параметра `?handoff_code=...`. Код валидируется, атомарно аннулируется (single-use), и браузер получает постоянный JWT-токен пользователя.

* **Аутентификация**: <span class="api-badge api-auth-public">Public</span> (код сам выступает авторизационным фактором)
* **Метод / Путь**: `POST /api/v1/auth/web-handoff/exchange`
* **Content-Type**: `application/json`

### Параметры запроса (Request Body)

| Поле | Тип | Обязательное | Описание |
|---|---|:---:|---|
| `code` | `string` | Да | Одноразовый 36-значный handoff-код |

```json
{
  "code": "hnd_a1f94c3e80914be4b568770c634ddf01"
}
```

### Ответ (200 OK — AuthResponse)

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 12,
  "email": "engineer@example.com",
  "role": "USER",
  "referralCode": "REF99XYZ"
}
```

### Коды ошибок

| HTTP Код | Ошибка | Причина |
|---|---|---|
| `400 Bad Request` | `Invalid or expired code` | Код не существует, истёк срок жизни (60 сек) или код уже был использован |
| `400 Bad Request` | `code is required` | Параметр `code` отсутствует в теле запроса |
