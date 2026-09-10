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

    public SubscriptionController(
            SubscriptionExportService exportService,
            UserRepository userRepository,
            AntiEnumerationService antiEnumerationService) {
        this.exportService = exportService;
        this.userRepository = userRepository;
        this.antiEnumerationService = antiEnumerationService;
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

        antiEnumerationService.recordAccessAndEnforce(user.getId(), clientIp(request));

        try {
            String encodedLinks = exportService.exportVlessSubscription(user.getId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"subscription.txt\"")
                    .header("Subscription-Userinfo", "upload=0; download=0; total=107374182400; expire=0")
                    .body(encodedLinks);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        // NOTE: if this server sits behind a reverse proxy/load balancer in
        // production, wire ForwardedHeaderFilter (or read X-Forwarded-For
        // here explicitly) so this reflects the real client IP rather than
        // the proxy's — left as-is for the MVP single-instance deployment.
        return request.getRemoteAddr();
    }
}
