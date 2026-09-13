# OpenAPI 3.1 & Спецификация для Postman / Swagger

Данная страница содержит готовую формальную спецификацию **OpenAPI 3.1.0** для всего REST API Aura VPN. Вы можете скопировать этот файл и импортировать его в **Postman**, **Insomnia**, **Swagger Editor** или использовать для генерации клиентских SDK на TypeScript, Go, Java, Swift.

---

## 1. Компоненты безопасности и схемы (OpenAPI Components)

```yaml
openapi: 3.1.0
info:
  title: Aura VPN REST API
  description: Высокоустойчивый к цензуре VPN-сервис (Spring Boot 4.1, PostgreSQL 17, Xray-core)
  version: 1.0.0
servers:
  - url: https://vpn.struchev.site/api/v1
    description: Production API Server
  - url: http://localhost:8080/api/v1
    description: Local Development Server

paths:
  /auth/register:
    post:
      summary: Регистрация по email и паролю
      tags: [Auth]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/RegisterRequest'
      responses:
        '200':
          description: Успешная регистрация
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AuthResponse'
        '400':
          $ref: '#/components/responses/400BadRequest'

  /auth/login:
    post:
      summary: Аутентификация с опциональным слиянием гостя
      tags: [Auth]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/LoginRequest'
      responses:
        '200':
          description: Успешный вход
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AuthResponse'

  /auth/device:
    post:
      summary: 1-Click анонимная авторизация по UUID устройства
      tags: [Auth]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/DeviceAuthRequest'
      responses:
        '200':
          description: Токен сессии гостя
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AuthResponse'

  /user/profile:
    get:
      summary: Получение профиля, баланса и подписки
      tags: [User]
      security:
        - BearerAuth: []
      responses:
        '200':
          description: Данные профиля
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserProfileResponse'
        '401':
          $ref: '#/components/responses/401Unauthorized'

  /user/subscription/links:
    get:
      summary: Получение VLESS-ссылок на активные ноды
      tags: [User]
      security:
        - BearerAuth: []
      parameters:
        - name: region
          in: query
          required: false
          schema:
            type: string
          example: "nl-ams"
      responses:
        '200':
          description: Список ссылок конфигурации
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SubscriptionLinksResponse'

  /user/billing/invoice:
    post:
      summary: Выпуск инвойса пополнения с микро-допуском
      tags: [Billing]
      security:
        - BearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateInvoiceRequest'
      responses:
        '200':
          description: Инвойс с уникальной суммой
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CryptoInvoice'

  /user/billing/purchase:
    post:
      summary: Списание с баланса и покупка тарифа
      tags: [Billing]
      security:
        - BearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [tariffId]
              properties:
                tariffId:
                  type: string
                  example: "pro"
                isAnnual:
                  type: boolean
                  example: true
      responses:
        '200':
          description: Подписка успешно продлена
        '400':
          description: Недостаточно средств (Shortfall)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/InsufficientBalanceResponse'

  /admin/dashboard:
    get:
      summary: Метрики системы и телеметрия ТСПУ
      tags: [Admin]
      security:
        - AdminAuth: []
      responses:
        '200':
          description: Сводка дашборда
        '403':
          $ref: '#/components/responses/403Forbidden'

components:
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
    AdminAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT

  schemas:
    RegisterRequest:
      type: object
      required: [email, password]
      properties:
        email:
          type: string
          format: email
          example: "user@example.com"
        password:
          type: string
          minLength: 8
          example: "StrongPassword2026!"
        referralCode:
          type: string
          example: "REF42ABC"

    LoginRequest:
      type: object
      required: [email, password]
      properties:
        email:
          type: string
          example: "user@example.com"
        password:
          type: string
          example: "StrongPassword2026!"
        deviceUuid:
          type: string
          format: uuid
          example: "e7b91d22-8354-4f01-9876-123456789abc"

    DeviceAuthRequest:
      type: object
      required: [deviceUuid]
      properties:
        deviceUuid:
          type: string
          format: uuid
          example: "e7b91d22-8354-4f01-9876-123456789abc"
        referralCode:
          type: string
          example: "REF42ABC"

    AuthResponse:
      type: object
      properties:
        token:
          type: string
          example: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
        userId:
          type: integer
          example: 12
        email:
          type: string
          example: "user@example.com"
        role:
          type: string
          enum: [USER, ADMIN]
          example: "USER"
        referralCode:
          type: string
          example: "REF99XYZ"

    UserProfileResponse:
      type: object
      properties:
        id:
          type: integer
          example: 12
        email:
          type: string
          example: "user@example.com"
        role:
          type: string
          example: "USER"
        balanceUsdtMicro:
          type: integer
          format: int64
          example: 15000000
        referralCode:
          type: string
          example: "REF99XYZ"
        referralLink:
          type: string
          example: "https://vpn.struchev.site/?ref=REF99XYZ"
        telegramLinked:
          type: boolean
          example: true
        isGuest:
          type: boolean
          example: false
        hasActiveSubscription:
          type: boolean
          example: true
        hasUsedTrial:
          type: boolean
          example: true
        subscription:
          type: object
          nullable: true

    SubscriptionLinksResponse:
      type: object
      properties:
        count:
          type: integer
          example: 2
        requestedRegion:
          type: string
          nullable: true
          example: "nl-ams"
        requestedRegionAvailable:
          type: boolean
          example: true
        links:
          type: array
          items:
            type: string
            example: "vless://9b1deb4d...#Aura-AMS-01"

    CreateInvoiceRequest:
      type: object
      required: [baseAmountUsdtMicro]
      properties:
        baseAmountUsdtMicro:
          type: integer
          format: int64
          example: 10000000
        chain:
          type: string
          enum: [TRON, ETHEREUM, BASE, ARBITRUM, POLYGON]
          default: TRON

    CryptoInvoice:
      type: object
      properties:
        invoiceId:
          type: integer
          example: 789
        chain:
          type: string
          example: "TRON"
        recipientAddress:
          type: string
          example: "TYDzsYUEpvnYmQK4zGP9s217x5MRxurGeB"
        expectedAmountUsdtMicro:
          type: integer
          format: int64
          example: 10342000
        expectedAmountUsdt:
          type: number
          format: double
          example: 10.342
        toleranceMinMicro:
          type: integer
          format: int64
          example: 10341600
        toleranceMaxMicro:
          type: integer
          format: int64
          example: 10342400
        status:
          type: string
          enum: [PENDING, PAID, EXPIRED]
          example: "PENDING"
        expiresAt:
          type: string
          format: date-time

    InsufficientBalanceResponse:
      type: object
      properties:
        error:
          type: string
          example: "INSUFFICIENT_BALANCE"
        requiredUsdtMicro:
          type: integer
          format: int64
          example: 20000000
        currentUsdtMicro:
          type: integer
          format: int64
          example: 15000000
        shortfallUsdtMicro:
          type: integer
          format: int64
          example: 5000000

  responses:
    400BadRequest:
      description: Неверные параметры запроса или логическая ошибка
      content:
        application/json:
          schema:
            type: object
            properties:
              error:
                type: string
                example: "Invalid email or password"
    401Unauthorized:
      description: Токен не передан, просрочен или невалиден
      content:
        application/json:
          schema:
            type: object
            properties:
              error:
                type: string
                example: "Unauthorized"
    403Forbidden:
      description: Доступ запрещен (недостаточно прав или аккаунт заблокирован)
      content:
        application/json:
          schema:
            type: object
            properties:
              error:
                type: string
                example: "Forbidden"
```
