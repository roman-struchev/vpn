package com.vpn.server.controller;

import com.vpn.server.dto.AuthResponse;
import com.vpn.server.dto.DeviceAuthRequest;
import com.vpn.server.dto.GoogleAuthRequest;
import com.vpn.server.dto.LoginRequest;
import com.vpn.server.dto.RegisterRequest;
import com.vpn.server.dto.TelegramAuthRequest;
import com.vpn.server.dto.UpgradeRequest;
import com.vpn.server.service.AuthService;
import com.vpn.server.service.DeviceAuthService;
import com.vpn.server.service.GoogleAuthService;
import com.vpn.server.service.GuestMergeService;
import com.vpn.server.service.TelegramAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final TelegramAuthService telegramAuthService;
    private final GoogleAuthService googleAuthService;
    private final DeviceAuthService deviceAuthService;
    private final GuestMergeService guestMergeService;

    public AuthController(
            AuthService authService,
            TelegramAuthService telegramAuthService,
            GoogleAuthService googleAuthService,
            DeviceAuthService deviceAuthService,
            GuestMergeService guestMergeService
    ) {
        this.authService = authService;
        this.telegramAuthService = telegramAuthService;
        this.googleAuthService = googleAuthService;
        this.deviceAuthService = deviceAuthService;
        this.guestMergeService = guestMergeService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        mergeGuestIfPresent(request.deviceUuid(), response.userId());
        return ResponseEntity.ok(response);
    }

    /**
     * Upgrades the caller's own guest/device-trial account (see
     * DeviceAuthService) into a real, credentialed one in place — the
     * register-time counterpart to /login's merge above. Carved out of the
     * /api/v1/auth/** permitAll block in SecurityConfig: unlike every other
     * endpoint here, this one needs the caller to already be signed in (as
     * the guest being upgraded).
     */
    @PostMapping("/upgrade")
    public ResponseEntity<AuthResponse> upgrade(Authentication auth, @RequestBody UpgradeRequest request) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(authService.upgradeGuest(userId, request));
    }

    /**
     * Best-effort: a merge failure must never turn an otherwise-successful
     * login into a client-visible error. `deviceUuid` is a client-supplied
     * hint (only web omits it today), not something to trust blindly, but
     * GuestMergeService itself only ever acts on a genuine, credential-less
     * guest row, so a spoofed/stale value can at worst no-op.
     */
    private void mergeGuestIfPresent(String deviceUuid, Long targetUserId) {
        if (deviceUuid == null || deviceUuid.isBlank()) return;
        try {
            guestMergeService.mergeGuestIntoTarget(deviceUuid.trim(), targetUserId);
        } catch (Exception e) {
            log.warn("Guest merge failed for deviceUuid={} targetUserId={}", deviceUuid, targetUserId, e);
        }
    }

    @PostMapping("/telegram")
    public ResponseEntity<AuthResponse> telegramAuth(@RequestBody TelegramAuthRequest request) {
        return ResponseEntity.ok(telegramAuthService.authenticateTelegram(request.initData(), request.referralCode()));
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleAuth(@RequestBody GoogleAuthRequest request) {
        return ResponseEntity.ok(googleAuthService.authenticateGoogle(request.idToken(), request.referralCode()));
    }

    /**
     * No-signup trial entry point for the desktop client: finds-or-creates a
     * User keyed by a locally-generated device UUID and returns a JWT, so a
     * fresh install can start using the trial tariff immediately. Idempotent
     * per deviceUuid (see DeviceAuthService).
     */
    @PostMapping("/device")
    public ResponseEntity<AuthResponse> deviceAuth(@RequestBody DeviceAuthRequest request) {
        return ResponseEntity.ok(deviceAuthService.authenticateDevice(request.deviceUuid(), request.referralCode()));
    }
}
