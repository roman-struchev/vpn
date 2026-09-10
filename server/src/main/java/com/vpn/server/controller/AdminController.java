package com.vpn.server.controller;

import com.vpn.server.entity.*;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.grpc.agent.v1.CommandType;
import com.vpn.server.grpc.agent.v1.ServerCommand;
import com.vpn.server.repository.*;
import com.vpn.server.service.BlockchainPaymentService;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.task.QuotaEnforcementTask;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final NodeManagementService nodeManagementService;
    private final NodeRepository nodeRepository;
    private final AgentStreamServiceImpl agentStreamService;
    private final QuotaEnforcementTask quotaEnforcementTask;
    private final BlockchainPaymentService blockchainPaymentService;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BalanceEntryRepository balanceEntryRepository;
    private final TransportPolicyRepository transportPolicyRepository;
    private final ConnTelemetryRepository connTelemetryRepository;
    private final DeviceRepository deviceRepository;

    public AdminController(
            NodeManagementService nodeManagementService,
            NodeRepository nodeRepository,
            AgentStreamServiceImpl agentStreamService,
            QuotaEnforcementTask quotaEnforcementTask,
            BlockchainPaymentService blockchainPaymentService,
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            BalanceEntryRepository balanceEntryRepository,
            TransportPolicyRepository transportPolicyRepository,
            ConnTelemetryRepository connTelemetryRepository,
            DeviceRepository deviceRepository
    ) {
        this.nodeManagementService = nodeManagementService;
        this.nodeRepository = nodeRepository;
        this.agentStreamService = agentStreamService;
        this.quotaEnforcementTask = quotaEnforcementTask;
        this.blockchainPaymentService = blockchainPaymentService;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.balanceEntryRepository = balanceEntryRepository;
        this.transportPolicyRepository = transportPolicyRepository;
        this.connTelemetryRepository = connTelemetryRepository;
        this.deviceRepository = deviceRepository;
    }

    // ==========================================
    // 1. DASHBOARD & OPERATIONAL METRICS
    // ==========================================

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboardMetrics() {
        long totalUsers = userRepository.count();
        long activeSubscriptions = subscriptionRepository.countByStatus("ACTIVE");
        long totalBalanceMicro = userRepository.sumBalanceUsdtMicro();
        long totalTrafficUsed = subscriptionRepository.sumTrafficUsedBytes();
        long onlineNodes = nodeRepository.countByStatus("ONLINE");
        long totalNodes = nodeRepository.count();

        Instant past24Hours = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Object[]> rawAgg = connTelemetryRepository.aggregateByOperatorAndRegion(past24Hours);
        List<Map<String, Object>> telemetryStats = new ArrayList<>();
        for (Object[] row : rawAgg) {
            long totalReports = (Long) row[3];
            long successCount = (Long) row[4];
            long failureReportCount = (Long) row[5];
            Double avgConnectTimeMs = (Double) row[7];

            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("operator", row[0] != null ? row[0] : "Unknown");
            stat.put("region", row[1] != null ? row[1] : "Unknown");
            stat.put("transport", row[2] != null ? row[2] : "Unknown");
            stat.put("totalReports", totalReports);
            stat.put("successCount", successCount);
            stat.put("failureReportCount", failureReportCount);
            // Real denominator now that success is reported too, not just failure —
            // previously this bucket only ever showed an absolute failure count.
            stat.put("failureRatePercent", totalReports > 0 ? (failureReportCount * 100.0 / totalReports) : 0.0);
            stat.put("avgConnectTimeMs", avgConnectTimeMs != null ? Math.round(avgConnectTimeMs) : null);
            stat.put("whitelistSuspected", row[6]);
            telemetryStats.add(stat);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("totalUsers", totalUsers);
        response.put("activeSubscriptions", activeSubscriptions);
        response.put("totalBalanceUsdt", totalBalanceMicro / 1_000_000.0);
        response.put("totalTrafficUsedBytes", totalTrafficUsed);
        response.put("onlineNodes", onlineNodes);
        response.put("totalNodes", totalNodes);
        response.put("telemetryDegradation", telemetryStats);

        return ResponseEntity.ok(response);
    }

    // ==========================================
    // 2. USER MANAGEMENT
    // ==========================================

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<User> users = userRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (User u : users) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("email", u.getEmail());
            map.put("telegramId", u.getTelegramId());
            map.put("role", u.getRole());
            map.put("status", u.getStatus());
            map.put("balanceUsdtMicro", u.getBalanceUsdtMicro());
            map.put("referralCode", u.getReferralCode());
            map.put("referredByUserId", u.getReferredBy() != null ? u.getReferredBy().getId() : null);
            map.put("createdAt", u.getCreatedAt());
            map.put("deviceCount", deviceRepository.countByUserIdAndIsActiveTrue(u.getId()));
            map.put("referralCount", userRepository.countByReferredBy_Id(u.getId()));

            subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(u.getId(), "ACTIVE")
                    .ifPresent(s -> map.put("activeSubscription", Map.of(
                            "id", s.getId(),
                            "tariffId", s.getTariff().getId(),
                            "currentPeriodEnd", s.getCurrentPeriodEnd(),
                            "trafficUsedBytes", s.getTrafficUsedBytes(),
                            "trafficLimitBytes", s.getTrafficLimitBytes()
                    )));

            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/users/{userId}/balance")
    @Transactional
    public ResponseEntity<?> adjustUserBalance(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> req
    ) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        long amountMicro = Long.parseLong(req.get("amountMicro").toString());
        String description = (String) req.getOrDefault("description", "Manual admin adjustment");

        long newBalance = Math.max(0L, user.getBalanceUsdtMicro() + amountMicro);
        user.setBalanceUsdtMicro(newBalance);
        userRepository.save(user);

        BalanceEntry entry = new BalanceEntry();
        entry.setUser(user);
        entry.setAmountUsdtMicro(amountMicro);
        entry.setBalanceAfterMicro(newBalance);
        entry.setType("MANUAL_ADJUSTMENT");
        entry.setDescription(description);
        entry.setReferenceId("admin_adj_" + System.currentTimeMillis());
        balanceEntryRepository.save(entry);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "amountMicro", amountMicro,
                "newBalanceMicro", newBalance
        ));
    }

    @PostMapping("/users/{userId}/status")
    @Transactional
    public ResponseEntity<?> updateUserStatus(
            @PathVariable Long userId,
            @RequestBody Map<String, String> req
    ) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        String newStatus = req.getOrDefault("status", "ACTIVE").toUpperCase();
        user.setStatus(newStatus);
        userRepository.save(user);

        if ("BLOCKED".equals(newStatus)) {
            agentStreamService.pushConfigSyncToAll();
        }

        return ResponseEntity.ok(Map.of("userId", userId, "status", newStatus));
    }

    @PostMapping("/users/{userId}/subscription/extend")
    @Transactional
    public ResponseEntity<?> extendSubscription(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> req
    ) {
        int days = Integer.parseInt(req.getOrDefault("days", 30).toString());
        Optional<Subscription> subOpt = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE");

        if (subOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No active subscription found for user"));
        }

        Subscription sub = subOpt.get();
        Instant newEnd = sub.getCurrentPeriodEnd().isAfter(Instant.now())
                ? sub.getCurrentPeriodEnd().plus(days, ChronoUnit.DAYS)
                : Instant.now().plus(days, ChronoUnit.DAYS);

        sub.setCurrentPeriodEnd(newEnd);
        subscriptionRepository.save(sub);

        return ResponseEntity.ok(Map.of(
                "subscriptionId", sub.getId(),
                "newPeriodEnd", newEnd.toString(),
                "extendedDays", days
        ));
    }

    // ==========================================
    // 3. NODE MANAGEMENT
    // ==========================================

    @PostMapping("/nodes/bootstrap-token")
    public ResponseEntity<?> createBootstrapToken(
            @RequestParam(defaultValue = "paid") String pool,
            @RequestParam(defaultValue = "direct") String type,
            @RequestParam(defaultValue = "24") int validHours
    ) {
        NodeBootstrapToken token = nodeManagementService.createBootstrapToken(pool, type, validHours);
        return ResponseEntity.ok(Map.of(
                "token", token.getToken(),
                "assignedPool", token.getAssignedPool(),
                "assignedType", token.getAssignedType(),
                "expiresAt", token.getExpiresAt().toString()
        ));
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<Node>> listNodes() {
        return ResponseEntity.ok(nodeRepository.findAll());
    }

    @PostMapping("/nodes/{nodeId}/pool")
    @Transactional
    public ResponseEntity<?> updateNodePool(
            @PathVariable Long nodeId,
            @RequestParam String pool
    ) {
        Node node = nodeRepository.findById(nodeId).orElse(null);
        if (node == null) return ResponseEntity.notFound().build();

        node.setPool(pool.toLowerCase());
        nodeRepository.save(node);
        agentStreamService.pushConfigSyncToAll();

        return ResponseEntity.ok(Map.of("nodeId", nodeId, "pool", node.getPool()));
    }

    @PostMapping("/nodes/{nodeId}/status")
    @Transactional
    public ResponseEntity<?> updateNodeStatus(
            @PathVariable Long nodeId,
            @RequestParam String status
    ) {
        Node node = nodeRepository.findById(nodeId).orElse(null);
        if (node == null) return ResponseEntity.notFound().build();

        node.setStatus(status.toUpperCase());
        nodeRepository.save(node);

        return ResponseEntity.ok(Map.of("nodeId", nodeId, "status", node.getStatus()));
    }

    @PostMapping("/nodes/{nodeId}/sync")
    public ResponseEntity<?> forceConfigSync(@PathVariable Long nodeId) {
        agentStreamService.pushConfigSync(nodeId);
        return ResponseEntity.ok(Map.of("message", "ConfigSync pushed to node " + nodeId));
    }

    @PostMapping("/nodes/{nodeId}/command")
    public ResponseEntity<?> sendNodeCommand(
            @PathVariable Long nodeId,
            @RequestParam(defaultValue = "COMMAND_TYPE_RESTART_XRAY") String type
    ) {
        CommandType cmdType;
        try {
            cmdType = CommandType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid command type: " + type));
        }

        ServerCommand cmd = ServerCommand.newBuilder()
                .setCommandType(cmdType)
                .build();

        boolean sent = agentStreamService.sendCommand(nodeId, cmd);
        return ResponseEntity.ok(Map.of("success", sent, "command", type, "nodeId", nodeId));
    }

    // ==========================================
    // 4. TRANSPORT POLICIES
    // ==========================================

    @GetMapping("/policies")
    public ResponseEntity<List<TransportPolicy>> listTransportPolicies() {
        return ResponseEntity.ok(transportPolicyRepository.findAll());
    }

    @PostMapping("/policies")
    @Transactional
    public ResponseEntity<?> saveTransportPolicy(@RequestBody TransportPolicy policy) {
        policy.setUpdatedAt(Instant.now());
        TransportPolicy saved = transportPolicyRepository.save(policy);
        agentStreamService.pushConfigSyncToAll();
        return ResponseEntity.ok(saved);
    }

    // ==========================================
    // 5. OPERATIONAL TASKS & RECONCILIATION
    // ==========================================

    @PostMapping("/tasks/enforce-quotas")
    public ResponseEntity<?> triggerQuotaEnforcement() {
        quotaEnforcementTask.runEnforcement();
        return ResponseEntity.ok(Map.of("message", "Quota enforcement executed successfully"));
    }

    @PostMapping("/crypto/reconcile")
    public ResponseEntity<?> reconcileDeposit(@RequestBody Map<String, Object> payload) {
        String chain = (String) payload.getOrDefault("chain", "TRC20");
        String address = (String) payload.getOrDefault("depositAddress", blockchainPaymentService.getDefaultTronDepositAddress());
        Long amountMicro = Long.valueOf(payload.get("amountMicro").toString());
        String txHash = (String) payload.getOrDefault("txHash", "manual_tx_" + System.currentTimeMillis());

        CryptoInvoice credited = blockchainPaymentService.processIncomingDeposit(chain, address, amountMicro, txHash);
        if (credited != null) {
            return ResponseEntity.ok(Map.of(
                    "status", "MATCHED_AND_CREDITED",
                    "invoiceId", credited.getId(),
                    "userId", credited.getUser().getId(),
                    "expectedAmountMicro", credited.getExpectedAmountUsdtMicro(),
                    "creditedAmountMicro", credited.getActualAmountUsdtMicro(),
                    "txHash", txHash
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "status", "UNMATCHED",
                    "message", "No pending invoice matched tolerance window for amount " + amountMicro
            ));
        }
    }
}
