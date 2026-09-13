# Аудит кодовой базы: Баги, уязвимости и архитектурные замечания

Дата составления: Сентябрь 2026  
Проект: Aura VPN (Server, Web, Agent, Android, Desktop, E2E)

---

## Резюме аудита

В ходе детального сквозного сканирования серверной логики (`server/`), клиентских приложений (`android/`, `desktop/`, `web/`), агентов нод (`agent/`) и тестового набора (`e2e/`) были выявлены следующие проблемы:
- **1 критическая уязвимость** (потенциальный double-spend при начислении крипто-платежей);
- **3 проблемы безопасности и контроля доступа** (обход статуса блокировки аккаунта);
- **3 дефекта стабильности API** (риски unhandled 500 из-за отсутствия глобального перехватчика ошибок);
- **2 продуктовых несоответствия логики биллинга** (разнобой длительности триала и отсутствие воркера автопродления);
- **2 замечания к UX и распределённой архитектуре** (утечка синтетического email и in-memory состояние SSO).

---

## 1. Критические уязвимости и риски биллинга

### 1.1. Двойное зачисление депозита (Double-Crediting / Double-Spend) — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (защита в `BillingService.creditInvoicePayment` и синхронизация инвойсов в `BillingService.claimTransaction`)
- **Уровень критичности:** 🔴 Высокий
- **Локализация:**
  - `server/src/main/java/com/vpn/server/service/BillingService.java:133-165` (`creditInvoicePayment`)
  - `server/src/main/java/com/vpn/server/service/BillingService.java:286-316` (`claimTransaction`)
  - `server/src/main/java/com/vpn/server/task/BlockchainScannerTask.java:144, 219`
- **Описание проблемы:**
  В системе существуют два параллельных механизма зачисления крипто-платежей:
  1. Фоновый сканер блокчейна (`BlockchainScannerTask`), который находит транзакции и вызывает `BillingService.creditInvoicePayment`.
  2. Ручной клейм пользователем кнопки «Я оплатил, вот хеш» (`UserController.claimTransaction` -> `BillingService.claimTransaction`).

  **Сценарий воспроизведения атаки:**
  1. Пользователь создает инвойс на пополнение баланса (`POST /api/v1/user/billing/invoice`). В базе создается запись `CryptoInvoice` со статусом `PENDING`.
  2. Пользователь отправляет указанную точную сумму USDT в сети Tron или Ethereum.
  3. Не дожидаясь отработки сканера блокчейна (интервал 30 сек), пользователь отправляет форму «Я оплатил, вот хеш» с хешем своей транзакции (`POST /api/v1/user/billing/claim-tx`).
  4. Метод `claimTransaction` проверяет:
     - `cryptoInvoiceRepository.existsByTxHash(cleanTxHash)` -> `false` (сканер еще не записал этот хеш);
     - `balanceEntryRepository.existsByReferenceId(cleanTxHash)` -> `false`.
  5. `claimTransaction` начисляет средства на баланс пользователя и создает запись `BalanceEntry` с `referenceId = cleanTxHash`. **При этом инвойс в таблице `crypto_invoices` НЕ помечается как `PAID` и не связывается с хешем — он так и остается в статусе `PENDING`!**
  6. Спустя 10-30 секунд срабатывает фоновый `BlockchainScannerTask`. Сканер считывает входящий перевод из блокчейна и вызывает `cryptoInvoiceRepository.findPendingMatchingInvoice(...)`.
  7. Сканер успешно находит исходный `PENDING` инвойс и вызывает `BillingService.creditInvoicePayment(invoice.getId(), txHash, actualAmount)`.
  8. Метод `creditInvoicePayment` проверяет только статус инвойса:
     ```java
     if (!"PENDING".equals(invoice.getStatus())) {
         return;
     }
     ```
     Так как статус всё ещё `PENDING`, проверка проходит! Метод меняет статус инвойса на `PAID`, записывает `txHash` и **повторно вызывает `adjustBalance(user, invoice.getBaseAmountUsdtMicro(), "DEPOSIT", txHash)`**.
  9. **Результат:** Баланс пользователя удваивается. Пользователь получает двойную сумму на свой лицевой счёт.
- **Рекомендация по исправлению:**
  1. В `BillingService.creditInvoicePayment`: перед начислением баланса добавить проверку `if (balanceEntryRepository.existsByReferenceId(txHash)) { invoice.setStatus("PAID"); invoice.setTxHash(txHash); return; }`.
  2. В `BillingService.claimTransaction`: находить соответствующий ожидающий инвойс пользователя с подходящей суммой и переводить его статус в `PAID` с фиксацией `txHash`.

