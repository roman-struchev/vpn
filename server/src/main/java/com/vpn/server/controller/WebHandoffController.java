package com.vpn.server.controller;

import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.dto.WebHandoffExchangeRequest;
import com.vpn.server.entity.User;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.WebHandoffService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client -> web SSO handoff endpoints (see WEB_HANDOFF_RESEARCH.md). Kept
 * separate from AuthController because these two endpoints have opposite
 * auth requirements (mint needs an existing JWT, exchange must not require
 * one) rather than mixing that into AuthController's otherwise all-pre-auth
 * @RequestMapping.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class WebHandoffController {

    private final WebHandoffService webHandoffService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    /** Public origin of the web dashboard, so clients never need their own copy of this config. */
    @Value("${vpn.public.web-base-url:https://vpn.struchev.site}")
    private String publicWebBaseUrl = "https://vpn.struchev.site";

    @Value("${vpn.web-handoff.ttl-seconds:60}")
    private int ttlSeconds;

    public WebHandoffController(
            WebHandoffService webHandoffService,
            UserRepository userRepository,
            JwtUtil jwtUtil
    ) {
        this.webHandoffService = webHandoffService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Authenticated: mints a short-lived, single-use exchange code the
     * calling client can hand to a browser (as a URL param, never the JWT
     * itself) via `<webUrl>?handoff_code=<code>&next=<path>`.
     */
    @PostMapping("/web-handoff")
    public ResponseEntity<?> issueHandoff(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        try {
            String code = webHandoffService.issueCode(userId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", code);
            response.put("webUrl", publicWebBaseUrl);
            response.put("expiresInSeconds", ttlSeconds);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Unauthenticated: the code itself is the credential (like a password
     * reset token). Redeems it exactly once and returns a normal,
     * full-privilege session JWT — the same AuthResponse shape every other
     * /api/v1/auth/* endpoint already returns, so the web client needs no
     * new response parsing.
     */
    @PostMapping("/web-handoff/exchange")
    public ResponseEntity<?> exchangeHandoff(@RequestBody WebHandoffExchangeRequest request) {
        try {
            String code = request.code();
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code is required");
            }
            Long userId = webHandoffService.redeem(code);
            if (userId == null) {
                throw new IllegalArgumentException("Invalid or expired code");
            }
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("User not found"));

            String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
            return ResponseEntity.ok(new AuthResponse(
                    token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
