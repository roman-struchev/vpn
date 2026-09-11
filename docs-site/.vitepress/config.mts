import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(
  defineConfig({
    title: 'NextGen VPN Portal',
    description: 'Инженерная и продуктовая документация, спецификация REST и gRPC API NextGen VPN',
    lang: 'ru-RU',
    base: '/',
    cleanUrls: true,
    lastUpdated: true,
    markdown: {
      lineNumbers: true,
      theme: {
        light: 'github-light',
        dark: 'one-dark-pro',
      },
    },
    themeConfig: {
      siteTitle: 'NextGen VPN Docs',
      logo: '/logo.svg',
      nav: [
        { text: 'Главная', link: '/' },
        {
          text: '⚡ Спецификация API',
          items: [
            { text: 'Обзор API и соглашения', link: '/api-reference/overview' },
            { text: 'Аутентификация (/auth)', link: '/api-reference/auth-api' },
            { text: 'Пользователи и профиль (/user)', link: '/api-reference/user-api' },
            { text: 'Биллинг и Web Handoff (/billing)', link: '/api-reference/billing-api' },
            { text: 'Панель администратора (/admin)', link: '/api-reference/admin-api' },
            { text: 'Экспорт VLESS подписок (/export)', link: '/api-reference/export-api' },
            { text: 'Telegram Bot Webhook', link: '/api-reference/telegram-api' },
            { text: 'gRPC Node Agent (Protobuf)', link: '/api-reference/grpc-agent-proto' },
            { text: 'OpenAPI 3.1 & Схема', link: '/api-reference/openapi' },
          ],
        },
        {
          text: '🏛 Архитектура',
          items: [
            { text: 'Обзор архитектуры', link: '/architecture/overview' },
            { text: 'Сетевой транспорт и DPI', link: '/architecture/anti-censorship' },
            { text: 'Оркестрация нод (mTLS)', link: '/architecture/node-orchestration' },
            { text: 'Безопасность и модель данных', link: '/architecture/security-and-data' },
            { text: 'Биллинг и микро-допуск', link: '/architecture/billing-and-payments' },
            { text: 'Юнит-экономика и риски', link: '/architecture/unit-economics' },
          ],
        },
        {
          text: '📱 Клиенты',
          items: [
            { text: 'Клиентская экосистема', link: '/clients/overview' },
            { text: 'Android (Material 3 + libXray)', link: '/clients/android' },
            { text: 'Desktop (Electron + System Proxy)', link: '/clients/desktop' },
            { text: 'Web SPA & Admin Console', link: '/clients/web-and-admin' },
          ],
        },
        {
          text: '⚖️ Исследования',
          items: [
            { text: 'ТСПУ / DPI исследование (РФ 2026)', link: '/research-and-compliance/ru-blocking' },
            { text: 'Юридическая база и правила сторов', link: '/research-and-compliance/stores-and-liability' },
            { text: 'Google Play & Data Safety', link: '/research-and-compliance/google-play-readiness' },
          ],
        },
        { text: '🛡 Тех. аудит', link: '/audit/bugs-and-observations' },
      ],
      sidebar: [
        {
          text: '⚡ Спецификация API (REST & gRPC)',
          collapsed: false,
          items: [
            { text: 'Общие соглашения и Auth', link: '/api-reference/overview' },
            { text: 'POST /api/v1/auth (Auth & Upgrade)', link: '/api-reference/auth-api' },
            { text: 'GET/POST /api/v1/user (Profile & Devices)', link: '/api-reference/user-api' },
            { text: 'POST /billing & /handoff (Payments & SSO)', link: '/api-reference/billing-api' },
            { text: 'GET/POST /api/v1/admin (Control Plane)', link: '/api-reference/admin-api' },
            { text: 'GET /api/v1/subscription/export (VLESS)', link: '/api-reference/export-api' },
            { text: 'POST /api/v1/telegram/webhook', link: '/api-reference/telegram-api' },
            { text: 'gRPC vpn.agent.v1 (agent.proto)', link: '/api-reference/grpc-agent-proto' },
            { text: 'OpenAPI 3.1 Спецификация', link: '/api-reference/openapi' },
          ],
        },
        {
          text: '🏛 Архитектура и дизайн',
          collapsed: true,
          items: [
            { text: 'Обзор и концепция системы', link: '/architecture/overview' },
            { text: 'Сетевой транспорт и DPI', link: '/architecture/anti-censorship' },
            { text: 'Управление нодами и оркестрация', link: '/architecture/node-orchestration' },
            { text: 'Безопасность и модель данных', link: '/architecture/security-and-data' },
            { text: 'Биллинг и блокчейн-платежи', link: '/architecture/billing-and-payments' },
            { text: 'Юнит-экономика и матрица рисков', link: '/architecture/unit-economics' },
          ],
        },
        {
          text: '📱 Клиентские приложения',
          collapsed: true,
          items: [
            { text: 'Обзор клиентской экосистемы', link: '/clients/overview' },
            { text: 'Android Client (libXray)', link: '/clients/android' },
            { text: 'Desktop Client (Electron)', link: '/clients/desktop' },
            { text: 'Web SPA & Админ-панель', link: '/clients/web-and-admin' },
          ],
        },
        {
          text: '⚖️ Исследования и комплаенс',
          collapsed: true,
          items: [
            { text: 'Анализ ТСПУ и DPI (РФ 2026)', link: '/research-and-compliance/ru-blocking' },
            { text: 'Правила сторов и ст. 14.3 КоАП', link: '/research-and-compliance/stores-and-liability' },
            { text: 'Google Play & Data Safety чеклист', link: '/research-and-compliance/google-play-readiness' },
          ],
        },
        {
          text: '🛡 Аудит и качество кода',
          collapsed: true,
          items: [
            { text: 'Отчёт аудита кодовой базы', link: '/audit/bugs-and-observations' },
          ],
        },
      ],
      search: {
        provider: 'local',
        options: {
          locales: {
            root: {
              translations: {
                button: {
                  buttonText: 'Быстрый поиск по API и документам',
                  buttonAriaLabel: 'Поиск',
                },
                modal: {
                  noResultsText: 'Ничего не найдено по запросу',
                  resetButtonTitle: 'Сбросить поиск',
                  footer: {
                    selectText: 'выбрать',
                    navigateText: 'навигация',
                    closeText: 'закрыть',
                  },
                },
              },
            },
          },
        },
      },
      outline: {
        level: [2, 3],
        label: 'Содержание страницы',
      },
      docFooter: {
        prev: 'Предыдущий раздел',
        next: 'Следующий раздел',
      },
      lastUpdated: {
        text: 'Последнее обновление',
        formatOptions: {
          dateStyle: 'medium',
          timeStyle: 'short',
        },
      },
      footer: {
        message: 'NextGen VPN Engineering Documentation Portal & API Reference.',
        copyright: '© 2026 NextGen VPN Team. Не закоммичено в git.',
      },
    },
  })
)