---

### 1.2. Асимметрия срока действия и автопродления триала (30 дней vs 3 дня) — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (в `BillingService.purchaseOrRenewSubscription` тариф `trial` ограничен 3 днями с `autoRenew = false`)
- **Уровень критичности:** 🟡 Средний
- **Локализация:**
  - `server/src/main/java/com/vpn/server/service/BillingService.java:269-276`
  - `server/src/main/java/com/vpn/server/service/DeviceAuthService.java:138`
  - `server/src/main/java/com/vpn/server/service/TelegramAuthService.java:202`
  - `server/src/main/java/com/vpn/server/service/GoogleAuthService.java:189`
- **Описание проблемы:**
  - Согласно продуктовым требованиям (`docs/ARCHITECTURE.md` §9.1, `TariffService.java`), пробный тариф `trial` является бесплатным и предоставляется на **3 дня** с `autoRenew = false`.
  - При регистрации через Telegram-бота, Device UUID или Google OAuth пользователю корректно выдается 3 дня:
    `sub.setCurrentPeriodEnd(Instant.now().plus(3, ChronoUnit.DAYS)); sub.setAutoRenew(false);`.
  - Однако, если пользователь зарегистрировался через email/пароль на сайте и нажал «Активировать бесплатно» (`tariffId = "trial"`), срабатывает метод `BillingService.purchaseOrRenewSubscription`:
    ```java
    Instant periodEnd = isAnnual ? periodStart.plus(365, ChronoUnit.DAYS) : periodStart.plus(30, ChronoUnit.DAYS);
    sub.setCurrentPeriodEnd(periodEnd);
    sub.setAutoRenew(true);
    ```
  - В результате веб-пользователь получает бесплатный доступ не на 3, а на **30 дней**, причём с выставленным флагом `autoRenew = true`.
- **Рекомендация по исправлению:**
  В `BillingService.purchaseOrRenewSubscription` добавить специальную обработку для `tariffId.equals("trial")`: выставлять срок окончания `periodStart.plus(3, ChronoUnit.DAYS)` и `autoRenew = false`.

---

### 1.3. Отсутствие механизма автоматического списания/продления подписок — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (в `QuotaEnforcementTask` добавлен вызов `billingService.purchaseOrRenewSubscription` при `autoRenew == true`)
- **Уровень критичности:** 🟡 Средний
- **Локализация:**
  - `server/src/main/java/com/vpn/server/task/QuotaEnforcementTask.java:70-76`
  - `server/src/main/java/com/vpn/server/service/BillingService.java`
- **Описание проблемы:**
  - В модели подписки `Subscription` хранится флаг `autoRenew = true`. В документации заявлено: *"Подписка списывается с баланса, продление автоматическое, пока баланса хватает. Пополнил на $12 — год работает само"*.
  - На практике в `QuotaEnforcementTask`:
    ```java
    if (sub.getCurrentPeriodEnd().isBefore(now)) {
        sub.setStatus("EXPIRED");
        subscriptionRepository.save(sub);
        expiredUserIds.add(sub.getUser().getId());
    }
    ```
  - Задача просто переводит подписку в статус `EXPIRED` и отключает ключи доступа. Нет никакого фонового планировщика или вызова `BillingService`, который проверял бы наличие достаточного баланса на счёте пользователя перед переводом в `EXPIRED` и совершал бы автоматическое списание и продление периода.
- **Рекомендация по исправлению:**
  Реализовать в `QuotaEnforcementTask` или отдельном `SubscriptionRenewalTask` проверку: если у пользователя `autoRenew == true` и текущий баланс `>= стоимость продления тарифа`, вызывать `billingService.purchaseOrRenewSubscription` вместо экспирации.

---

## 2. Безопасность и контроль доступа

### 2.1. Выпуск активных JWT-сессий заблокированным пользователям через Web Handoff — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (добавлена проверка `!"ACTIVE".equals(user.getStatus())` со статусом 403 Forbidden в `WebHandoffController.exchangeHandoff` и `AuthService.upgradeGuest`)
- **Уровень критичности:** 🟠 Средний / Высокий
- **Локализация:**
  - `server/src/main/java/com/vpn/server/controller/WebHandoffController.java:87-93`
