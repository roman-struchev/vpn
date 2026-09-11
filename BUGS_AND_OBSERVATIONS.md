# Аудит кодовой базы: Баги, уязвимости и архитектурные замечания

Полный отчёт аудита с детальным разбором кода, сценариями воспроизведения и рекомендациями находится в файле:
👉 [`docs/BUGS_AND_OBSERVATIONS.md`](docs/BUGS_AND_OBSERVATIONS.md)

### Краткое резюме:
1. **Double-Crediting в крипто-платежах (Высокая критичность):**
   - Рассинхронизация между `BillingService.claimTransaction` и `BlockchainScannerTask` позволяет дважды зачислить один и тот же крипто-платеж на баланс пользователя при быстром ручном подтверждении.
2. **Асимметрия срока триала (Web vs Native/Telegram):**
   - На вебе пробный тариф активируется на 30 дней с `autoRenew=true`, в то время как Telegram, Device Auth и Google Sign-In выдают строго 3 дня с `autoRenew=false`.
3. **Отсутствие воркера автопродления подписок:**
   - Несмотря на флаг `autoRenew=true`, планировщик `QuotaEnforcementTask` только помечает подписки как `EXPIRED`, не пытаясь продлить их с баланса пользователя.
4. **Обход блокировки пользователя через Web Handoff:**
   - `WebHandoffController.exchangeHandoff` генерирует JWT без проверки статуса `user.getStatus() == "ACTIVE"`.
5. **Валидность действующих JWT у заблокированных пользователей:**
   - `JwtAuthFilter` проверяет только HMAC-подпись токена, позволяя заблокированным пользователям обращаться к API до истечения срока JWT.
6. **Доступность экспорта VLESS-ссылок заблокированным пользователям:**
   - `SubscriptionExportService.exportVlessLinks` не проверяет статус активности пользователя.
7. **Риски HTTP 500 (NPE и NumberFormatException) из-за отсутствия `@ControllerAdvice`:**
   - `UserController.claimTransaction` и `AdminController` вызывают методы на невалидированных полях `Map<String, Object>` без перехвата исключений.
8. **Утечка синтетического email (`device_<uuid>@device.local`) в UI:**
   - В клиентах Android и Desktop анонимный системный email показывается как основной логин пользователя.
9. **In-Memory хранилище в `WebHandoffService`:**
   - Хранение handoff-кодов в `ConcurrentHashMap` вызовет сбои при горизонтальном масштабировании без липких сессий.
10. **Несоответствие блокчейн-сетей между Admin UI и User UI:**
    - В веб-интерфейсе пользователя доступны только Tron и Ethereum, тогда как админка поддерживает также сети L2 (Base, Arbitrum, Polygon).
