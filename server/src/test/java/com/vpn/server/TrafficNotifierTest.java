package com.vpn.server;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.service.TelegramBotService;
import com.vpn.server.service.TrafficNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrafficNotifierTest {

    private SubscriptionRepository subscriptionRepository;
    private TelegramBotService telegramBotService;
    private TrafficNotifier notifier;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        telegramBotService = mock(TelegramBotService.class);
        notifier = new TrafficNotifier(subscriptionRepository, telegramBotService);
    }

    private static Subscription sub(String tariffId, Long telegramId) {
        User user = new User();
        user.setId(50L);
        user.setTelegramId(telegramId);
        Tariff tariff = new Tariff();
        tariff.setId(tariffId);
        tariff.setName(tariffId.equals("trial") ? "Пробный" : "Pro");
        Subscription s = new Subscription();
        s.setId(11L);
        s.setUser(user);
        s.setTariff(tariff);
        s.setStatus("ACTIVE");
        s.setTrafficLimitBytes(100L * 1024 * 1024 * 1024);
        s.setTrafficUsedBytes(95L * 1024 * 1024 * 1024);
        s.setCurrentPeriodEnd(Instant.now().plus(10, ChronoUnit.DAYS));
        return s;
    }

    @Test
    void lowTrafficIsWarnedOnceWithWhatIsLeft() {
        Subscription s = sub("pro", 900L);
        when(subscriptionRepository.findLowTrafficUnwarned()).thenReturn(List.of(s));
        Instant now = Instant.now();

        notifier.warnLowTraffic(now);

        verify(telegramBotService).sendTextMessage(eq(900L), contains("5.0 ГБ"), isNull());
        assertEquals(now, s.getTrafficWarningSentAt());
    }

    @Test
    void withoutTelegramThePeriodIsStillMarked() {
        Subscription s = sub("pro", null);
        when(subscriptionRepository.findLowTrafficUnwarned()).thenReturn(List.of(s));

        notifier.warnLowTraffic(Instant.now());

        verifyNoInteractions(telegramBotService);
        assertNotNull(s.getTrafficWarningSentAt());
    }

    @Test
    void exhaustedTrialPointsAtThePlans() {
        notifier.notifyExhausted(sub("trial", 900L));
        verify(telegramBotService).sendTextMessage(eq(900L), contains("Пробный трафик закончился"), isNull());
    }

    @Test
    void exhaustedAnnualPlanNamesTheMonthlyResetNotTheYearEnd() {
        Subscription s = sub("pro", 900L);
        s.setCurrentPeriodEnd(Instant.parse("2027-06-01T10:00:00Z"));
        s.setTrafficResetAt(Instant.parse("2026-10-20T10:00:00Z"));

        notifier.notifyExhausted(s);

        verify(telegramBotService).sendTextMessage(eq(900L), contains("20 октября"), isNull());
    }
}
