package com.vpn.server.service;

import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.entity.Device;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Getting back into an account and leaving it:
 * sign in to the apps with a one-time code, reset a forgotten password,
 * set or change email/password, delete the account.
 *
 * Before this an account could only be entered with the credential it was
 * made with: a Telegram-made account never got into the apps, and a
 * forgotten password lost the account and its balance for good.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final OneTimeCodeService codes;
    private final MailService mailService;
    private final TelegramBotService telegramBotService;
    private final DeviceManagementService deviceManagementService;

    public AccountService(UserRepository userRepository, SubscriptionRepository subscriptionRepository,
                          PasswordEncoder passwordEncoder, JwtUtil jwtUtil, OneTimeCodeService codes,
                          MailService mailService, TelegramBotService telegramBotService,
                          DeviceManagementService deviceManagementService) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.codes = codes;
        this.mailService = mailService;
        this.telegramBotService = telegramBotService;
        this.deviceManagementService = deviceManagementService;
    }

    /** The code from the bot's /login or the web dashboard, typed into an app. */
    @Transactional(readOnly = true)
    public AuthResponse loginWithCode(String code) {
        Long userId = codes.consumeLoginCode(code)
                .orElseThrow(() -> new IllegalArgumentException("The code is wrong or has expired. Get a new one."));
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        requireActive(user);
        return token(user);
    }

    /**
     * Sends a reset code to every channel the account has: its Telegram
     * chat, and its email when SMTP is set up. Says nothing about whether
     * the address exists — the caller always shows the same "if it's
     * yours, a code is on its way".
     */
    public void requestPasswordReset(String email) {
        if (email == null || email.isBlank()) return;
        User user = userRepository.findByEmail(email.toLowerCase().trim()).orElse(null);
        if (user == null || !"ACTIVE".equals(user.getStatus())) return;

        String code = codes.createResetCode(user.getId());
        boolean sent = false;
        if (user.getTelegramId() != null) {
            telegramBotService.sendTextMessage(user.getTelegramId(),
                    "🔑 Код для сброса пароля Aura VPN: <b>" + code + "</b>\n"
                            + "Действует 10 минут. Если это были не вы — просто ничего не делайте.",
                    null);
            sent = true;
        }
        if (mailService.send(user.getEmail(), "Aura VPN: код для сброса пароля",
                "Код для сброса пароля: " + code + "\n\nДействует 10 минут. Если это были не вы, ничего не делайте.")) {
            sent = true;
        }
        if (!sent) {
            log.info("Password reset for user {} requested, but it has no Telegram and mail is not configured", user.getId());
        }
    }

    @Transactional
    public AuthResponse confirmPasswordReset(String email, String code, String newPassword) {
        User user = email == null ? null : userRepository.findByEmail(email.toLowerCase().trim()).orElse(null);
        if (user == null || !codes.consumeResetCode(user.getId(), code)) {
            throw new IllegalArgumentException("The code is wrong or has expired. Request a new one.");
        }
        requireActive(user);
        user.setPasswordHash(passwordEncoder.encode(validPassword(newPassword)));
        return token(userRepository.save(user));
    }

    /**
     * Changes the password, or sets email + password on an account that has
     * none (made in Telegram, with Google, or a device trial), so it can sign
     * in anywhere. Changing an existing password needs the current one.
     */
    @Transactional
    public AuthResponse setCredentials(Long userId, String email, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        requireActive(user);
        if (user.getPasswordHash() != null
                && (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash()))) {
            throw new IllegalArgumentException("Current password is wrong");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("Email cannot be blank");
            }
            String normalized = email.toLowerCase().trim();
            if (normalized.length() > AuthService.MAX_EMAIL_LENGTH || !normalized.contains("@")) {
                throw new IllegalArgumentException("Enter a valid email");
            }
            if (userRepository.existsByEmail(normalized)) {
                throw new IllegalArgumentException("Email already registered");
            }
            user.setEmail(normalized);
        }
        user.setPasswordHash(passwordEncoder.encode(validPassword(newPassword)));
        return token(userRepository.save(user));
    }

    /**
     * Deletes the account as far as the user is concerned (Google Play
     * requires this for apps with sign-up): every way in is removed, devices
     * are revoked, plans stop and don't renew, the subscription URL dies.
     * The row itself stays, anonymised, because the ledger (balance entries,
     * invoices, referral payouts) points at it; the remaining balance is
     * forfeited, which the UI says before asking.
     */
    @Transactional
    public void deleteAccount(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        List<Device> devices = new ArrayList<>(deviceManagementService.getUserDevices(userId));
        for (Device device : devices) {
            deviceManagementService.deleteDevice(userId, device.getId());
        }
        for (String status : List.of("ACTIVE", "EXHAUSTED")) {
            for (Subscription sub : subscriptionRepository.findByUserIdAndStatus(userId, status)) {
                sub.setStatus("CANCELLED");
                sub.setAutoRenew(false);
                sub.setNextTariff(null);
                subscriptionRepository.save(sub);
            }
        }
        user.setEmail(null);
        user.setPasswordHash(null);
        user.setTelegramId(null);
        user.setGoogleSub(null);
        user.setDeviceUuid(null);
        user.setSubscriptionToken(UUID.randomUUID());
        user.setStatus("DELETED");
        userRepository.save(user);
        log.info("User {} deleted their account", userId);
    }

    private static String validPassword(String password) {
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (password.length() > AuthService.MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password is too long (max " + AuthService.MAX_PASSWORD_LENGTH + " characters)");
        }
        return password;
    }

    private static void requireActive(User user) {
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }
    }

    private AuthResponse token(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }
}
