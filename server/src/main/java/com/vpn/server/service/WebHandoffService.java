package com.vpn.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client -> web SSO handoff (see WEB_HANDOFF_RESEARCH.md). An already
 * authenticated client (desktop/Android/etc.) mints a short-lived, single-use
 * opaque code here and hands it to the browser as a URL param; the web app
 * redeems it exactly once for a normal session JWT, so the user never has to
 * log in again and the client's real (30-day) JWT never travels through a URL.
 *
 * In-memory and single-instance is a deliberate choice, not an oversight: the
 * exchange window is ~60 seconds, so "the server restarted mid-window and the
 * code is gone" is an acceptable failure mode (the user just retries) and far
 * cheaper than a DB round-trip for a value this short-lived and throwaway.
 * SCALING CAVEAT: if this server ever runs multiple replicas behind a load
 * balancer without sticky sessions, this map needs to move to a shared store
 * (Redis, or a short-TTL DB table) since a code minted on one instance
 * wouldn't be visible for redemption on another. See WEB_HANDOFF_RESEARCH.md
 * §3.2.
 */
@Service
public class WebHandoffService {

    private record PendingHandoff(Long userId, Instant expiresAt) {}

    private final Map<String, PendingHandoff> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Value("${vpn.web-handoff.ttl-seconds:60}")
    private int ttlSeconds;

    /**
     * Mints a new code for userId. Basic per-user abuse guard (§4.5): refuses
     * to mint a second code while an earlier one for the same user is still
     * outstanding, so a buggy client retry-loop can't flood this map. No
     * dedicated rate-limiting infrastructure exists elsewhere in this
     * codebase to reuse, and this flow doesn't warrant adding one.
     */
    public String issueCode(Long userId) {
        purgeExpired();

        boolean hasActivePendingCode = pending.values().stream()
                .anyMatch(h -> h.userId().equals(userId));
        if (hasActivePendingCode) {
            throw new IllegalStateException("A handoff code was already issued for this account; wait for it to expire or complete it before requesting another");
        }

        String code = generateCode();
        pending.put(code, new PendingHandoff(userId, Instant.now().plusSeconds(ttlSeconds)));
        return code;
    }

    /**
     * Single-use: {@code Map.remove} atomically hands the entry to exactly
     * one caller, so two concurrent redemption attempts for the same code
     * (e.g. a flaky network firing the exchange call twice) can't both
     * succeed — no separate "used" flag/check-then-act race is needed.
     *
     * @return the userId the code was minted for, or {@code null} if the
     *         code is unknown, already redeemed, or expired.
     */
    public Long redeem(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        PendingHandoff handoff = pending.remove(code);
        if (handoff == null || Instant.now().isAfter(handoff.expiresAt())) {
            return null;
        }
        return handoff.userId();
    }

    /** Cheap opportunistic sweep so never-redeemed codes don't linger forever. */
    private void purgeExpired() {
        Instant now = Instant.now();
        pending.values().removeIf(h -> now.isAfter(h.expiresAt()));
    }

    private String generateCode() {
        byte[] bytes = new byte[24]; // 192 bits
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
