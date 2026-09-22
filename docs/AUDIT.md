# Отчёт Технического Аудита: Баги, Уязвимости и Замечания

Дата проведения аудита: Сентябрь 2026.  
Объекты сканирования: `server/`, `agent/`, `android/`, `desktop/`, `web/`, `proto/`.

---

## 1. Сводный реестр выявленных дефектов

| № | Уровень | Проблема | Локализация | Статус |
|---|:---:|---|---|:---:|
| **1.1** | 🔴 Критический | Двойное зачисление депозита (Double-Crediting / Double-Spend) | `BillingService.java:133`, `claimTransaction:286` | ⚠️ Требует фикса |
| **1.2** | 🟡 Средний | Асимметрия срока действия триала (30 дней vs 3 дня) | `BillingService.java:269-276` | ⚠️ Требует фикса |
| **1.3** | 🟡 Средний | Отсутствие механизма автосписания продления подписки | `QuotaEnforcementTask.java:70` | ⚠️ Требует фикса |
| **2.1** | 🟠 Высокий | Выпуск активных JWT заблокированным пользователям через Handoff | `WebHandoffController.java:87` | ⚠️ Требует фикса |
| **2.2** | 🟡 Средний | Валидность ранее выпущенных JWT при блокировке аккаунта | `JwtAuthFilter.java:32-45` | Рекомендация |
| **2.3** | 🟡 Средний | Доступность экспорта VLESS-ссылок заблокированным пользователям | `SubscriptionExportService.java:275` | ⚠️ Требует фикса |
| **3.1** | 🟡 Средний | Необработанные `NullPointerException` (HTTP 500) без `@ControllerAdvice` | `UserController.java:246`, `AdminController.java:174` | ⚠️ Требует фикса |
| **4.1** | 🟢 UX | Отображение синтетического email `device_<uuid>@device.local` в профиле | `ProfilePage.tsx`, `ProfileFragment.java` | Замечание |
| **4.2** | 🟢 Архитектура| In-memory хранилище handoff-кодов в `ConcurrentHashMap` | `WebHandoffService.java:30` | Замечание |
| **4.3** | 🟢 UX | Несоответствие блокчейн-сетей между пользователем и админкой | `DashboardView.tsx`, `PaymentsSection.tsx` | Замечание |
| **4.4** | 🟢 Релиз | Дефолтный плейсхолдер Google Web Client ID в Android | `res/values/strings.xml:13` | Чеклист релиза |

---

## 2. Разбор критических проблем безопасности

### 2.1. Критическая уязвимость: Двойное зачисление крипто-депозита (Double-Spend)
* **Класс опасности**: Финансовая уязвимость высокой степени критичности.
* **Механика воспроизведения**:
  1. Пользователь создает инвойс на пополнение (`POST /api/v1/user/billing/invoice`), запись сохраняется со статусом `PENDING`.
  2. Переводит USDT в блокчейне.
  3. Не дожидаясь срабатывания сканера, нажимает «Я оплатил, вот хеш» (`POST /api/v1/user/billing/claim-tx`).
  4. Метод `claimTransaction` проверяет, что хеш ранее не использовался, и начисляет баланс. **При этом статус инвойса в таблице `crypto_invoices` НЕ меняется на `PAID` и остаётся `PENDING`!**
  5. Спустя 15 секунд срабатывает фоновый `BlockchainScannerTask`. Сканер находит в БД исходный инвойс со статусом `PENDING`, вызывает `creditInvoicePayment` и **повторно начисляет баланс** на ту же сумму.
* **Решение**:
  В `claimTransaction` атомарно переводить инвойс в статус `PAID` с фиксацией `tx_hash`. В `creditInvoicePayment` проверять `balanceEntryRepository.existsByReferenceId(txHash)`.

```mermaid
sequenceDiagram
    autonumber
    actor A as Злоумышленник
    participant API as /api/v1/user/billing
    participant S as BlockchainScannerTask
    participant DB as PostgreSQL

    A->>API: 1. POST /invoice ($10) -> PENDING
    A->>API: 2. POST /claim-tx (hash="0xabc")
    API->>DB: Баланс += $10, но статус инвойса остался PENDING!
    Note over S,DB: 3. Спустя 15 секунд срабатывает сканер
    S->>DB: Поиск инвойсов PENDING -> Находит инвойс #1
    S->>DB: creditInvoicePayment -> Баланс += $10 повторно!
    Note over A,DB: Итог: $20 на балансе вместо $10
```

---

### 2.2. Выпуск JWT заблокированным аккаунтам через Web Handoff
* В базовых контроллерах статус пользователя строго проверяется:
  ```java
  if (!"ACTIVE".equals(user.getStatus())) {
      throw new IllegalStateException("User account is " + user.getStatus());
  }
  ```
