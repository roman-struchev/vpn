package com.vpn.server.controller;

import com.vpn.server.service.DiagnosticsService;
import com.vpn.server.service.DynamicRoutingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/client")
public class ClientController {

    private final DynamicRoutingService dynamicRoutingService;
    private final DiagnosticsService diagnosticsService;

    public ClientController(DynamicRoutingService dynamicRoutingService,
                            DiagnosticsService diagnosticsService) {
        this.dynamicRoutingService = dynamicRoutingService;
        this.diagnosticsService = diagnosticsService;
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

    /**
     * Where client apps ship the failures they hit (Android's
     * DiagnosticsReporter, desktop's diagnostics module); node agents report
     * the same shape over their gRPC stream instead, since they already hold
     * one. Unauthenticated like the rest of /api/v1/client/**: an app that
     * cannot get a token — which is itself one of the failures worth hearing
     * about — must still be able to say so. DiagnosticsService is what keeps
     * that safe: every field is clipped, a request is capped at
     * MAX_EVENTS_PER_REPORT, and a per-source-per-minute valve bounds the
     * whole channel regardless of who calls it.
     *
     * Always answers 200 with how many reports were kept. A reporter is
     * already in a failure path, and must never end up retrying or logging
     * about its own error reporting.
     */
    @PostMapping("/diagnostics")
    public ResponseEntity<?> submitDiagnostics(@RequestBody Map<String, Object> req, Authentication auth) {
        Long userId = (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
        String appVersion = asString(req.get("appVersion"));
        String source = asString(req.get("source"));
        String reporterId = asString(req.get("reporterId"));

        Object rawEvents = req.get("events");
        List<DiagnosticsService.Report> reports = new ArrayList<>();
        if (rawEvents instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> event) {
                    reports.add(toReport(event, source, appVersion, reporterId, userId));
                }
            }
        }

        int accepted = diagnosticsService.record(reports);
        return ResponseEntity.ok(Map.of("status", "RECEIVED", "accepted", accepted));
    }

    private static DiagnosticsService.Report toReport(
            Map<?, ?> event, String source, String appVersion, String reporterId, Long userId) {
        Map<String, String> context = new java.util.LinkedHashMap<>();
        if (event.get("context") instanceof Map<?, ?> rawContext) {
            rawContext.forEach((k, v) -> {
                if (k != null && v != null) {
                    context.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        return new DiagnosticsService.Report(
                asString(event.get("source")) != null ? asString(event.get("source")) : source,
                asString(event.get("severity")),
                asString(event.get("component")),
                asString(event.get("code")),
                asString(event.get("message")),
                asString(event.get("detail")),
                context,
                asString(event.get("appVersion")) != null ? asString(event.get("appVersion")) : appVersion,
                asString(event.get("reporterId")) != null ? asString(event.get("reporterId")) : reporterId,
                userId,
                event.get("nodeId") != null ? parseLongOrNull(String.valueOf(event.get("nodeId"))) : null
        );
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
