package com.vpn.server.service;

import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * No-signup trial flow for the desktop client (see AuthController#deviceAuth):
 * a fresh install generates a local device UUID and logs in with it, instead
 * of forcing registration/login before the app is usable. Mirrors
 * TelegramAuthService's find-or-create-then-grant-trial shape, keyed by
 * User.deviceUuid instead of telegramId.
 */
@Service
public class DeviceAuthService {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthService.class);

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TariffRepository tariffRepository;
    private final JwtUtil jwtUtil;
    private final SecureRandom random = new SecureRandom();

    public DeviceAuthService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            TariffRepository tariffRepository,
            JwtUtil jwtUtil
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffRepository = tariffRepository;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    public AuthResponse authenticateDevice(String deviceUuid, String referralCode) {
        if (deviceUuid == null || deviceUuid.isBlank()) {
            throw new IllegalArgumentException("deviceUuid cannot be empty");
        }

        User user = userRepository.findByDeviceUuid(deviceUuid.trim())
                .orElseGet(() -> createNewDeviceUser(deviceUuid.trim(), referralCode));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }

    private User createNewDeviceUser(String deviceUuid, String referralCode) {
        User user = new User();
        user.setDeviceUuid(deviceUuid);
        user.setEmail("device_" + deviceUuid + "@device.local");
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setBalanceUsdtMicro(0L);
        user.setReferralCode(generateUniqueReferralCode());

        if (referralCode != null && !referralCode.isBlank()) {
            userRepository.findByReferralCode(referralCode.trim())
                    .ifPresent(user::setReferredBy);
        }

        user = userRepository.save(user);

        // Grant 3-day trial subscription if trial tariff exists (same shape as
        // TelegramAuthService#createNewTelegramUser).
        Optional<Tariff> trialTariff = tariffRepository.findById("trial");
        if (trialTariff.isPresent() && trialTariff.get().getIsActive()) {
            Subscription sub = new Subscription();
            sub.setUser(user);
            sub.setTariff(trialTariff.get());
            sub.setStatus("ACTIVE");
            sub.setIsAnnual(false);
            sub.setAutoRenew(false);
            sub.setCurrentPeriodStart(Instant.now());
            sub.setCurrentPeriodEnd(Instant.now().plus(3, ChronoUnit.DAYS));
            sub.setTrafficUsedBytes(0L);
            sub.setTrafficLimitBytes(trialTariff.get().getTrafficQuotaBytes());
            subscriptionRepository.save(sub);
            log.info("Granted trial subscription to new device user {}", user.getId());
        }

        return user;
    }

    private String generateUniqueReferralCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempts = 0; attempts < 10; attempts++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            String code = sb.toString();
            if (!userRepository.existsByReferralCode(code)) {
                return code;
            }
        }
        return "DEV" + System.currentTimeMillis();
    }
}
