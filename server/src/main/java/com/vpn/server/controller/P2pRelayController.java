package com.vpn.server.controller;

import com.vpn.server.entity.NodeBootstrapToken;
import com.vpn.server.entity.User;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.P2pRelayCreditRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.service.P2pRelayAccountingService;
import com.vpn.server.service.P2pRelayDirectory;
import com.vpn.server.service.P2pSessionRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

/**
 * Authenticated-user-only P2P relay endpoints (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8) — consent, bootstrap-token minting for a
 * user's own relay-mode client, WebRTC signaling relay, and the connecting
 * client's half of the dual traffic self-report. Never available to guest/
 * device-trial accounts (see isGuestAccount) — the doc's §8.6 requirement
 * for real accountability before letting someone's device carry other
 * users' traffic.
 */
@RestController
@RequestMapping("/api/v1/user/p2p")
public class P2pRelayController {

    private final UserRepository userRepository;
    private final NodeManagementService nodeManagementService;
    private final AgentStreamServiceImpl agentStreamService;
    private final P2pRelayAccountingService p2pRelayAccountingService;
    private final P2pRelayCreditRepository creditRepository;
    private final P2pRelayDirectory p2pRelayDirectory;
    private final P2pSessionRegistry p2pSessionRegistry;

    public P2pRelayController(
            UserRepository userRepository,
            NodeManagementService nodeManagementService,
            AgentStreamServiceImpl agentStreamService,
            P2pRelayAccountingService p2pRelayAccountingService,
            P2pRelayCreditRepository creditRepository,
            P2pRelayDirectory p2pRelayDirectory,
            P2pSessionRegistry p2pSessionRegistry
    ) {
        this.userRepository = userRepository;
        this.nodeManagementService = nodeManagementService;
        this.agentStreamService = agentStreamService;
        this.p2pRelayAccountingService = p2pRelayAccountingService;
        this.creditRepository = creditRepository;
        this.p2pRelayDirectory = p2pRelayDirectory;
        this.p2pSessionRegistry = p2pSessionRegistry;
    }

    /** Same signal UserController#getProfile's own isGuest uses — no password/Telegram/Google credential means no real accountability yet. */
    private static boolean isGuestAccount(User user) {
        return user.getPasswordHash() == null && user.getTelegramId() == null && user.getGoogleSub() == null;
    }

    static final String OWN_RELAY_NODE_ERROR =
            "This relay node is your own device — relaying your own traffic through it is not allowed";

    /**
     * Whether {@code nodeId} is the caller's own relay device (see
     * Node#isOwnRelayDeviceOf). Nobody may connect through, or claim relay
     * credit for, their own phone/laptop: the traffic would leave from the
     * same IP it entered (so it circumvents nothing), while the accounting
     * credits a relay's owner — which would turn "relay to yourself" into a
     * free-quota generator. SubscriptionExportService keeps such a node out
     * of the region list the same way, so a well-behaved client never even
     * offers it; this is the enforcement that does not trust the client.
     */
    private boolean ownsRelayNode(Authentication auth, Long nodeId) {
        if (auth == null || nodeId == null) {
            return false;
        }
        return nodeManagementService.isOwnRelayDevice(nodeId, (Long) auth.getPrincipal());
    }

