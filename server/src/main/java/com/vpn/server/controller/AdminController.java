package com.vpn.server.controller;

import com.vpn.server.entity.CryptoInvoice;
import com.vpn.server.entity.Node;
import com.vpn.server.entity.NodeBootstrapToken;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.grpc.agent.v1.CommandType;
import com.vpn.server.grpc.agent.v1.ServerCommand;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.service.BlockchainPaymentService;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.task.QuotaEnforcementTask;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final NodeManagementService nodeManagementService;
    private final NodeRepository nodeRepository;
    private final AgentStreamServiceImpl agentStreamService;
    private final QuotaEnforcementTask quotaEnforcementTask;
    private final BlockchainPaymentService blockchainPaymentService;

    public AdminController(
            NodeManagementService nodeManagementService,
            NodeRepository nodeRepository,
            AgentStreamServiceImpl agentStreamService,
            QuotaEnforcementTask quotaEnforcementTask,
            BlockchainPaymentService blockchainPaymentService
    ) {
        this.nodeManagementService = nodeManagementService;
        this.nodeRepository = nodeRepository;
        this.agentStreamService = agentStreamService;
        this.quotaEnforcementTask = quotaEnforcementTask;
        this.blockchainPaymentService = blockchainPaymentService;
    }

    @PostMapping("/nodes/bootstrap-token")
    public ResponseEntity<?> createBootstrapToken(
            @RequestParam(defaultValue = "paid") String pool,
            @RequestParam(defaultValue = "direct") String type,
            @RequestParam(defaultValue = "24") int validHours) {
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

    @PostMapping("/nodes/{nodeId}/sync")
    public ResponseEntity<?> forceConfigSync(@PathVariable Long nodeId) {
        agentStreamService.pushConfigSync(nodeId);
        return ResponseEntity.ok(Map.of("message", "ConfigSync pushed to node " + nodeId));
    }

    @PostMapping("/nodes/{nodeId}/command")
    public ResponseEntity<?> sendNodeCommand(
            @PathVariable Long nodeId,
            @RequestParam(defaultValue = "COMMAND_TYPE_RESTART_XRAY") String type) {
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
