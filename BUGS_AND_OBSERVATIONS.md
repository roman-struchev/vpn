# Аудит кодовой базы: Баги, уязвимости и архитектурные замечания

Полный отчёт аудита с детальным разбором кода, сценариями воспроизведения и рекомендациями находится в файле:
👉 [`docs/BUGS_AND_OBSERVATIONS.md`](docs/BUGS_AND_OBSERVATIONS.md)

### Краткое резюме и статус исправлений:
1. **Double-Crediting в крипто-платежах (Высокая критичность) — ✅ ИСПРАВЛЕНО:**
   - Предотвращено двойное зачисление в `BillingService.creditInvoicePayment` через проверку `balanceEntryRepository.existsByReferenceId(txHash)`, а в `BillingService.claimTransaction` ожидающий инвойс переводится в `PAID` с фиксацией хеша.
2. **Асимметрия срока триала (Web vs Native/Telegram) — ✅ ИСПРАВЛЕНО:**
   - В `BillingService.purchaseOrRenewSubscription` для тарифа `trial` установлен строгий срок 3 дня и `autoRenew = false`.
3. **Отсутствие воркера автопродления подписок — ✅ ИСПРАВЛЕНО:**
   - В `QuotaEnforcementTask` реализовано автопродление подписок с баланса пользователя перед переводом в `EXPIRED`.
4. **Обход блокировки пользователя через Web Handoff — ✅ ИСПРАВЛЕНО:**
   - В `WebHandoffController.exchangeHandoff` и `AuthService.upgradeGuest` добавлена строгая проверка статуса `ACTIVE`.
5. **Валидность действующих JWT у заблокированных пользователей — ✅ ИСПРАВЛЕНО:**
   - `JwtAuthFilter` валидирует активность статуса пользователя (`userRepository.findById`) перед аутентификацией сессии.
6. **Доступность экспорта VLESS-ссылок заблокированным пользователям — ✅ ИСПРАВЛЕНО:**
   - В `SubscriptionController.exportSubscription` и `SubscriptionExportService` заблокированным пользователям закрыт доступ (403 Forbidden).
7. **Риски HTTP 500 (NPE и NumberFormatException) из-за отсутствия `@ControllerAdvice` — ✅ ИСПРАВЛЕНО:**
   - Добавлен `@RestControllerAdvice` (`GlobalExceptionHandler`), а в `UserController` и `AdminController` внедрен безопасный парсинг числовых параметров.
8. **Утечка синтетического email (`device_<uuid>@device.local`) в UI — ✅ ИСПРАВЛЕНО:**
   - Реализовано в рамках паритета гостевого профиля (Android & Desktop).
9. **In-Memory хранилище в `WebHandoffService`:**
   - Архитектурное замечание для этапа горизонтального масштабирования (Redis/PostgreSQL).
10. **Несоответствие блокчейн-сетей между Admin UI и User UI:**
    - Замечание к UI (пользовательские чейны TRON/ETH vs админские L2).
11. **Статический плейсхолдер Google Web Client ID в Android — ✅ ИСПРАВЛЕНО:**
    - В `LoginActivity.java` добавлена проверка плейсхолдера с информативным уведомлением пользователя вместо сбоя Credential Manager.
