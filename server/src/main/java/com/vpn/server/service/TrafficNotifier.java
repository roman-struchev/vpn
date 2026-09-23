package com.vpn.server.service;

import com.vpn.server.entity.Subscription;
import com.vpn.server.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Tells the user about their traffic before and when it runs out, in
 * Telegram. Without this the first sign of a spent quota was the VPN simply
 * stopping. The web dashboard and the apps show the same states on the plan
 * card (profile subscription.status / inactiveReason).
 */
@Service
public class TrafficNotifier {

    private static final Logger log = LoggerFactory.getLogger(TrafficNotifier.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
            .withZone(ZoneId.of("Europe/Moscow"));

    private final SubscriptionRepository subscriptionRepository;
    private final TelegramBotService telegramBotService;

    @Value("${vpn.public.web-base-url:https://vpn.struchev.site}")
    private String publicWebBaseUrl = "https://vpn.struchev.site";

    public TrafficNotifier(SubscriptionRepository subscriptionRepository, TelegramBotService telegramBotService) {
        this.subscriptionRepository = subscriptionRepository;
        this.telegramBotService = telegramBotService;
    }

    /** Once per traffic period: 90% of the quota is used. */
    public void warnLowTraffic(Instant now) {
        for (Subscription sub : subscriptionRepository.findLowTrafficUnwarned()) {
            Long chatId = sub.getUser().getTelegramId();
            if (chatId != null) {
                long leftMb = Math.max(0, sub.getTrafficLimitBytes() - sub.getTrafficUsedBytes()) / (1024 * 1024);
                telegramBotService.sendTextMessage(chatId,
                        "📉 Осталось " + formatMb(leftMb) + " трафика из " + formatGb(sub.getTrafficLimitBytes())
                                + " по тарифу «" + sub.getEffectiveTariff().getName() + "».\n"
                                + (isTrial(sub)
                                        ? "Когда он закончится, VPN остановится. Тарифы — в кабинете: " + cabinetUrl()
                                        : "Когда он закончится, VPN остановится до "
                                                + DATE.format(nextRefill(sub)) + ". Сменить тариф: " + cabinetUrl()),
                        null);
            }
            sub.setTrafficWarningSentAt(now);
            subscriptionRepository.save(sub);
            log.info("Low-traffic warning for subscription {} (user {})", sub.getId(), sub.getUser().getId());
        }
    }

    /** The quota ran out just now; the plan is EXHAUSTED. */
    public void notifyExhausted(Subscription sub) {
        Long chatId = sub.getUser().getTelegramId();
        if (chatId == null) {
            return;
        }
        String text = isTrial(sub)
                ? "⛔️ Пробный трафик закончился — VPN остановлен.\nВыберите тариф в кабинете, и он заработает сразу: " + cabinetUrl()
                : "⛔️ Трафик по тарифу «" + sub.getEffectiveTariff().getName() + "» закончился — VPN остановлен.\n"
                        + "Новый трафик придёт " + DATE.format(nextRefill(sub))
                        + ". Не хотите ждать — купите тариф заново в кабинете, он начнётся сегодня: " + cabinetUrl();
        telegramBotService.sendTextMessage(chatId, text, null);
    }

    /** When traffic comes back on its own: the annual plan's monthly reset, else the renewal. */
    static Instant nextRefill(Subscription sub) {
        return sub.getTrafficResetAt() != null && sub.getTrafficResetAt().isBefore(sub.getCurrentPeriodEnd())
                ? sub.getTrafficResetAt()
                : sub.getCurrentPeriodEnd();
    }

    private static boolean isTrial(Subscription sub) {
        return sub.getEffectiveTariff() != null && "trial".equalsIgnoreCase(sub.getEffectiveTariff().getId());
    }

    private String cabinetUrl() {
        String base = publicWebBaseUrl == null ? "" : publicWebBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/#tariffs";
    }

    private static String formatMb(long mb) {
        return mb >= 1024 ? String.format(Locale.US, "%.1f ГБ", mb / 1024.0) : mb + " МБ";
    }

    private static String formatGb(long bytes) {
        return String.format(Locale.US, "%.0f ГБ", bytes / (1024.0 * 1024 * 1024));
    }
}
