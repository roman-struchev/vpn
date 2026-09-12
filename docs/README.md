# Документация проекта NextGen VPN

Каталог `docs/` содержит постоянную системную, продуктовую, исследовательскую и юридическую документацию проекта.

---

## Навигатор по документации

| Документ | Назначение и содержание |
|---|---|
| [**`ARCHITECTURE.md`**](ARCHITECTURE.md) | **Главный документ системы**: архитектура монорепозитория, компонентов (`server`, `agent`, `web`, `admin`, `android`, `desktop`), протоколов (VLESS + XHTTP + Reality, gRPC fallback, CDN), модель безопасности, биллинг, продуктовые тарифы, юнит-экономика и матрица рисков. |
| [**`research/ru-blocking.md`**](research/ru-blocking.md) | **Исследование блокировок DPI и ТСПУ**: технический анализ работы классификаторов РКН, почему детектируется TCP+Vision, преимущества XHTTP с XMUX, маскировка Reality и поведение при шатдаунах («белые списки»). |
| [**`stores-and-liability.md`**](stores-and-liability.md) | **Юридический анализ и правила сторов**: риски работы физлица без юрлица, ответственность по ст. 14.3 КоАП РФ, правила Apple App Store (требование организации по правилу 5.4) и Google Play. |
| [**`google-play-readiness.md`**](google-play-readiness.md) | **Чеклист публикации в Google Play**: требования VpnService, черновик политики конфиденциальности, маппинг полей Data Safety и статус верификации аккаунта. |
| [**`BUGS_AND_OBSERVATIONS.md`**](BUGS_AND_OBSERVATIONS.md) | **Отчёт технического аудита**: подтвержденные баги, уязвимости (включая критический double-credit в крипто-платежах), проблемы безопасности и рекомендации по улучшению кодовой базы. |
| [**`research/UX_REVIEW.md`**](research/UX_REVIEW.md) | **UX-аудит** веб-дашборда, админки, desktop и Android с конкретными находками ("Quick Win #N") — на них ссылаются комментарии в коде. |
| [**`research/WEB_HANDOFF_RESEARCH.md`**](research/WEB_HANDOFF_RESEARCH.md) | **Исследование и дизайн** механизма client→web SSO handoff (реализовано, на разделы документа ссылаются комментарии в `web/src/App.tsx` и др.). |
| [**`TODO_ANDROID_GUEST_PROFILE.md`**](TODO_ANDROID_GUEST_PROFILE.md) | **TODO**: портировать на Android UX-исправление гостевого/пробного профиля, уже сделанное для desktop (`isGuest`, merge при входе, единый экран без вкладок для гостя) — конкретный список пробелов по файлам. |

---

## С чего начать?

* **Для разработчиков ядра и бэкенда**: начните с [`ARCHITECTURE.md`](ARCHITECTURE.md) (разделы 2, 3, 5, 6, 7).
* **Для сетевых инженеров и работы с нодами**: изучите [`research/ru-blocking.md`](research/ru-blocking.md) и [`ARCHITECTURE.md`](ARCHITECTURE.md) (разделы 4 и 5).
* **Для мобильных и десктопных разработчиков**: см. [`ARCHITECTURE.md`](ARCHITECTURE.md) (раздел 8), [`android/README.md`](../android/README.md) и [`desktop/README.md`](../desktop/README.md).
* **Для подготовки релиза в сторы**: см. [`google-play-readiness.md`](google-play-readiness.md) и [`stores-and-liability.md`](stores-and-liability.md).
* **Для планирования багфиксов**: см. [`BUGS_AND_OBSERVATIONS.md`](BUGS_AND_OBSERVATIONS.md).
