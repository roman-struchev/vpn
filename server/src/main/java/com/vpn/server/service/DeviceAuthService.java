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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    // Runs the "create new device user" insert in its own, independent
    // transaction (REQUIRES_NEW) instead of via @Transactional on a method
    // called from within this same class (self-invocation bypasses Spring's
    // AOP proxy, so a plain @Transactional there would silently be ignored).
    // This matters for findOrCreateDeviceUser below: if the insert loses a
    // create-race, its own transaction must be fully rolled back and closed
    // *before* we retry the lookup, not inline in whatever transaction the
    // caller happens to be in - on Postgres, once one statement in a
    // transaction errors, that whole transaction is aborted and unusable for
    // any further statements (including a plain SELECT) until it is rolled
    // back, so the retry has to happen in a fresh transaction.
    private final TransactionTemplate newUserTransactionTemplate;

    public DeviceAuthService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            TariffRepository tariffRepository,
            JwtUtil jwtUtil,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffRepository = tariffRepository;
        this.jwtUtil = jwtUtil;
        this.newUserTransactionTemplate = new TransactionTemplate(transactionManager);
        this.newUserTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AuthResponse authenticateDevice(String deviceUuid, String referralCode) {
        if (deviceUuid == null || deviceUuid.isBlank()) {
            throw new IllegalArgumentException("deviceUuid cannot be empty");
        }

        User user = findOrCreateDeviceUser(deviceUuid.trim(), referralCode);

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }

    /**
     * Find-or-create keyed by deviceUuid, resilient to two concurrent
     * requests racing to create the same never-before-seen deviceUuid (e.g. a
     * client retrying a slow/timed-out first attempt). Both requests can see
     * "no user found yet" and both try to INSERT; the loser hits the unique
     * constraint on email/deviceUuid instead of the winner's row. Rather than
     * surfacing that as a 500, treat it as "someone else just created it" and
     * hand back the winner's (now-committed) user.
     */
    private User findOrCreateDeviceUser(String deviceUuid, String referralCode) {
        Optional<User> existing = userRepository.findByDeviceUuid(deviceUuid);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return newUserTransactionTemplate.execute(status -> createNewDeviceUser(deviceUuid, referralCode));
        } catch (DataIntegrityViolationException e) {
            // Lost the race: by the time this exception reaches us, the failed
            // insert's own (REQUIRES_NEW) transaction has already been rolled
            // back by TransactionTemplate, and the winner's insert must have
            // already committed (that's what our insert collided with) - so a
            // fresh lookup here is safe and should find it.
            return userRepository.findByDeviceUuid(deviceUuid).orElseThrow(() -> e);
        }
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
