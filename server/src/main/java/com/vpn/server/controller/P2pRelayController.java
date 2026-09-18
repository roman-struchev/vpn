package com.vpn.server.controller;

import com.vpn.server.entity.NodeBootstrapToken;
import com.vpn.server.entity.User;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.P2pRelayCreditRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.service.P2pRelayAccountingService;
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

    public P2pRelayController(
            UserRepository userRepository,
            NodeManagementService nodeManagementService,
            AgentStreamServiceImpl agentStreamService,
            P2pRelayAccountingService p2pRelayAccountingService,
            P2pRelayCreditRepository creditRepository
    ) {
        this.userRepository = userRepository;
        this.nodeManagementService = nodeManagementService;
        this.agentStreamService = agentStreamService;
        this.p2pRelayAccountingService = p2pRelayAccountingService;
        this.creditRepository = creditRepository;
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
     * Forwards this connecting client's SDP offer / ICE candidate to a p2p
     * relay node and waits (bounded) for that node's reply — pure opaque
     * passthrough (docs §8.1). `payloadBase64` in, `payloadBase64` out (or
     * 504 on timeout / node not connected). No client currently calls this
     * (phase 2/3) — the plumbing is what this phase delivers.
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
        byte[] payload = Base64.getDecoder().decode(payloadBase64);
        byte[] reply = agentStreamService.sendSignalToNodeAndAwaitReply(nodeId, sessionId, payload);
        if (reply == null) {
            return ResponseEntity.status(504).body(Map.of("error", "No reply from the relay node — it may be offline or unreachable right now"));
        }
        return ResponseEntity.ok(Map.of("payloadBase64", Base64.getEncoder().encodeToString(reply)));
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
