# Документация проекта Aura VPN

Каталог `docs/` содержит постоянную системную, продуктовую, исследовательскую и юридическую документацию проекта.

---

## Навигатор по документации

| Документ | Назначение и содержание |
|---|---|
| [**`ARCHITECTURE.md`**](ARCHITECTURE.md) | **Главный документ системы**: архитектура монорепозитория, компонентов (`server`, `agent`, `web`, `admin`, `android`, `desktop`), протоколов (VLESS + XHTTP + Reality, gRPC fallback, CDN), модель безопасности, биллинг, продуктовые тарифы, юнит-экономика и матрица рисков. |
| [**`research/ru-blocking.md`**](research/ru-blocking.md) | **Исследование блокировок DPI и ТСПУ**: технический анализ работы классификаторов РКН, почему детектируется TCP+Vision, преимущества XHTTP с XMUX, маскировка Reality и поведение при шатдаунах («белые списки»). |
| [**`stores-and-liability.md`**](stores-and-liability.md) | **Юридический анализ и правила сторов**: риски работы физлица без юрлица, ответственность по ст. 14.3 КоАП РФ, правила Apple App Store (требование организации по правилу 5.4) и Google Play. |
| [**`google-play-readiness.md`**](google-play-readiness.md) | **Чеклист публикации в Google Play**: требования VpnService, черновик политики конфиденциальности, маппинг полей Data Safety и статус верификации аккаунта. |
| [**`P2P_RELAY.md`**](P2P_RELAY.md) | **Подключение через участников сети**: чем реле отличается от выходной ноды, как устроен путь xray → мост → WebRTC → реле → нода, когда он включается автоматически, какие есть ограничения (STUN без TURN) и где это в коде. |
| [**`DIAGNOSTICS.md`**](DIAGNOSTICS.md) | **Сбор ошибок с нод и клиентов**: что и как собирается, почему хранится агрегировано по отпечатку, какие стоят лимиты, как получить готовый к анализу отчёт (`/api/v1/admin/diagnostics/report`) и как добавить новую точку сбора в agent/desktop/android. |
| [**`AUDIT.md`**](AUDIT.md) | **Отчёт технического аудита**: баги и уязвимости сервера (включая double-credit в крипто-платежах) и аудит клиентов от 22.09.2026 (надёжность туннеля, сессии, UX) со статусом исправлений. |
| [**`research/UX_REVIEW.md`**](research/UX_REVIEW.md) | **UX-аудит** веб-дашборда, админки, desktop и Android с конкретными находками ("Quick Win #N") — на них ссылаются комментарии в коде. |
| [**`research/WEB_HANDOFF_RESEARCH.md`**](research/WEB_HANDOFF_RESEARCH.md) | **Исследование и дизайн** механизма client→web SSO handoff (реализовано, на разделы документа ссылаются комментарии в `web/src/App.tsx` и др.). |

---

## С чего начать?

* **Для разработчиков ядра и бэкенда**: начните с [`ARCHITECTURE.md`](ARCHITECTURE.md) (разделы 2, 3, 5, 6, 7).
* **Для сетевых инженеров и работы с нодами**: изучите [`research/ru-blocking.md`](research/ru-blocking.md) и [`ARCHITECTURE.md`](ARCHITECTURE.md) (разделы 4 и 5).
* **Для мобильных и десктопных разработчиков**: см. [`ARCHITECTURE.md`](ARCHITECTURE.md) (раздел 8), [`android/README.md`](../android/README.md) и [`desktop/README.md`](../desktop/README.md).
* **Для подготовки релиза в сторы**: см. [`google-play-readiness.md`](google-play-readiness.md) и [`stores-and-liability.md`](stores-and-liability.md).
* **Для планирования багфиксов**: см. [`AUDIT.md`](AUDIT.md) и [`TODO.md`](TODO.md).