- **Описание проблемы:**
  Во всех базовых сервисах аутентификации (`AuthService`, `DeviceAuthService`, `GoogleAuthService`, `TelegramAuthService`) строго проверяется статус аккаунта:
  ```java
  if (!"ACTIVE".equals(user.getStatus())) {
      throw new IllegalStateException("User account is " + user.getStatus());
  }
  ```
  Однако в `WebHandoffController.exchangeHandoff`:
  ```java
  var user = handoffService.exchange(handoffCode);
  String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
  ```
  Статус `user.getStatus()` не проверяется. Заблокированный администратором пользователь (`status = "BLOCKED"`), перейдя по сохраненной или сгенерированной ссылке handoff, получает полноценный свежий JWT-токен на 30 дней.
- **Рекомендация по исправлению:**
  Добавить проверку `if (!"ACTIVE".equals(user.getStatus())) { return ResponseEntity.status(403).body(Map.of("error", "Account is " + user.getStatus())); }` в `WebHandoffController.exchangeHandoff`.

---

### 2.2. Недействительность статуса блокировки для действующих JWT (`JwtAuthFilter`) — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (`JwtAuthFilter` проверяет существование и активность пользователя через `userRepository.findById`)
- **Уровень критичности:** 🟡 Средний
- **Локализация:**
  - `server/src/main/java/com/vpn/server/config/JwtAuthFilter.java:32-45`
- **Описание проблемы:**
  `JwtAuthFilter` валидирует токен чисто криптографически через подпись HMAC (`jwtUtil.validateToken(token)`). Когда администратор блокирует пользователя (`POST /api/v1/admin/users/{id}/status` -> `"BLOCKED"`), сервер отзывает ключи с нод через gRPC, но JWT-токен пользователя остаётся валидным до окончания 30-дневного срока. Заблокированный пользователь может продолжать совершать запросы к `/api/v1/user/**`.
- **Рекомендация по исправлению:**
  Либо проверять статус пользователя в БД/кэше в `JwtAuthFilter` (или `UserDetailsService`), либо сохранять версионность токена/дату отзыва сессии (`tokenNotBefore` / `revokedAt`) в сущности `User`.

---

### 2.3. Доступность экспорта VLESS-ссылок заблокированным пользователям — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (добавлена проверка `!"ACTIVE".equals(sub.getUser().getStatus())` в `SubscriptionExportService` и `SubscriptionController`)
- **Уровень критичности:** 🟡 Средний
- **Локализация:**
  - `server/src/main/java/com/vpn/server/service/SubscriptionExportService.java:275-285`
- **Описание проблемы:**
  Эндпоинт экспорта `/api/v1/subscription/export/{exportToken}` проверяет дату окончания подписки:
  ```java
  if (sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.GONE, "Subscription expired");
  }
  ```
  Но статус пользователя `sub.getUser().getStatus()` не проверяется. Если подписка по сроку ещё не истекла, но пользователь заблокирован администратором за нарушения, его внешний VLESS-экспорт продолжает отдавать актуальные ссылки на ноды.
- **Рекомендация по исправлению:**
  Добавить проверку `if (!"ACTIVE".equals(sub.getUser().getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account suspended");`.

---

## 3. Стабильность API и обработка ошибок

### 3.1. Необработанные NullPointerException и NumberFormatException (HTTP 500) — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (добавлен `@RestControllerAdvice` `GlobalExceptionHandler`, а в контроллерах внедрена безопасная валидация параметров перед парсингом)
- **Уровень критичности:** 🟡 Средний
- **Локализация:**
  - `server/src/main/java/com/vpn/server/controller/UserController.java:246`
  - `server/src/main/java/com/vpn/server/controller/AdminController.java:174, 225, 369`
- **Описание проблемы:**
  В ряде методов контроллеров данные извлекаются из `Map<String, Object>` без проверки на `null` до входа в блок валидации:
  1. `UserController.claimTransaction`:
     ```java
     Long amountMicro = Long.valueOf(req.get("amountMicro").toString()); // NPE если amountMicro отсутствует!
     ```
  2. `AdminController.adjustUserBalance`:
     ```java
     long amountMicro = Long.parseLong(req.get("amountMicro").toString()); // NPE если ключ отсутствует
     ```
  3. `AdminController.extendSubscription`:
     ```java
     int days = Integer.parseInt(req.getOrDefault("days", 30).toString()); // NumberFormatException если строка не число
     ```
  4. `AdminController.reconcileDeposit`:
     ```java
     Long amountMicro = Long.valueOf(payload.get("amountMicro").toString()); // NPE
     ```
  Поскольку в проекте **отсутствовал `@ControllerAdvice`**, любые такие исключения приводили к возврату generic HTTP 500 Internal Server Error и засорению логов стектрейсами.
