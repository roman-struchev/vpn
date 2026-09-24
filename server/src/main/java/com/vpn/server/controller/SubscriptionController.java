package com.vpn.server.controller;

import com.vpn.server.entity.User;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.AntiEnumerationService;
import com.vpn.server.service.SubscriptionExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/subscription")
public class SubscriptionController {

    private final SubscriptionExportService exportService;
    private final UserRepository userRepository;
    private final AntiEnumerationService antiEnumerationService;
    private final com.vpn.server.service.CurrentPlanService currentPlanService;

    public SubscriptionController(
            SubscriptionExportService exportService,
            UserRepository userRepository,
            AntiEnumerationService antiEnumerationService,
            com.vpn.server.service.CurrentPlanService currentPlanService) {
        this.exportService = exportService;
        this.userRepository = userRepository;
        this.antiEnumerationService = antiEnumerationService;
        this.currentPlanService = currentPlanService;
    }

    /**
     * Intentionally unauthenticated — this is the URL pasted into third-party
     * clients (v2rayTun, Hiddify, v2rayNG, ...) that only support a static
     * subscription link, not a login flow. `token` is an opaque per-user
     * UUID (users.subscription_token), never the raw sequential user id —
     * see V2__anti_enumeration.sql for why that distinction matters.
     */
    @GetMapping(value = "/export/{token}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportSubscription(@PathVariable UUID token, HttpServletRequest request) {
        User user = userRepository.findBySubscriptionToken(token).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body("Account suspended");
        }

        antiEnumerationService.recordAccessAndEnforce(user.getId(), clientIp(request));

        try {
            String encodedLinks = exportService.exportVlessSubscription(user.getId());
            // Read by v2rayTun/Hiddify/Happ to show the plan's traffic and
            // end date and a profile name. The traffic
            // used to be a hardcoded "100 GB, never expires" for everyone.
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"subscription.txt\"")
                    .header("Subscription-Userinfo", userInfo(currentPlanService.currentPlan(user.getId()).orElse(null)))
                    .header("Profile-Title", "base64:" + java.util.Base64.getEncoder()
                            .encodeToString("Aura VPN".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Profile-Update-Interval", "6")
                    .header("Profile-Web-Page-Url", currentPlanService.webBaseUrl())
                    .body(encodedLinks);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    static String userInfo(com.vpn.server.entity.Subscription sub) {
        if (sub == null) {
            return "upload=0; download=0; total=0; expire=0";
        }
        long expire = sub.getOverrideTariff() == null && sub.hasNoExpiry()
                ? 0
                : sub.getEffectiveExpiresAt().getEpochSecond();
        return "upload=0; download=" + sub.getTrafficUsedBytes()
                + "; total=" + sub.getTrafficLimitBytes()
                + "; expire=" + expire;
    }

    private String clientIp(HttpServletRequest request) {
        // NOTE: if this server sits behind a reverse proxy/load balancer in
        // production, wire ForwardedHeaderFilter (or read X-Forwarded-For
        // here explicitly) so this reflects the real client IP rather than
        // the proxy's — left as-is for the MVP single-instance deployment.
        return request.getRemoteAddr();
    }
}
