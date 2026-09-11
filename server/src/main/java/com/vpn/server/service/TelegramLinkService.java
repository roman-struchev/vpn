package com.vpn.server.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, in-memory pending links between a web account and a Telegram
 * chat, used to attach Telegram Stars top-ups to an existing email/Google
 * account (see UserController#createTelegramLink and
 * TelegramBotService#handleAccountLinkStart).
 *
 * Deliberately not persisted: a code is only useful for the few minutes it
 * takes a user to tap the deep link and land in the Telegram chat, so a
 * lost entry on server restart just means the (rare) in-flight link attempt
 * has to be retried from the dashboard -- not worth a DB table/migration for.
 */
@Service
public class TelegramLinkService {

    private static final long TTL_MINUTES = 10;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int CODE_LENGTH = 10;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();

    private record PendingLink(Long userId, Instant expiresAt) {
        boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }
    }

    /** Creates a fresh code for {@code userId} and returns it. */
    public String createPendingLink(Long userId) {
        cleanupExpired();
        String code = generateCode();
        pendingLinks.put(code, new PendingLink(userId, Instant.now().plusSeconds(TTL_MINUTES * 60)));
        return code;
    }

    /**
     * Looks up and immediately invalidates {@code code} (single use). Returns
     * empty if the code is unknown or expired.
     */
    public Optional<Long> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        PendingLink link = pendingLinks.remove(code);
        if (link == null || link.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(link.userId());
    }

    private void cleanupExpired() {
        pendingLinks.values().removeIf(PendingLink::isExpired);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
