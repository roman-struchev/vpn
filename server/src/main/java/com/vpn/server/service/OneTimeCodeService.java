package com.vpn.server.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived single-use codes that stand in for a password:
 *
 * - app login: the Telegram bot (/login) or the signed-in web dashboard
 *   hands out a code, typed into the Android/desktop app. The only way into
 *   the apps for an account made in Telegram, which has no email/password.
 * - password reset: sent to the account's Telegram and/or email.
 *
 * In memory, like TelegramLinkService: single-instance server, and a code
 * lost on restart just means asking for a new one.
 */
@Service
public class OneTimeCodeService {

    public enum Purpose { APP_LOGIN, PASSWORD_RESET }

    static final long TTL_SECONDS = 10 * 60;
    /** Wrong guesses a reset code survives before it is burned. */
    static final int MAX_RESET_ATTEMPTS = 5;
    // No 0/O, 1/I/L: read off one screen and typed on another.
    private static final String LOGIN_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> loginCodes = new ConcurrentHashMap<>();
    private final Map<Long, Pending> resetCodes = new ConcurrentHashMap<>();

    private static final class Pending {
        final Long userId;
        final String code;
        final Instant expiresAt;
        int attempts;

        Pending(Long userId, String code, Instant expiresAt) {
            this.userId = userId;
            this.code = code;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }
    }

    /** 8 characters shown as XXXX-XXXX: ~10^12 combinations, so guessing one isn't practical. */
    public String createLoginCode(Long userId) {
        loginCodes.values().removeIf(Pending::isExpired);
        loginCodes.values().removeIf(p -> p.userId.equals(userId));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(LOGIN_CHARS.charAt(random.nextInt(LOGIN_CHARS.length())));
        }
        String code = sb.toString();
        loginCodes.put(code, new Pending(userId, code, Instant.now().plusSeconds(TTL_SECONDS)));
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    public Optional<Long> consumeLoginCode(String typed) {
        if (typed == null) return Optional.empty();
        String code = typed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        Pending p = loginCodes.remove(code);
        return p == null || p.isExpired() ? Optional.empty() : Optional.of(p.userId);
    }

    /** 6 digits: read from a message, few tries allowed per code (see MAX_RESET_ATTEMPTS). */
    public String createResetCode(Long userId) {
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        resetCodes.put(userId, new Pending(userId, code, Instant.now().plusSeconds(TTL_SECONDS)));
        return code;
    }

    public boolean consumeResetCode(Long userId, String typed) {
        Pending p = resetCodes.get(userId);
        if (p == null || p.isExpired()) {
            resetCodes.remove(userId);
            return false;
        }
        String code = typed == null ? "" : typed.replaceAll("\\D", "");
        if (!p.code.equals(code)) {
            if (++p.attempts >= MAX_RESET_ATTEMPTS) {
                resetCodes.remove(userId);
            }
            return false;
        }
        resetCodes.remove(userId);
        return true;
    }
}
