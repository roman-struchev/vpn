package com.vpn.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vpn.server.entity.*;
import com.vpn.server.repository.BalanceEntryRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);
    private static final long STARS_TO_MICRO_USDT_RATE = 20_000L; // 1 Star = 0.02 USDT = 20,000 micro-USDT

    // Same denominations offered by the /balance inline keyboard (sendBalanceMenu)
    // -- also the whitelist for the "stars_<amount>" /start deep-link payload
    // (see UserController's Stars top-up UI), so a tampered/arbitrary amount in
    // a deep link can't be used to request an invoice for a made-up value.
    private static final Set<Integer> ALLOWED_STAR_AMOUNTS = Set.of(50, 250, 500, 1000);

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TariffRepository tariffRepository;
    private final BalanceEntryRepository balanceEntryRepository;
    private final SubscriptionExportService exportService;
    private final DeviceManagementService deviceManagementService;
    private final BillingService billingService;
    private final TelegramLinkService telegramLinkService;
    private final CurrentPlanService currentPlanService;
    private final OneTimeCodeService oneTimeCodeService;
    private final org.springframework.beans.factory.ObjectProvider<com.vpn.server.grpc.AgentStreamServiceImpl> agentStream;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient httpClient;

    @Value("${vpn.telegram.bot-token:}")
    private String botToken;

    @Value("${vpn.telegram.bot-username:MyVpnBot}")
    private String botUsername = "MyVpnBot";

    @Value("${vpn.telegram.mini-app-url:https://vpn.example.com}")
    private String miniAppUrl = "https://vpn.example.com";

    public TelegramBotService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            TariffRepository tariffRepository,
            BalanceEntryRepository balanceEntryRepository,
            SubscriptionExportService exportService,
            DeviceManagementService deviceManagementService,
            BillingService billingService,
            TelegramLinkService telegramLinkService,
            CurrentPlanService currentPlanService,
            OneTimeCodeService oneTimeCodeService,
            org.springframework.beans.factory.ObjectProvider<com.vpn.server.grpc.AgentStreamServiceImpl> agentStream
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffRepository = tariffRepository;
        this.balanceEntryRepository = balanceEntryRepository;
        this.exportService = exportService;
        this.deviceManagementService = deviceManagementService;
        this.billingService = billingService;
        this.telegramLinkService = telegramLinkService;
        this.currentPlanService = currentPlanService;
        this.oneTimeCodeService = oneTimeCodeService;
        this.agentStream = agentStream;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public void setBotUsername(String botUsername) {
        this.botUsername = botUsername;
    }

    public void setMiniAppUrl(String miniAppUrl) {
        this.miniAppUrl = miniAppUrl;
    }

    @Transactional
    public void processUpdate(JsonNode update) {
        if (update.has("message")) {
            handleMessage(update.path("message"));
        } else if (update.has("callback_query")) {
            handleCallbackQuery(update.path("callback_query"));
        } else if (update.has("pre_checkout_query")) {
            handlePreCheckoutQuery(update.path("pre_checkout_query"));
        }
    }

    private void handleMessage(JsonNode message) {
        if (message.has("successful_payment")) {
            handleSuccessfulPayment(message, message.path("successful_payment"));
            return;
        }

        long chatId = message.path("chat").path("id").asLong();
        JsonNode from = message.path("from");
        long telegramId = from.path("id").asLong();
        String text = message.path("text").asText("").trim();

        // "/start link_<code>" attaches THIS Telegram account to an existing web
        // account (see UserController#createTelegramLink). It has to be handled
        // BEFORE getOrCreateUser() below: that call unconditionally creates (and
        // persists, with a trial subscription + device) a brand-new bot user the
        // first time it sees a telegramId, which is exactly wrong here -- the
        // whole point of this payload is to attach this Telegram identity to the
        // *existing* target user, not spawn a second account for the same
        // person. It also must be checked before the generic referral-code
        // fallback further down, or "link_<code>"/"stars_<amount>" would be
        // misread as a referral code and silently ignored (and a spurious user
        // would still get created).
        if (text.startsWith("/start")) {
            String[] startParts = text.split("\\s+");
            String startPayload = startParts.length > 1 ? startParts[1] : null;
            if (startPayload != null && startPayload.startsWith("link_")) {
                handleAccountLinkStart(chatId, telegramId, startPayload.substring("link_".length()));
                return;
            }
        }

        User user = getOrCreateUser(telegramId, from);

        if (text.startsWith("/start")) {
            String[] parts = text.split("\\s+");
            String payload = parts.length > 1 ? parts[1] : null;
            if (payload != null && payload.startsWith("stars_")) {
                handleStarsStart(chatId, user, payload.substring("stars_".length()));
            } else {
                if (parts.length > 1 && user.getReferredBy() == null) {
                    applyReferralCode(user, parts[1]);
                }
                sendWelcomeMessage(chatId, user);
            }
        } else if (text.equalsIgnoreCase("/vpn") || text.equalsIgnoreCase("/status")) {
            sendVpnStatus(chatId, user);
        } else if (text.equalsIgnoreCase("/balance")) {
            sendBalanceMenu(chatId, user);
        } else if (text.equalsIgnoreCase("/ref") || text.equalsIgnoreCase("/referral")) {
            sendReferralInfo(chatId, user);
        } else if (text.equalsIgnoreCase("/diag")) {
            sendDiagnosticInfo(chatId);
        } else if (text.equalsIgnoreCase("/plans") || text.equalsIgnoreCase("/tariffs")) {
            sendPlansMenu(chatId, user);
        } else if (text.equalsIgnoreCase("/login")) {
            sendAppLoginCode(chatId, user);
        } else {
            sendWelcomeMessage(chatId, user);
        }
    }

    private void handleCallbackQuery(JsonNode callbackQuery) {
        String callbackId = callbackQuery.path("id").asText();
        String data = callbackQuery.path("data").asText();
        JsonNode message = callbackQuery.path("message");
        long chatId = message.path("chat").path("id").asLong();
        long telegramId = callbackQuery.path("from").path("id").asLong();

        answerCallback(callbackId, "");

        User user = userRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return;
        }

        if (data.startsWith("stars:")) {
            int stars = Integer.parseInt(data.substring("stars:".length()));
            sendStarsInvoice(chatId, user, stars);
        } else if ("cmd_vpn".equals(data)) {
            sendVpnStatus(chatId, user);
        } else if ("cmd_balance".equals(data)) {
            sendBalanceMenu(chatId, user);
        } else if ("cmd_ref".equals(data)) {
            sendReferralInfo(chatId, user);
        } else if ("cmd_diag".equals(data)) {
            sendDiagnosticInfo(chatId);
        } else if ("cmd_plans".equals(data)) {
            sendPlansMenu(chatId, user);
        } else if ("cmd_login".equals(data)) {
            sendAppLoginCode(chatId, user);
        } else if (data.startsWith("buy:")) {
            buyPlan(chatId, user, data.substring("buy:".length()));
        } else if (data.startsWith("next:")) {
            schedulePlan(chatId, user, data.substring("next:".length()));
        }
    }

    private void handlePreCheckoutQuery(JsonNode preCheckoutQuery) {
        String queryId = preCheckoutQuery.path("id").asText();
        answerPreCheckout(queryId, true, null);
    }

    @Transactional
    public void handleSuccessfulPayment(JsonNode message, JsonNode payment) {
        long chatId = message.path("chat").path("id").asLong();
        long telegramId = message.path("from").path("id").asLong();
        long totalAmountStars = payment.path("total_amount").asLong();
        String chargeId = payment.path("telegram_payment_charge_id").asText();

        if (chargeId.isBlank() || balanceEntryRepository.existsByReferenceId(chargeId)) {
            log.warn("Payment charge ID {} already credited or invalid, skipping", chargeId);
            return;
        }

        User user = userRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            log.error("User not found for Telegram payment: telegramId={}", telegramId);
            return;
        }

        long creditedMicro = totalAmountStars * STARS_TO_MICRO_USDT_RATE;
        long newBalance = user.getBalanceUsdtMicro() + creditedMicro;
        user.setBalanceUsdtMicro(newBalance);
        userRepository.save(user);

        BalanceEntry entry = new BalanceEntry();
        entry.setUser(user);
        entry.setAmountUsdtMicro(creditedMicro);
        entry.setBalanceAfterMicro(newBalance);
        entry.setType("DEPOSIT");
        entry.setDescription("Telegram Stars deposit (" + totalAmountStars + " Stars)");
        entry.setReferenceId(chargeId);
        balanceEntryRepository.save(entry);

        log.info("Credited {} Stars ({} micro-USDT) to user #{}", totalAmountStars, creditedMicro, user.getId());

        // Referral bonuses (15% to referrer always, 10% one-time welcome bonus
        // to this user on their first-ever deposit) — same rules for every
        // deposit path, see BillingService#applyReferralRewards.
        BillingService.ReferralRewardResult referralResult =
                billingService.applyReferralRewards(user, creditedMicro, chargeId);
        if (referralResult.referrerBonusMicro() > 0 && user.getReferredBy() != null
                && user.getReferredBy().getTelegramId() != null) {
            sendTextMessage(user.getReferredBy().getTelegramId(),
                    String.format("🎉 <b>Реферальный бонус!</b>\nВаш приглашённый друг пополнил баланс. Вам начислено <b>+$%.2f</b> USDT!",
                            referralResult.referrerBonusMicro() / 1_000_000.0), null);
        }

        String confirmation = String.format(
                "✅ <b>Оплата прошла успешно!</b>\n\n" +
                "Начислено: <b>%d Stars</b> (+$%.2f USDT)\n" +
                "Текущий баланс: <b>$%.2f USDT</b>\n\n" +
                "Баланс используется для автоматического продления вашей подписки.",
                totalAmountStars, creditedMicro / 1_000_000.0, newBalance / 1_000_000.0
        );
        sendTextMessage(chatId, confirmation, null);
    }

    private User getOrCreateUser(long telegramId, JsonNode from) {
        return userRepository.findByTelegramId(telegramId).orElseGet(() -> {
            User newUser = new User();
            newUser.setTelegramId(telegramId);
            newUser.setRole("USER");
            newUser.setStatus("ACTIVE");
            newUser.setBalanceUsdtMicro(0L);
            newUser.setReferralCode(generateUniqueReferralCode());
            newUser = userRepository.save(newUser);

            // Trial: no time limit, the traffic quota is the only cap — the same
            // trial whichever door the user came in through (see BillingService).
            Tariff trialTariff = tariffRepository.findById("trial").orElse(null);
            if (trialTariff != null) {
                Subscription trialSub = new Subscription();
                trialSub.setUser(newUser);
                trialSub.setTariff(trialTariff);
                trialSub.setStatus("ACTIVE");
                trialSub.setIsAnnual(false);
                trialSub.setAutoRenew(false);
                trialSub.setCurrentPeriodStart(Instant.now());
                trialSub.setCurrentPeriodEnd(Instant.now().plus(BillingService.NO_EXPIRY_DAYS, ChronoUnit.DAYS));
                trialSub.setTrafficUsedBytes(0L);
                trialSub.setTrafficLimitBytes(trialTariff.getTrafficQuotaBytes());
                subscriptionRepository.save(trialSub);

                try {
                    deviceManagementService.addDevice(newUser.getId(), "Telegram Bot", "THIRD_PARTY");
                } catch (Exception e) {
                    log.warn("Failed to create initial device for new user #{}: {}", newUser.getId(), e.getMessage());
                }
            }

            return newUser;
        });
    }

    private void applyReferralCode(User user, String code) {
        if (code == null || code.isBlank() || code.equalsIgnoreCase(user.getReferralCode())) {
            return;
        }
        userRepository.findByReferralCode(code).ifPresent(ref -> {
            if (!ref.getId().equals(user.getId())) {
                user.setReferredBy(ref);
                userRepository.save(user);
                log.info("User #{} linked to referrer #{}", user.getId(), ref.getId());
            }
        });
    }

    /**
     * Handles "/start link_&lt;code&gt;": attaches this Telegram chat to the web
     * user that requested the pending link (see UserController#createTelegramLink).
     * Never overwrites/merges an unrelated account -- if this Telegram identity
     * is already linked to a *different* user, the request is rejected.
     */
    private void handleAccountLinkStart(long chatId, long telegramId, String code) {
        Optional<Long> targetUserId = telegramLinkService.consume(code);
        if (targetUserId.isEmpty()) {
            sendTextMessage(chatId,
                    "⚠️ <b>Ссылка для привязки недействительна или истекла.</b>\n\n" +
                    "Запросите новую ссылку в личном кабинете на сайте.",
                    null);
            return;
        }

        User targetUser = userRepository.findById(targetUserId.get()).orElse(null);
        if (targetUser == null) {
            sendTextMessage(chatId, "⚠️ Не удалось найти аккаунт для привязки. Попробуйте ещё раз.", null);
            return;
        }

        Optional<User> existingTelegramUser = userRepository.findByTelegramId(telegramId);
        if (existingTelegramUser.isPresent() && !existingTelegramUser.get().getId().equals(targetUser.getId())) {
            sendTextMessage(chatId,
                    "⚠️ <b>Этот Telegram-аккаунт уже привязан к другому аккаунту.</b>",
                    null);
            return;
        }

        if (existingTelegramUser.isEmpty()) {
            targetUser.setTelegramId(telegramId);
            userRepository.save(targetUser);
            log.info("Linked Telegram account {} to web user #{}", telegramId, targetUser.getId());
        }

        sendTextMessage(chatId,
                "✅ <b>Telegram успешно привязан к вашему аккаунту!</b>\n\n" +
                "Теперь вы можете пополнять баланс с помощью Telegram Stars прямо из личного кабинета на сайте.",
                null);
    }

    /**
     * Handles "/start stars_&lt;amount&gt;": same effect as tapping a "stars:&lt;n&gt;"
     * button in the /balance menu, reachable via a deep link from the web
     * dashboard (see DashboardView.tsx's Top Up modal). The amount is validated
     * against the same denominations the bot menu offers.
     */
    private void handleStarsStart(long chatId, User user, String amountText) {
        int stars;
        try {
            stars = Integer.parseInt(amountText);
        } catch (NumberFormatException e) {
            sendBalanceMenu(chatId, user);
            return;
        }
        if (!ALLOWED_STAR_AMOUNTS.contains(stars)) {
            sendBalanceMenu(chatId, user);
            return;
        }
        sendStarsInvoice(chatId, user, stars);
    }

    private void sendWelcomeMessage(long chatId, User user) {
        String text = "👋 <b>Добро пожаловать в быстрый и приватный VPN!</b>\n\n" +
                "🛡 Мы используем протоколы нового поколения (<b>XHTTP + Reality</b>) со встроенной устойчивостью к блокировкам.\n\n" +
                "Вам доступен <b>бесплатный пробный период</b>: 1 ГБ трафика без ограничения по времени.\n\n" +
                "Выберите действие в меню ниже:";

        Map<String, Object> keyboard = Map.of(
                "inline_keyboard", List.of(
                        List.of(
                                Map.of("text", "🚀 Подключить VPN", "callback_data", "cmd_vpn"),
                                Map.of("text", "💳 Баланс & Stars", "callback_data", "cmd_balance")
                        ),
                        List.of(
                                Map.of("text", "📦 Тарифы", "callback_data", "cmd_plans"),
                                Map.of("text", "📱 Войти в приложении", "callback_data", "cmd_login")
                        ),
                        List.of(
                                Map.of("text", "👥 Рефералка (15%)", "callback_data", "cmd_ref"),
                                Map.of("text", "🔍 Диагностика", "callback_data", "cmd_diag")
                        ),
                        List.of(
                                Map.of("text", "🌐 Открыть веб-кабинет", "web_app", Map.of("url", miniAppUrl))
                        )
                )
        );

        sendTextMessage(chatId, text, keyboard);
    }

    private static final java.time.format.DateTimeFormatter HUMAN_DATE =
            java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.forLanguageTag("ru"))
                    .withZone(java.time.ZoneId.of("Europe/Moscow"));

    public void sendVpnStatus(long chatId, User user) {
        Subscription sub = currentPlanService.currentPlan(user.getId()).orElse(null);
        String reason = currentPlanService.inactiveReason(user.getId(), sub);

        StringBuilder sb = new StringBuilder();
        if (sub == null) {
            sb.append(CurrentPlanService.EXPIRED.equals(reason)
                    ? "⚠️ <b>Срок тарифа закончился</b>\n\n"
                    : CurrentPlanService.TRIAL_USED_UP.equals(reason)
                            ? "⚠️ <b>Пробный трафик закончился</b>\n\n"
                            : "⚠️ <b>У вас нет тарифа</b>\n\n");
            sb.append("Выберите тариф — он оплачивается с баланса: /plans");
            sendTextMessage(chatId, sb.toString(), plansButton());
            return;
        }

        double usedGb = sub.getTrafficUsedBytes() / (1024.0 * 1024 * 1024);
        double limitGb = sub.getTrafficLimitBytes() / (1024.0 * 1024 * 1024);
        boolean noExpiry = sub.getOverrideTariff() == null && sub.hasNoExpiry();

        sb.append("🛡 <b>Ваш тариф: ").append(sub.getEffectiveTariff().getName()).append("</b>\n\n");
        if (CurrentPlanService.TRIAL_USED_UP.equals(reason)) {
            sb.append("⛔️ Пробный трафик закончился — выберите тариф: /plans\n");
        } else if (CurrentPlanService.TRAFFIC_USED_UP.equals(reason)) {
            sb.append("⛔️ Трафик закончился. Новый придёт <b>")
                    .append(HUMAN_DATE.format(TrafficNotifier.nextRefill(sub)))
                    .append("</b>, или купите тариф заново сейчас: /plans\n");
        }
        sb.append(noExpiry
                ? "⏳ Без ограничения по времени\n"
                : "⏳ Действует до: <b>" + HUMAN_DATE.format(sub.getEffectiveExpiresAt()) + "</b>\n");
        sb.append(String.format(java.util.Locale.US, "📊 Трафик: <b>%.2f из %.0f ГБ</b>\n\n", usedGb, limitGb));

        // The auto-updating subscription URL, not one vless:// key: a single
        // key points at one node and silently stops working with it.
        sb.append("🔑 <b>Ссылка-подписка для v2rayTun, Hiddify, Happ:</b>\n")
                .append("<code>").append(currentPlanService.subscriptionUrl(user)).append("</code>\n")
                .append("<i>Добавьте её в клиенте как подписку (не как ключ) — список серверов будет обновляться сам.</i>\n\n")
                .append("Или скачайте наше приложение и войдите по коду: /login");

        sendTextMessage(chatId, sb.toString(), null);
    }

    private Map<String, Object> plansButton() {
        return Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", "📦 Выбрать тариф", "callback_data", "cmd_plans"))));
    }

    /**
     * Plans with a button each, bought from the balance by the same rules as
     * on the web: the current plan renews, a pricier one starts now, a
     * cheaper one is scheduled for the end of the paid period.
     */
    public void sendPlansMenu(long chatId, User user) {
        Subscription sub = currentPlanService.currentPlan(user.getId()).orElse(null);
        boolean paidRunning = sub != null && "ACTIVE".equals(sub.getStatus())
                && sub.getTariff().getMonthlyPriceUsdtMicro() != null && sub.getTariff().getMonthlyPriceUsdtMicro() > 0;
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.US,
                "📦 <b>Тарифы</b> · баланс <b>$%.2f</b>\n\n", user.getBalanceUsdtMicro() / 1_000_000.0));
        List<List<Map<String, Object>>> rows = new java.util.ArrayList<>();
        for (com.vpn.server.entity.Tariff t : tariffRepository.findAll()) {
            if (!Boolean.TRUE.equals(t.getIsActive()) || "trial".equalsIgnoreCase(t.getId())) continue;
            long price = t.getMonthlyPriceUsdtMicro() == null ? 0 : t.getMonthlyPriceUsdtMicro();
            String priceText = String.format(java.util.Locale.US, "$%.2f", price / 1_000_000.0);
            sb.append("• <b>").append(t.getName()).append("</b> — ").append(priceText).append(" в месяц, ")
                    .append(t.getTrafficQuotaBytes() / (1024L * 1024 * 1024)).append(" ГБ, устройств: ").append(t.getMaxDevices()).append("\n");
            boolean isCurrent = sub != null && sub.getTariff().getId().equalsIgnoreCase(t.getId());
            String label;
            String data;
            if (isCurrent && paidRunning) {
                label = "🔁 Продлить " + t.getName() + " · " + priceText;
                data = "buy:" + t.getId();
            } else if (paidRunning && price < sub.getTariff().getMonthlyPriceUsdtMicro()) {
                label = "⏭ " + t.getName() + " с " + HUMAN_DATE.format(sub.getCurrentPeriodEnd());
                data = "next:" + t.getId();
            } else {
                label = (paidRunning ? "⬆️ Перейти на " : "✅ ") + t.getName() + " · " + priceText;
                data = "buy:" + t.getId();
            }
            rows.add(List.of(Map.of("text", label, "callback_data", data)));
        }
        rows.add(List.of(Map.of("text", "💳 Пополнить баланс", "callback_data", "cmd_balance")));
        sb.append("\nОплата с баланса, продление — автоматически в конце месяца.");
        sendTextMessage(chatId, sb.toString(), Map.of("inline_keyboard", rows));
    }

    private void buyPlan(long chatId, User user, String tariffId) {
        try {
            Subscription sub = billingService.purchaseOrRenewSubscription(user.getId(), tariffId, false);
            com.vpn.server.grpc.AgentStreamServiceImpl stream = agentStream.getIfAvailable();
            if (stream != null) stream.pushConfigSyncToAll();
            sendTextMessage(chatId, "✅ Готово: тариф «" + sub.getTariff().getName() + "» до <b>"
                    + HUMAN_DATE.format(sub.getCurrentPeriodEnd()) + "</b>. Подключение: /vpn", null);
        } catch (InsufficientBalanceException e) {
            sendTextMessage(chatId, String.format(java.util.Locale.US,
                    "Не хватает <b>$%.2f</b> на балансе. Пополните и нажмите ещё раз.", e.getShortfallUsdtMicro() / 1_000_000.0),
                    Map.of("inline_keyboard", List.of(List.of(Map.of("text", "💳 Пополнить баланс", "callback_data", "cmd_balance")))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendTextMessage(chatId, "Не получилось: " + e.getMessage(), null);
        }
    }

    private void schedulePlan(long chatId, User user, String tariffId) {
        try {
            Subscription sub = billingService.scheduleNextTariff(user.getId(), tariffId);
            sendTextMessage(chatId, "⏭ Перейдёте на «" + sub.getNextTariff().getName() + "» "
                    + HUMAN_DATE.format(sub.getCurrentPeriodEnd()) + ". Сейчас ничего не списано.", null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendTextMessage(chatId, "Не получилось: " + e.getMessage(), null);
        }
    }

    /** A code to sign in to the Android/desktop app as this Telegram account. */
    public void sendAppLoginCode(long chatId, User user) {
        String code = oneTimeCodeService.createLoginCode(user.getId());
        sendTextMessage(chatId, "📱 Код для входа в приложение: <code>" + code + "</code>\n\n"
                + "В приложении нажмите «Войти по коду из Telegram» и введите его. Код действует 10 минут и работает один раз.\n"
                + "Скачать приложение: " + currentPlanService.webBaseUrl(), null);
    }

    private void sendBalanceMenu(long chatId, User user) {
        double balanceUsdt = user.getBalanceUsdtMicro() / 1_000_000.0;

        String text = String.format(
                "💳 <b>Ваш баланс: $%.2f USDT</b>\n\n" +
                "Баланс используется для автопродления подписки без привязки карт.\n\n" +
                "Пополнить баланс с помощью <b>Telegram Stars</b>:",
                balanceUsdt
        );

        Map<String, Object> keyboard = Map.of(
                "inline_keyboard", List.of(
                        List.of(
                                Map.of("text", "⭐️ 50 Stars ($1.00)", "callback_data", "stars:50"),
                                Map.of("text", "⭐️ 250 Stars ($5.00)", "callback_data", "stars:250")
                        ),
                        List.of(
                                Map.of("text", "⭐️ 500 Stars ($10.00)", "callback_data", "stars:500"),
                                Map.of("text", "⭐️ 1000 Stars ($20.00)", "callback_data", "stars:1000")
                        )
                )
        );

        sendTextMessage(chatId, text, keyboard);
    }

    private void sendReferralInfo(long chatId, User user) {
        String link = "https://t.me/" + botUsername + "?start=" + user.getReferralCode();

        String text = "👥 <b>Реферальная программа</b>\n\n" +
                "Приглашайте друзей и получайте <b>15% с каждого пополнения</b> их баланса навсегда!\n\n" +
                "Ваша персональная ссылка для приглашений:\n" +
                "<code>" + link + "</code>\n\n" +
                "<i>Средства начисляются прямо на ваш VPN-баланс и продлевают сервис автоматически.</i>";

        sendTextMessage(chatId, text, null);
    }

    private void sendDiagnosticInfo(long chatId) {
        String text = "🔍 <b>Диагностика сети и блокировок</b>\n\n" +
                "Если соединение прерывается или не устанавливается:\n\n" +
                "1. <b>Проверка белых списков оператора</b>: откройте в браузере gosuslugi.ru или vk.com. " +
                "Если российские сайты открываются, а VPN не соединяется — ваш провайдер временно блокирует внешний интернет.\n\n" +
                "2. <b>Защита от бана</b>: наше приложение использует умный backoff (пауза 15-20 с при обрыве). Не нажимайте кнопку переподключения судорожно — частые попытки удлиняют блокировку с 2 до 10 минут!\n\n" +
                "3. Через 15 секунд нода автоматически переключится на резервный транспорт.";

        sendTextMessage(chatId, text, null);
    }

    public void sendStarsInvoice(long chatId, User user, int stars) {
        if (botToken == null || botToken.isBlank() || "mock".equalsIgnoreCase(botToken)) {
            log.info("Mock invoice created: chatId={}, stars={}", chatId, stars);
            return;
        }

        try {
            String uriStr = "https://api.telegram.org/bot" + botToken + "/sendInvoice";
            Map<String, Object> req = Map.of(
                    "chat_id", chatId,
                    "title", "Пополнение баланса VPN",
                    "description", "Пополнение баланса аккаунта на " + stars + " Stars",
                    "payload", "stars_deposit:" + user.getId() + ":" + stars,
                    "currency", "XTR",
                    "prices", List.of(Map.of("label", stars + " Stars", "amount", stars))
            );

            postJson(uriStr, req);
        } catch (Exception e) {
            log.error("Failed to send Stars invoice: {}", e.getMessage(), e);
        }
    }

    public void answerPreCheckout(String preCheckoutQueryId, boolean ok, String errorMessage) {
        if (botToken == null || botToken.isBlank() || "mock".equalsIgnoreCase(botToken)) {
            return;
        }

        try {
            String uriStr = "https://api.telegram.org/bot" + botToken + "/answerPreCheckoutQuery";
            Map<String, Object> req = new HashMap<>();
            req.put("pre_checkout_query_id", preCheckoutQueryId);
            req.put("ok", ok);
            if (errorMessage != null) {
                req.put("error_message", errorMessage);
            }

            postJson(uriStr, req);
        } catch (Exception e) {
            log.error("Failed to answer pre-checkout: {}", e.getMessage(), e);
        }
    }

    public void answerCallback(String callbackQueryId, String text) {
        if (botToken == null || botToken.isBlank() || "mock".equalsIgnoreCase(botToken)) {
            return;
        }

        try {
            String uriStr = "https://api.telegram.org/bot" + botToken + "/answerCallbackQuery";
            Map<String, Object> req = Map.of(
                    "callback_query_id", callbackQueryId,
                    "text", text
            );
            postJson(uriStr, req);
        } catch (Exception e) {
            log.warn("Failed to answer callback: {}", e.getMessage());
        }
    }

    public void sendTextMessage(long chatId, String text, Map<String, Object> replyMarkup) {
        if (botToken == null || botToken.isBlank() || "mock".equalsIgnoreCase(botToken)) {
            log.info("Mock message to chatId {}: {}", chatId, text);
            return;
        }

        try {
            String uriStr = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            Map<String, Object> req = new HashMap<>();
            req.put("chat_id", chatId);
            req.put("text", text);
            req.put("parse_mode", "HTML");
            if (replyMarkup != null) {
                req.put("reply_markup", replyMarkup);
            }

            postJson(uriStr, req);
        } catch (Exception e) {
            log.error("Failed to send message: {}", e.getMessage(), e);
        }
    }

    private void postJson(String uriStr, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(uriStr))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        httpClient.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private String generateUniqueReferralCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int i = 0; i < 10; i++) {
            StringBuilder sb = new StringBuilder("ref_");
            for (int j = 0; j < 8; j++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            String code = sb.toString();
            if (!userRepository.existsByReferralCode(code)) {
                return code;
            }
        }
        return "ref_" + System.currentTimeMillis();
    }
}
