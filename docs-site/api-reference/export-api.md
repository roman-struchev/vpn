# Export API: Универсальный экспорт VLESS-подписок

Контроллер: `SubscriptionExportController.java` (`SubscriptionExportService.java`).  
Базовый путь: `/api/v1/subscription/export/{exportToken}`.

---

## 1. Назначение и формат

Эндпоинт предназначен для интеграции с популярными открытыми мультиплатформенными VPN-клиентами: **v2rayNG** (Android), **Hiddify** (Windows, macOS, Android, iOS), **FoXray**, **Streisand**, **Karing** и **Shadowrocket**.

Пользователь копирует персональный URL подписки из веб-кабинета или Telegram-бота и вставляет его в поле «Добавить подписку» в стороннем клиенте.

* **Аутентификация**: <span class="api-badge api-auth-public">Token in URL Path</span> (криптостойкий 64-символьный hex-токен)
* **Метод / Путь**: `GET /api/v1/subscription/export/{exportToken}`
* **Формат ответа**: `text/plain; charset=utf-8` (Base64-encoded список VLESS-ссылок, разделенных переводом строки `\n`)

---

## 2. HTTP-заголовки ответа (Стандарт SIP008 / Clash / Sing-box)

Сервер отдаёт стандартизированные метаданные подписки в HTTP-заголовках, позволяя клиентам наглядно отображать остаток трафика и дату окончания прямо в списке серверов:

```http
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Subscription-Userinfo: upload=1048576000; download=13237325000; total=107374182400; expire=1791742000
profile-update-interval: 6
Content-Disposition: attachment; filename="NextGen-VPN.txt"
```

### Разбор заголовка `Subscription-Userinfo`:
* `upload`: объём исходящего трафика пользователя в байтах;
* `download`: объём входящего трафика пользователя в байтах;
* `total`: общий лимит тарифа в байтах (`trafficLimitBytes`);
* `expire`: время истечения подписки в секундах Unix Epoch (`currentPeriodEnd`).
* `profile-update-interval`: рекомендованный интервал автообновления профиля клиентом в часах (6 часов).

---

## 3. Декодированное содержимое (Payload)

Тело ответа представляет собой Base64-строку, которая после декодирования содержит актуальные конфигурационные URI для всех активных нод:

```text
vless://9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d@194.87.12.34:443?type=xhttp&path=%2Fapi%2Fv1%2Fstream&host=dl.google.com&security=reality&pbk=Z1X5q_a4...&fp=firefox&sni=dl.google.com&sid=a1b2c3d4#NextGen-AMS-01-XHTTP
vless://9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d@194.87.12.34:8443?type=grpc&serviceName=vless-grpc&security=reality&pbk=Z1X5q_a4...&fp=firefox&sni=dl.google.com&sid=a1b2c3d4#NextGen-AMS-01-gRPC-Fallback
vless://9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d@185.12.89.50:443?type=xhttp&path=%2Fapi%2Fv1%2Fstream&host=dl.google.com&security=reality&pbk=Y8K2...&fp=edge&sni=dl.google.com&sid=e5f60718#NextGen-FRA-01-XHTTP
```

---

## 4. Защита Anti-Enumeration (Ограничение на перебор IP)

Для предотвращения массового слива конфигураций или сканирования активных нод ТСПУ/DPI-ботами действует строгое ограничение:
1. Запросы к экспорту отслеживаются в скользящем окне 60 минут.
2. Если к одному `exportToken` поступили запросы более чем с **5 различных публичных IP-адресов** в течение часа:
   * Эндпоинт возвращает код `429 Too Many Requests`.
   * Сервер генерирует событие безопасности `SECURITY_SUSPICIOUS_ENUMERATION`.
   * Инициируется фоновая ротация клиентских UUID на всех нодах.

---

## 5. Коды ответов

| HTTP Код | Описание | Причина |
|---|---|---|
| `200 OK` | Успешно | Возвращает Base64-список нод |
| `404 Not Found` | `Invalid subscription token` | Токен не найден в БД |
| `410 Gone` | `Subscription expired` | Срок действия подписки пользователя истек |
| `429 Too Many Requests` | `Rate limit exceeded` | Слишком много запросов с разных IP-адресов |

### Пример cURL

```bash
curl -s "https://vpn.struchev.site/api/v1/subscription/export/a98f12c34d5e67f89012345678abcdef0123456789abcdef0123456789abcdef" \
  | base64 -d
```
