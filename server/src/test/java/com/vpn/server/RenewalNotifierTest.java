package com.vpn.server;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.service.InsufficientBalanceException;
import com.vpn.server.service.RenewalNotifier;
import com.vpn.server.service.TelegramBotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenewalNotifierTest {

    private SubscriptionRepository subscriptionRepository;
    private TelegramBotService telegramBotService;
    private RenewalNotifier notifier;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        telegramBotService = mock(TelegramBotService.class);
        notifier = new RenewalNotifier(subscriptionRepository, telegramBotService);
    }

    private static Subscription dueSub(long balanceMicro, Long telegramId) {
        User user = new User();
        user.setId(40L);
        user.setBalanceUsdtMicro(balanceMicro);
        user.setTelegramId(telegramId);
        Tariff max = new Tariff();
        max.setId("max");
        max.setName("Максимальный");
        max.setMonthlyPriceUsdtMicro(5_000_000L);
        Tariff mid = new Tariff();
        mid.setId("mid");
        mid.setName("Средний");
        mid.setMonthlyPriceUsdtMicro(3_000_000L);
        Subscription sub = new Subscription();
        sub.setId(9L);
        sub.setUser(user);
        sub.setTariff(max);
        sub.setIsAnnual(false);
        sub.setAutoRenew(true);
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodEnd(Instant.now().plus(2, ChronoUnit.DAYS));
        return sub;
    }

    @Test
    void shortBalanceIsRemindedOnce() {
        Instant now = Instant.now();
        Subscription sub = dueSub(1_000_000L, 777L);
        when(subscriptionRepository.findRenewalsDueBefore(eq(now), any())).thenReturn(List.of(sub));

        notifier.remindUpcomingShortfalls(now);

        verify(telegramBotService).sendTextMessage(eq(777L), contains("$4.00"), isNull());
        assertEquals(now, sub.getRenewalReminderSentAt());
        verify(subscriptionRepository).save(sub);
    }

    @Test
    void reminderUsesTheScheduledCheaperPlansPrice() {
        Instant now = Instant.now();
        Subscription sub = dueSub(4_000_000L, 777L);
        sub.setNextTariff(new Tariff());
        sub.getNextTariff().setId("mid");
        sub.getNextTariff().setMonthlyPriceUsdtMicro(3_000_000L);
        when(subscriptionRepository.findRenewalsDueBefore(eq(now), any())).thenReturn(List.of(sub));

        notifier.remindUpcomingShortfalls(now);

        // $4 covers the $3 plan it renews into, even though the current one costs $5.
        verifyNoInteractions(telegramBotService);
        assertNull(sub.getRenewalReminderSentAt());
    }

    @Test
    void coveredRenewalIsLeftForALaterCheck() {
        Instant now = Instant.now();
        Subscription sub = dueSub(5_000_000L, 777L);
        when(subscriptionRepository.findRenewalsDueBefore(eq(now), any())).thenReturn(List.of(sub));

        notifier.remindUpcomingShortfalls(now);

        verifyNoInteractions(telegramBotService);
        assertNull(sub.getRenewalReminderSentAt());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void noTelegramStillMarksThePeriodSoItIsNotRecheckedEveryMinute() {
        Instant now = Instant.now();
        Subscription sub = dueSub(0L, null);
        when(subscriptionRepository.findRenewalsDueBefore(eq(now), any())).thenReturn(List.of(sub));

        notifier.remindUpcomingShortfalls(now);

        verifyNoInteractions(telegramBotService);
        assertEquals(now, sub.getRenewalReminderSentAt());
    }

    @Test
    void failedRenewalNamesTheShortfall() {
        Subscription sub = dueSub(1_000_000L, 777L);

        notifier.notifyRenewalFailed(sub, new InsufficientBalanceException(5_000_000L, 1_000_000L));

        verify(telegramBotService).sendTextMessage(eq(777L), contains("$4.00"), isNull());
    }
}
