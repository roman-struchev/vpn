package com.vpn.server.controller;

import com.vpn.server.dto.AuthResponse;
import com.vpn.server.service.AccountService;
import com.vpn.server.service.GuestMergeService;
import com.vpn.server.service.OneTimeCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Getting into an account from anywhere and leaving it — see AccountService.
 * The /api/v1/auth/* routes here are public (SecurityConfig), the
 * /api/v1/user/* ones need the caller's JWT.
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;
    private final OneTimeCodeService codes;
    private final GuestMergeService guestMergeService;

    public AccountController(AccountService accountService, OneTimeCodeService codes, GuestMergeService guestMergeService) {
        this.accountService = accountService;
        this.codes = codes;
        this.guestMergeService = guestMergeService;
    }

    public record CodeLoginRequest(String code, String deviceUuid) {}
    public record ResetRequest(String email) {}
    public record ResetConfirmRequest(String email, String code, String newPassword) {}
    public record CredentialsRequest(String email, String currentPassword, String newPassword) {}
    public record DeleteRequest(Boolean confirm) {}

    /** Signs an app in with a code from the Telegram bot (/login) or the web dashboard. */
    @PostMapping("/auth/code")
    public ResponseEntity<AuthResponse> loginWithCode(@RequestBody CodeLoginRequest request) {
        AuthResponse response = accountService.loginWithCode(request.code());
        // Same as /auth/login: the trial account this app was using folds into the real one.
        if (request.deviceUuid() != null && !request.deviceUuid().isBlank()) {
            try {
                guestMergeService.mergeGuestIntoTarget(request.deviceUuid().trim(), response.userId());
            } catch (Exception e) {
                log.warn("Guest merge after code login failed for user {}", response.userId(), e);
            }
        }
        return ResponseEntity.ok(response);
    }

    /** Always the same answer, whether or not the address has an account. */
    @PostMapping("/auth/password-reset/request")
    public ResponseEntity<?> requestPasswordReset(@RequestBody ResetRequest request) {
        accountService.requestPasswordReset(request.email());
        return ResponseEntity.ok(Map.of("status", "SENT_IF_EXISTS"));
    }

    @PostMapping("/auth/password-reset/confirm")
    public ResponseEntity<AuthResponse> confirmPasswordReset(@RequestBody ResetConfirmRequest request) {
        return ResponseEntity.ok(accountService.confirmPasswordReset(request.email(), request.code(), request.newPassword()));
    }

    /** A code to sign in to the apps as this account, shown in the web dashboard. */
    @PostMapping("/user/app-login-code")
    public ResponseEntity<?> appLoginCode(Authentication auth) {
        String code = codes.createLoginCode((Long) auth.getPrincipal());
        return ResponseEntity.ok(Map.of("code", code, "expiresInSeconds", 600));
    }

    /** Change the password, or set email + password on an account made in Telegram/Google. */
    @PostMapping("/user/credentials")
    public ResponseEntity<AuthResponse> setCredentials(Authentication auth, @RequestBody CredentialsRequest request) {
        return ResponseEntity.ok(accountService.setCredentials(
                (Long) auth.getPrincipal(), request.email(), request.currentPassword(), request.newPassword()));
    }

    @DeleteMapping("/user/account")
    public ResponseEntity<?> deleteAccount(Authentication auth, @RequestBody(required = false) DeleteRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            throw new IllegalArgumentException("Confirm the deletion");
        }
        accountService.deleteAccount((Long) auth.getPrincipal());
        return ResponseEntity.ok(Map.of("status", "DELETED"));
    }
}
