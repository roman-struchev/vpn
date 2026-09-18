package com.vpn.server.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who owns a signaling session id.
 *
 * Session ids are minted by the connecting client, and the broker routes
 * purely by them, so without this anyone could poll
 * {@code GET /p2p/sessions/{id}/signals} with somebody else's id and collect
 * that negotiation's SDP and candidates — or inject signals into it. The id is
 * a UUID, so this is not the difference between easy and impossible, but "hard
 * to guess" is not an access rule; ownership is.
 *
 * In memory and per instance, like the broker's own mailboxes: a session lives
 * for the seconds it takes to negotiate one connection, and a claim that is
 * lost to a restart costs the client one retry rather than anything durable.
 */
@Service
public class P2pSessionRegistry {

    /** Long enough for a slow negotiation over a bad link, short enough to forget quickly. */
    static final long SESSION_TTL_MS = 300_000;
    /** Ceiling on tracked sessions, so claiming ids cannot grow memory without bound. */
    static final int MAX_SESSIONS = 5000;

    private record Owner(Long userId, long claimedAt) {}

    private final Map<String, Owner> owners = new ConcurrentHashMap<>();

    /**
     * Binds this session to a user, or confirms it is already theirs.
     *
     * @return false when the session belongs to somebody else.
     */
    public boolean claim(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return false;
        }
        evictExpired();
        Owner existing = owners.compute(sessionId, (id, current) ->
                current == null || isExpired(current) ? new Owner(userId, System.currentTimeMillis()) : current);
        return userId.equals(existing.userId());
    }

    public boolean isOwnedBy(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return false;
        }
        Owner owner = owners.get(sessionId);
        if (owner == null || isExpired(owner)) {
            return false;
        }
        return userId.equals(owner.userId());
    }

    public void release(String sessionId) {
        if (sessionId != null) {
            owners.remove(sessionId);
        }
    }

    private static boolean isExpired(Owner owner) {
        return System.currentTimeMillis() - owner.claimedAt() > SESSION_TTL_MS;
    }

    private void evictExpired() {
        owners.entrySet().removeIf(e -> isExpired(e.getValue()));
        if (owners.size() >= MAX_SESSIONS) {
            owners.entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(e -> e.getValue().claimedAt()))
                    .limit(Math.max(1, owners.size() - MAX_SESSIONS + 1))
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(owners::remove);
        }
    }
}
