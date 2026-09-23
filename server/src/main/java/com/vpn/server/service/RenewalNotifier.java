package com.vpn.server.service;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Auto-renewal is paid from the balance, so a short balance means the VPN
 * stops when the period ends. This warns the user in Telegram a few days
 * ahead while there is still time to top up, and again if the renewal did
 * fail. The web dashboard shows the same shortfall on the plan card.
 */
@Service
public class RenewalNotifier {

    private static final Logger log = LoggerFactory.getLogger(RenewalNotifier.class);
    static final int REMIND_DAYS_BEFORE = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
            .withZone(ZoneId.of("Europe/Moscow"));

    private final SubscriptionRepository subscriptionRepository;
    private final TelegramBotService telegramBotService;

    public RenewalNotifier(SubscriptionRepository subscriptionRepository, TelegramBotService telegramBotService) {
        this.subscriptionRepository = subscriptionRepository;
        this.telegramBotService = telegramBotService;
    }

    /** Once per period: warns about renewals due soon that the balance won't cover. */
    public void remindUpcomingShortfalls(Instant now) {
        List<Subscription> due = subscriptionRepository.findRenewalsDueBefore(now, now.plus(REMIND_DAYS_BEFORE, ChronoUnit.DAYS));
        for (Subscription sub : due) {
            User user = sub.getUser();
            long price = sub.getRenewalPriceUsdtMicro();
            long balance = user.getBalanceUsdtMicro() != null ? user.getBalanceUsdtMicro() : 0L;
            if (balance >= price) {
                // Covered for now; checked again next run in case the balance drops.
                continue;
            }
            if (user.getTelegramId() != null) {
                telegramBotService.sendTextMessage(user.getTelegramId(),
                        "⏳ Подписка «" + sub.getRenewalTariff().getName() + "» продлевается " + DATE.format(sub.getCurrentPeriodEnd())
                                + " с баланса, но на нём " + usd(balance) + " из " + usd(price) + " нужных.\n"
                                + "Пополните баланс на " + usd(price - balance) + ", иначе VPN отключится в этот день.",
                        null);
            }
            sub.setRenewalReminderSentAt(now);
            subscriptionRepository.save(sub);
            log.info("Low-balance renewal reminder for subscription {} (user {}): balance {} < {}",
                    sub.getId(), user.getId(), balance, price);
        }
    }

    /** The period ended and the balance couldn't pay for the next one. */
    public void notifyRenewalFailed(Subscription sub, InsufficientBalanceException e) {
        Long chatId = sub.getUser().getTelegramId();
        if (chatId == null) {
            return;
        }
        telegramBotService.sendTextMessage(chatId,
                "⚠️ Подписка «" + sub.getRenewalTariff().getName() + "» закончилась: на балансе не хватило "
                        + usd(e.getShortfallUsdtMicro()) + " для продления.\n"
                        + "Пополните баланс и выберите тариф в кабинете — VPN снова заработает сразу.",
                null);
    }

    private static String usd(long micro) {
        return String.format(Locale.US, "$%.2f", micro / 1_000_000.0);
    }
}