* Однако в `WebHandoffController.exchangeHandoff` проверка статуса пользователя отсутствовала. Заблокированный администраторской блокировкой пользователь при переходе по ссылке handoff получал полноценный рабочий JWT-токен на 30 дней.
* **Решение**: добавить валидацию статуса в метод обмена кода.

---

### 2.3. Падения контроллеров с HTTP 500 из-за отсутствия `@ControllerAdvice`
* В контроллерах `UserController.java` и `AdminController.java` аргументы извлекались из сырых `Map<String, Object>` вызовами `Long.valueOf(req.get("amountMicro").toString())`.
* При отсутствии ключа в JSON происходил неперехваченный `NullPointerException`. Из-за отсутствия глобального перехватчика `@ControllerAdvice` клиент получал пустой generic HTTP 500.
* **Решение**: внедрить класс `GlobalExceptionHandler` с обработкой `ResponseStatusException`, `IllegalArgumentException` и возвратом JSON `{"error": "..."}` с кодом 400.

---

## 3. Аудит клиентов (22.09.2026): найдено и исправлено

| № | Уровень | Проблема | Где | Статус |
|---|:---:|---|---|:---:|
| C1 | 🔴 | Android: служебные запросы сервиса (проба блокировки, API, сигналинг реле) шли в собственный TUN — «блокировка оператора» не детектировалась, P2P-реле не включалось | `CensorshipProbeService`, `XrayVpnService` | ✅ `ProtectedSocketFactory` |
| C2 | 🔴 | Android: проверка живости через TCP-connect проходила при мёртвом туннеле (рукопожатие завершает TUN-стек); в режиме «только RU» шла мимо туннеля | `XrayVpnService#isTunnelAlive` | ✅ HTTPS-проба, приложение в allow-list |
| C3 | 🔴 | Desktop: «Защищено» = «локальный порт открыт»; при недоступной ноде машина без интернета, фолбэки не срабатывали | `vpnController.ts` | ✅ проба через `probe-in` + health-check |
| C4 | 🔴 | Android: в OPERATOR_BLOCKED TUN оставался поднятым без ядра — интернет пропадал при кнопке «Подключить» | `XrayVpnService` | ✅ `failTerminal` |
| C5 | 🟠 | Android: после «Отключить» во время P2P-подключения туннель поднимался; `onDestroy` не останавливал xray | `XrayVpnService` | ✅ |
| C6 | 🟠 | JWT на 30 дней без продления; 401/403 неразличимы, клиенты не обрабатывали истечение | server, все клиенты | ✅ `/auth/refresh`, 401 entry point |
| C7 | 🟠 | Переподключение бесконечно перебирало устаревший список нод; истёкший тариф выглядел как реконнект/«блокировка» | оба клиента | ✅ перезагрузка после полного круга |
| C8 | 🟠 | Desktop: гонка в `XrayProcess` (осиротевший xray, ложные сбои, занятый порт) | `xrayProcess.ts` | ✅ |
| C9 | 🟠 | Android: повторный `runXray` без `stopXray` после неудачной проверки | `XrayVpnService` | ✅ |
| C10 | 🟡 | Плитка в шторке в RECONNECTING запускала второй цикл подключения; `connect()` не учитывал RECONNECTING | `VpnTileService`, оба контроллера | ✅ |
| C11 | 🟡 | Переход в OPERATOR_BLOCKED из CONNECTED (после health-check) отклонялся машиной состояний | оба контроллера | ✅ |
| C12 | 🟡 | Desktop: RU-режим сбрасывался при перезапуске; `save()` терял гео-IP, `saveDeviceId` — настройки P2P | `tokenStore.ts` | ✅ |
| C13 | 🟡 | «Ошибка» без причины; английские тексты ошибок входа; дата «2126» у бессрочной подписки; английские строки в пополнении | UI всех клиентов | ✅ |
| C14 | 🟢 | Android: опрос профиля в фоне каждые 60 с; пересоздание вкладок; пинг = лишний запрос `subscription/links` и мерился через туннель | Android UI, ApiClient | ✅ |
| C15 | 🟢 | Web: выход из аккаунта при любой ошибке загрузки профиля (включая 5xx/сеть) | `web/src/App.tsx` | ✅ |

**Открыто:**
* WebRTC-сокеты P2P на Android не защищены через `protect()`. В режиме P2P-exit после поднятия TUN трафик может зацикливаться. Нужна проверка на устройстве (см. `docs/TODO.md`).
* Desktop TUN-режим (kill switch, трафик приложений, игнорирующих прокси) не реализован.
