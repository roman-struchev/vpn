package com.vpn.server.controller;

import com.vpn.server.entity.Node;
import com.vpn.server.entity.NodeBootstrapToken;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.service.NodeManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final NodeManagementService nodeManagementService;
    private final NodeRepository nodeRepository;

    public AdminController(NodeManagementService nodeManagementService, NodeRepository nodeRepository) {
        this.nodeManagementService = nodeManagementService;
        this.nodeRepository = nodeRepository;
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
}
