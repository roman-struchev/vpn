package com.vpn.server;

import com.vpn.server.entity.BalanceEntry;
import com.vpn.server.entity.PromoCode;
import com.vpn.server.entity.User;
import com.vpn.server.repository.BalanceEntryRepository;
import com.vpn.server.repository.PromoCodeRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.PromoCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromoCodeServiceTest {

    @Mock
    private PromoCodeRepository promoCodeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BalanceEntryRepository balanceEntryRepository;

    private PromoCodeService promoCodeService;

    @BeforeEach
    void setUp() {
        promoCodeService = new PromoCodeService(promoCodeRepository, userRepository, balanceEntryRepository);
    }

    @Test
    void testApplyPromoCodeSuccess() {
        PromoCode promo = new PromoCode("WELCOME2026", 2_000_000L, 100, Instant.now().plus(1, ChronoUnit.DAYS));
        promo.setId(1L);

        User user = new User();
        user.setId(10L);
        user.setBalanceUsdtMicro(3_000_000L);

        when(promoCodeRepository.findByCodeIgnoreCase("WELCOME2026")).thenReturn(Optional.of(promo));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(balanceEntryRepository.existsByReferenceId("promo:1:10")).thenReturn(false);

        Map<String, Object> result = promoCodeService.applyPromoCode(10L, " welcome2026 ");

        assertTrue((Boolean) result.get("success"));
        assertEquals(2_000_000L, result.get("bonusUsdtMicro"));
        assertEquals(5_000_000L, result.get("newBalanceUsdtMicro"));
        assertEquals(5_000_000L, user.getBalanceUsdtMicro());
        assertEquals(1, promo.getActivationsCount());

        verify(userRepository).save(user);
        verify(promoCodeRepository).save(promo);

        ArgumentCaptor<BalanceEntry> entryCaptor = ArgumentCaptor.forClass(BalanceEntry.class);
        verify(balanceEntryRepository).save(entryCaptor.capture());
        BalanceEntry entry = entryCaptor.getValue();
        assertEquals("PROMO_CODE", entry.getType());
        assertEquals(2_000_000L, entry.getAmountUsdtMicro());
        assertEquals(5_000_000L, entry.getBalanceAfterMicro());
        assertEquals("promo:1:10", entry.getReferenceId());
    }

    @Test
    void testApplyPromoCodeAlreadyUsed() {
        PromoCode promo = new PromoCode("WELCOME2026", 2_000_000L, 100, null);
        promo.setId(1L);
        User user = new User();
        user.setId(10L);

        when(promoCodeRepository.findByCodeIgnoreCase("WELCOME2026")).thenReturn(Optional.of(promo));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(balanceEntryRepository.existsByReferenceId("promo:1:10")).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                promoCodeService.applyPromoCode(10L, "WELCOME2026")
        );
        assertEquals("You have already used this promo code", ex.getMessage());
        verify(balanceEntryRepository, never()).save(any());
    }

    @Test
    void testApplyPromoCodeExpired() {
        PromoCode promo = new PromoCode("OLD", 1_000_000L, null, Instant.now().minus(1, ChronoUnit.HOURS));
        promo.setId(2L);

        when(promoCodeRepository.findByCodeIgnoreCase("OLD")).thenReturn(Optional.of(promo));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                promoCodeService.applyPromoCode(10L, "OLD")
        );
        assertEquals("This promo code has expired", ex.getMessage());
    }

    @Test
    void testApplyPromoCodeLimitReached() {
        PromoCode promo = new PromoCode("LIMITED", 1_000_000L, 5, null);
        promo.setId(3L);
        promo.setActivationsCount(5);

        when(promoCodeRepository.findByCodeIgnoreCase("LIMITED")).thenReturn(Optional.of(promo));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                promoCodeService.applyPromoCode(10L, "LIMITED")
        );
        assertEquals("This promo code has reached its activation limit", ex.getMessage());
    }

    @Test
    void testApplyPromoCodeInactive() {
        PromoCode promo = new PromoCode("DISABLED", 1_000_000L, 10, null);
        promo.setId(4L);
        promo.setActive(false);

        when(promoCodeRepository.findByCodeIgnoreCase("DISABLED")).thenReturn(Optional.of(promo));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                promoCodeService.applyPromoCode(10L, "DISABLED")
        );
        assertEquals("This promo code is no longer active", ex.getMessage());
    }
}