    @PostMapping("/accept-terms")
    public ResponseEntity<?> acceptTerms(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();
        if (isGuestAccount(user)) {
            return ResponseEntity.badRequest().body(Map.of("error", "P2P relay mode requires a real account, not a guest/trial device profile"));
        }
        user.setP2pRelayTermsAcceptedAt(Instant.now());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("acceptedAt", user.getP2pRelayTermsAcceptedAt().toString()));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();
        long creditedToday = creditRepository.sumBytesCreditedSince(userId, Instant.now().minus(1, ChronoUnit.DAYS));
        return ResponseEntity.ok(Map.of(
                "termsAccepted", user.getP2pRelayTermsAcceptedAt() != null,
                "isGuest", isGuestAccount(user),
                "bytesCreditedToday", creditedToday,
                "dailyCapBytes", P2pRelayAccountingService.DAILY_CAP_BYTES,
                "remainingCapBytesToday", Math.max(0, P2pRelayAccountingService.DAILY_CAP_BYTES - creditedToday)
        ));
    }

    /**
     * Mints a fresh bootstrap token for this user's own relay-agent to
     * register with (docs §8.4) — bound to them (NodeBootstrapToken#ownerUser)
     * so relay credit can never be misattributed. Requires terms already
     * accepted; a real, non-guest account (redundant with acceptTerms'
     * check, but this endpoint doesn't assume acceptTerms was called in the
     * same session).
     */
    @PostMapping("/bootstrap-token")
    public ResponseEntity<?> createBootstrapToken(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();
        if (isGuestAccount(user)) {
            return ResponseEntity.badRequest().body(Map.of("error", "P2P relay mode requires a real account, not a guest/trial device profile"));
        }
        if (user.getP2pRelayTermsAcceptedAt() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Accept the P2P relay terms first (POST /accept-terms)"));
        }
        NodeBootstrapToken token = nodeManagementService.createP2pBootstrapTokenForUser(user);
        return ResponseEntity.ok(Map.of(
                "token", token.getToken(),
                "expiresAt", token.getExpiresAt().toString()
        ));
    }

    /**
     * Relay peers this user may currently connect *through* (docs §8.1). A
     * relay is not an exit: it forwards opaque bytes to a VPN node the client
     * names in its offer, so this is the list of paths available when dialing
     * a node directly does not work — which is the whole point of the feature.
     *
     * Excludes the caller's own devices (Node#isOwnRelayDeviceOf: relaying
     * through yourself circumvents nothing and would credit you for your own
     * bytes) and anything outside their tariff's pool.
     */
    @GetMapping("/relays")
    public ResponseEntity<?> availableRelays(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(Map.of("relays", p2pRelayDirectory.availableRelaysFor(userId)));
    }

    /**
     * Forwards this connecting client's SDP offer / ICE candidate to a relay
     * node — pure opaque passthrough (docs §8.1), the server never looks
     * inside. Returns as soon as it has been handed to the node's stream; what
     * the relay says back is collected from {@link #pollSignals} instead,
     * because the two directions do not pair up one-to-one (one offer draws an
     * answer plus a burst of candidates).
     */
    @PostMapping("/nodes/{nodeId}/signal")
    public ResponseEntity<?> sendSignal(
            @PathVariable Long nodeId,
            @RequestBody Map<String, String> req,
            Authentication auth
    ) {
        String sessionId = req.get("sessionId");
        String payloadBase64 = req.get("payloadBase64");
        if (sessionId == null || sessionId.isBlank() || payloadBase64 == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId and payloadBase64 are required"));
        }
        if (ownsRelayNode(auth, nodeId)) {
            return ResponseEntity.status(403).body(Map.of("error", OWN_RELAY_NODE_ERROR));
        }
        if (!p2pSessionRegistry.claim(sessionId, (Long) auth.getPrincipal())) {
            return ResponseEntity.status(403).body(Map.of("error", "This signaling session belongs to another account"));
        }

        byte[] payload = Base64.getDecoder().decode(payloadBase64);
        boolean sent = agentStreamService.sendSignalToNode(nodeId, sessionId, payload);
        if (!sent) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "The relay node is not reachable right now — it may be offline or no longer offering relay"));
        }
        return ResponseEntity.ok(Map.of("status", "SENT"));
    }

    /**
     * Long-polls for the relay's next signal in this session. Returns
     * {@code payloadBase64: null} when nothing arrives within the wait, which
     * is an ordinary outcome: a client polls in a loop while negotiating and
     * stops once its data channel is open.
     */
    @GetMapping("/sessions/{sessionId}/signals")
    public ResponseEntity<?> pollSignals(
            @PathVariable String sessionId,
            @RequestParam(required = false, defaultValue = "10000") long waitMs,
            Authentication auth
    ) {
        if (!p2pSessionRegistry.isOwnedBy(sessionId, (Long) auth.getPrincipal())) {
            // Also the answer for a session nobody started: a caller must not
            // be able to fish for another account's negotiation by guessing ids.
            return ResponseEntity.status(403).body(Map.of("error", "This signaling session belongs to another account"));
        }
        byte[] payload = agentStreamService.awaitSignal(sessionId, Math.min(Math.max(waitMs, 1_000), 30_000));
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("payloadBase64", payload == null ? null : Base64.getEncoder().encodeToString(payload));
        return ResponseEntity.ok(body);
    }

    /** Frees the session's mailbox once a client is done negotiating. */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> closeSession(@PathVariable String sessionId, Authentication auth) {
        if (!p2pSessionRegistry.isOwnedBy(sessionId, (Long) auth.getPrincipal())) {
            return ResponseEntity.status(403).body(Map.of("error", "This signaling session belongs to another account"));
        }
        agentStreamService.closeSignalSession(sessionId);
        p2pSessionRegistry.release(sessionId);
        return ResponseEntity.ok(Map.of("status", "CLOSED"));
    }

    /**
     * The connecting client's half of the dual self-report (docs §8.2) — the
     * relay node's half arrives separately over its own gRPC stream
     * (AgentMessage.p2p_traffic_report -> P2pRelayAccountingService).
     */
    @PostMapping("/sessions/{sessionId}/traffic-report")
    public ResponseEntity<?> reportSessionTraffic(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> req,
            Authentication auth
    ) {
        Object nodeIdObj = req.get("nodeId");
        Object bytesObj = req.get("bytesRelayed");
        if (nodeIdObj == null || bytesObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "nodeId and bytesRelayed are required"));
        }
        long nodeId = Long.parseLong(nodeIdObj.toString());
        // A session with one's own relay device can never be legitimate (see
        // ownsRelayNode) — and this is the half of the dual report that comes
        // from the *client*, so accepting it would let an account pair it with
        // its own relay agent's half and mint credit for itself.
        if (ownsRelayNode(auth, nodeId)) {
            return ResponseEntity.status(403).body(Map.of("error", OWN_RELAY_NODE_ERROR));
        }
        long bytesRelayed = Long.parseLong(bytesObj.toString());
        p2pRelayAccountingService.recordClientReport(nodeId, sessionId, bytesRelayed);
        return ResponseEntity.ok(Map.of("status", "RECEIVED"));
    }
}
