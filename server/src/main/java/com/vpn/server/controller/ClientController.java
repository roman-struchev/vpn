package com.vpn.server.controller;

import com.vpn.server.service.DynamicRoutingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/client")
public class ClientController {

    private final DynamicRoutingService dynamicRoutingService;

    public ClientController(DynamicRoutingService dynamicRoutingService) {
        this.dynamicRoutingService = dynamicRoutingService;
    }

    @GetMapping("/config")
    public ResponseEntity<DynamicRoutingService.RoutingConfigResponse> getRoutingConfig(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String region
    ) {
        return ResponseEntity.ok(dynamicRoutingService.getRoutingConfig(operator, region));
    }

    @PostMapping("/telemetry")
    public ResponseEntity<?> submitTelemetry(@RequestBody Map<String, Object> req) {
        Long nodeId = req.get("nodeId") != null ? Long.valueOf(req.get("nodeId").toString()) : null;
        String operator = (String) req.get("operator");
        String region = (String) req.get("region");
        String transport = (String) req.get("transport");
        int connectTimeMs = req.get("connectTimeMs") != null ? Integer.parseInt(req.get("connectTimeMs").toString()) : 0;
        int failureCount = req.get("failureCount") != null ? Integer.parseInt(req.get("failureCount").toString()) : 0;
        boolean isWhitelist = req.get("isWhitelistSuspected") != null && Boolean.parseBoolean(req.get("isWhitelistSuspected").toString());

        dynamicRoutingService.recordTelemetry(
                nodeId,
                operator,
                region,
                transport,
                connectTimeMs,
                failureCount,
                isWhitelist
        );

        return ResponseEntity.ok(Map.of("status", "RECEIVED"));
    }
}
