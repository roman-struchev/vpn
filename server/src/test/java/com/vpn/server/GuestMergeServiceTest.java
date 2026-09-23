package com.vpn.server;

import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.BalanceEntryRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.GuestMergeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuestMergeServiceTest {

    private UserRepository userRepository;
    private SubscriptionRepository subscriptionRepository;
    private GuestMergeService merge;
    private User guest;
    private User target;
    private Subscription guestTrial;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        merge = new GuestMergeService(userRepository, subscriptionRepository,
                mock(BalanceEntryRepository.class), mock(AgentStreamServiceImpl.class));

        guest = new User();
        guest.setId(1L);
        guest.setDeviceUuid("dev-1");
        guest.setBalanceUsdtMicro(0L);
        target = new User();
        target.setId(2L);
        target.setEmail("t@x.y");
        target.setPasswordHash("h");
        target.setBalanceUsdtMicro(0L);

        Tariff trial = new Tariff();
        trial.setId("trial");
        guestTrial = new Subscription();
        guestTrial.setUser(guest);
        guestTrial.setTariff(trial);
        guestTrial.setStatus("ACTIVE");

        when(userRepository.findByDeviceUuid("dev-1")).thenReturn(Optional.of(guest));
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(1L, "ACTIVE"))
                .thenReturn(Optional.of(guestTrial));
    }

    @Test
    void theGuestsTrialMovesToAnAccountThatNeverHadOne() {
        merge.mergeGuestIntoTarget("dev-1", 2L);
        assertSame(target, guestTrial.getUser());
    }

    @Test
    void aSpentPaidPlanIsNotHiddenBehindTheGuestsTrial() {
        Subscription spent = new Subscription();
        spent.setStatus("EXHAUSTED");
        spent.setCurrentPeriodEnd(Instant.now().plus(10, ChronoUnit.DAYS));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(2L, "EXHAUSTED"))
                .thenReturn(Optional.of(spent));

        merge.mergeGuestIntoTarget("dev-1", 2L);

        assertSame(guest, guestTrial.getUser());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void anAccountThatAlreadyHadATrialDoesNotGetAnother() {
        when(subscriptionRepository.existsByUserIdAndTariffId(2L, "trial")).thenReturn(true);

        merge.mergeGuestIntoTarget("dev-1", 2L);

        assertSame(guest, guestTrial.getUser());
    }
}
