package com.vpn.server.service;

import com.vpn.server.entity.BalanceEntry;
import com.vpn.server.entity.PromoCode;
import com.vpn.server.entity.User;
import com.vpn.server.repository.BalanceEntryRepository;
import com.vpn.server.repository.PromoCodeRepository;
import com.vpn.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class PromoCodeService {

    private final PromoCodeRepository promoCodeRepository;
    private final UserRepository userRepository;
    private final BalanceEntryRepository balanceEntryRepository;

    public PromoCodeService(
            PromoCodeRepository promoCodeRepository,
            UserRepository userRepository,
            BalanceEntryRepository balanceEntryRepository
    ) {
        this.promoCodeRepository = promoCodeRepository;
        this.userRepository = userRepository;
        this.balanceEntryRepository = balanceEntryRepository;
    }

    @Transactional
    public Map<String, Object> applyPromoCode(Long userId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new IllegalArgumentException("Promo code cannot be empty");
        }
        String code = rawCode.trim().toUpperCase();

        PromoCode promo = promoCodeRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Invalid promo code"));

        if (!promo.isActive()) {
            throw new IllegalStateException("This promo code is no longer active");
        }

        if (promo.getExpiresAt() != null && promo.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalStateException("This promo code has expired");
        }

        if (promo.getMaxActivations() != null && promo.getActivationsCount() >= promo.getMaxActivations()) {
            throw new IllegalStateException("This promo code has reached its activation limit");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String referenceId = "promo:" + promo.getId() + ":" + user.getId();
        if (balanceEntryRepository.existsByReferenceId(referenceId)) {
            throw new IllegalStateException("You have already used this promo code");
        }

        long bonus = promo.getBonusAmountUsdtMicro();
        long newBalance = user.getBalanceUsdtMicro() + bonus;
        user.setBalanceUsdtMicro(newBalance);
        userRepository.save(user);

        BalanceEntry entry = new BalanceEntry();
        entry.setUser(user);
        entry.setAmountUsdtMicro(bonus);
        entry.setBalanceAfterMicro(newBalance);
        entry.setType("PROMO_CODE");
        entry.setDescription("Promo code activation: " + promo.getCode());
        entry.setReferenceId(referenceId);
        balanceEntryRepository.save(entry);

        promo.setActivationsCount(promo.getActivationsCount() + 1);
        promoCodeRepository.save(promo);

        return Map.of(
                "success", true,
                "bonusUsdtMicro", bonus,
                "newBalanceUsdtMicro", newBalance,
                "code", promo.getCode()
        );
    }
}
