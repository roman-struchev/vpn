package com.vpn.server.service;

import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.dto.LoginRequest;
import com.vpn.server.dto.RegisterRequest;
import com.vpn.server.dto.UpgradeRequest;
import com.vpn.server.entity.User;
import com.vpn.server.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;

@Service
public class AuthService {

    /** RFC 5321's own maximum, and one under users.email's VARCHAR(255). */
    static final int MAX_EMAIL_LENGTH = 254;
    /** Generous for a passphrase, bounded for the hashing cost — see register(). */
    static final int MAX_PASSWORD_LENGTH = 200;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("Email cannot be blank");
        }
        // An address longer than its column (users.email VARCHAR(255)) used to
        // reach Postgres as-is and come back as a 500 "Internal server error";
        // 254 is the longest an email address may be anyway (RFC 5321).
        if (request.email().trim().length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("Email is too long (max " + MAX_EMAIL_LENGTH + " characters)");
        }
        if (request.password() == null || request.password().length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        // Not a column-width problem (only the hash is stored) but a work one:
        // hashing cost scales with input length, so an unbounded password is
        // free CPU for whoever asks. bcrypt reads the first 72 bytes anyway.
        if (request.password().length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password is too long (max " + MAX_PASSWORD_LENGTH + " characters)");
        }
        if (userRepository.existsByEmail(request.email().toLowerCase().trim())) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = new User();
        user.setEmail(request.email().toLowerCase().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setBalanceUsdtMicro(0L);
        user.setReferralCode(generateUniqueReferralCode());

        if (request.referralCode() != null && !request.referralCode().isBlank()) {
            userRepository.findByReferralCode(request.referralCode().trim())
                    .ifPresent(user::setReferredBy);
        }

        user = userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase().trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }

    /**
     * Swaps a still-valid token for a fresh one, so a client that is in use
     * never reaches the token's expiry and gets signed out mid-month. Only
     * reachable with a valid token (SecurityConfig), and re-checks the
     * account, so a blocked user cannot keep extending a session.
     */
    @Transactional(readOnly = true)
    public AuthResponse refresh(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }

    /**
     * Converts the currently-signed-in guest/device-trial account (see
     * DeviceAuthService) into a real, credentialed one in place — same row,
     * same id, same balance and active trial subscription, just adding an
     * email+password so it survives logout/reinstall. Deliberately not a
     * "create new account" call: that would orphan the guest row's balance
     * and trial the way plain register() used to (see GuestMergeService for
     * the equivalent when the user instead signs into a *different*,
     * already-existing account).
     */
    @Transactional
    public AuthResponse upgradeGuest(Long userId, UpgradeRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }
        if (user.getPasswordHash() != null || user.getTelegramId() != null || user.getGoogleSub() != null) {
            throw new IllegalStateException("Account is already registered");
        }
        if (request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("Email cannot be blank");
        }
        if (request.password() == null || request.password().length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }

        String normalizedEmail = request.email().toLowerCase().trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("Email already registered");
        }

        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user = userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }

    private String generateUniqueReferralCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempts = 0; attempts < 10; attempts++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            String code = sb.toString();
            if (!userRepository.existsByReferralCode(code)) {
                return code;
            }
        }
        return "REF" + System.currentTimeMillis();
    }
}