- **Рекомендация по исправлению:**
  1. Использовать строгие DTO-классы с аннотациями `@NotNull`, `@Min` вместо сырых `Map<String, Object>`.
  2. Добавить глобальный обработчик `@ControllerAdvice` (`GlobalExceptionHandler`), перехватывающий `IllegalArgumentException`, `IllegalStateException`, `NullPointerException` и валидационные ошибки Spring, возвращая понятный HTTP 400 Bad Request: `{"error": "..."}`.

---

## 4. Замечания к архитектуре и пользовательскому опыту (UX)

### 4.1. Утечка синтетического email устройства в интерфейс клиентов — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (реализован паритет гостевого профиля в Android и Desktop с бейджами гостя и скрытием синтетического email)
- **Уровень критичности:** 🟢 Низкий (UX)
- **Локализация:**
  - `server/src/main/java/com/vpn/server/service/DeviceAuthService.java:114`
  - `desktop/src/renderer/src/pages/ProfilePage.tsx:47`
  - `android/app/src/main/java/com/vpn/android/ui/profile/ProfileFragment.java:60`
- **Описание проблемы:**
  При анонимной активации устройства бэкенд регистрирует виртуальный аккаунт с email вида `device_<uuid>@device.local`. В интерфейсах приложений Android и Desktop это значение отображается пользователю как основной email его профиля. Для конечного пользователя строка вида `device_a1b2c3d4...local` выглядит пугающе и воспринимается как баг или технический мусор.
- **Рекомендация по исправлению:**
  Если email оканчивается на `@device.local`, отображать в профиле бейдж «Анонимный пробный аккаунт» с кнопкой «Привязать почту / войти через Google».

---

### 4.2. In-Memory состояние в `WebHandoffService`
- **Уровень критичности:** 🟢 Архитектурное замечание
- **Локализация:**
  - `server/src/main/java/com/vpn/server/service/WebHandoffService.java:30`
- **Описание проблемы:**
  Одноразовые handoff-коды хранятся в `ConcurrentHashMap<String, HandoffEntry>`. В текущей конфигурации монолита это работает корректно. Однако при масштабировании бэкенда на 2 и более инстанса без sticky sessions запрос на создание кода с клиента попадет на узел А, а вызов обмена из браузера — на узел Б, что приведет к ошибке авторизации.
- **Рекомендация по исправлению:**
  При горизонтальном масштабировании хранить handoff-коды в общей базе данных PostgreSQL с `expires_at` или в Redis / кэше с TTL.

---

### 4.3. Несоответствие поддерживаемых блокчейн-сетей между пользователем и админкой
- **Уровень критичности:** 🟢 UX / Фичи
- **Локализация:**
  - `web/src/components/DashboardView.tsx:322-331`
  - `web/src/admin/sections/PaymentsSection.tsx:120-128`
- **Описание проблемы:**
  В интерфейсе администратора для сверки платежей доступны чейны `TRON`, `ETHEREUM`, `BASE`, `ARBITRUM`, `POLYGON`. При этом в форме пополнения для пользователя захардкожены только `TRON` и `ETHEREUM`. Сети второго уровня (Base/Arbitrum), где комиссии составляют единицы центов, пользователям на фронтенде не предлагаются.
- **Рекомендация по исправлению:**
  Вынести список доступных сетей в конфигурацию или добавить переключатели сетей Base и Arbitrum в пользовательский интерфейс пополнения.

---

### 4.4. Статический плейсхолдер Google Web Client ID в Android — ✅ ИСПРАВЛЕНО
- **Статус:** ✅ Исправлено (добавлена проверка `google_web_client_id` с информативным тостом вместо падения `GetCredentialException`)
- **Уровень критичности:** 🟢 Эксплуатация
- **Локализация:**
  - `android/app/src/main/res/values/strings.xml:13`
- **Описание проблемы:**
  По умолчанию параметр `google_web_client_id` имеет значение `REPLACE_WITH_GOOGLE_WEB_CLIENT_ID`. Если пользователь в сборке нажмет кнопку «Войти через Google», приложение падает в ошибку Credential Manager `GetCredentialException`.
- **Рекомендация по исправлению:**
  В `LoginActivity.java` проверять, равен ли `google_web_client_id` дефолтному плейсхолдеру, и либо скрывать кнопку Google Sign-In, либо выводить информативный тост пользователю / разработчику.
