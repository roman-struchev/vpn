package com.vpn.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);
    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TariffRepository tariffRepository;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient httpClient;

    @Value("${vpn.google.client-id:}")
    private String googleClientId;

    public GoogleAuthService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            TariffRepository tariffRepository,
            JwtUtil jwtUtil
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffRepository = tariffRepository;
        this.jwtUtil = jwtUtil;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setGoogleClientId(String googleClientId) {
        this.googleClientId = googleClientId;
    }

    public record GoogleUser(
            String sub,
            String email,
            boolean emailVerified
    ) {}

    @Transactional
    public AuthResponse authenticateGoogle(String idToken, String referralCode) {
        GoogleUser googleUser = verifyIdToken(idToken);

        User user = userRepository.findByGoogleSub(googleUser.sub())
                .orElseGet(() -> createNewGoogleUser(googleUser, referralCode));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }

    public GoogleUser verifyIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("idToken cannot be empty");
        }

        if (googleClientId == null || googleClientId.isBlank()) {
            throw new IllegalStateException(
                    "Google Sign-In is not configured on this server (vpn.google.client-id / GOOGLE_OAUTH_CLIENT_ID is unset)"
            );
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKENINFO_URL + idToken))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("Invalid or expired Google idToken");
            }

            JsonNode root = objectMapper.readTree(response.body());

            String aud = root.path("aud").asText("");
            if (!googleClientId.equals(aud)) {
                throw new IllegalArgumentException("Google idToken audience does not match this application");
            }

            String sub = root.path("sub").asText("");
            if (sub.isBlank()) {
                throw new IllegalArgumentException("Google idToken missing sub claim");
            }

            String email = root.path("email").asText("");
            boolean emailVerified = root.path("email_verified").asBoolean(false);

            return new GoogleUser(sub, email, emailVerified);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to verify Google idToken", e);
            throw new IllegalArgumentException("Failed to verify Google idToken: " + e.getMessage());
        }
    }

    private User createNewGoogleUser(GoogleUser googleUser, String referralCode) {
        User user = new User();
        user.setGoogleSub(googleUser.sub());
        if (googleUser.emailVerified() && googleUser.email() != null && !googleUser.email().isBlank()) {
            user.setEmail(googleUser.email());
        } else {
            user.setEmail("google_" + googleUser.sub() + "@google.local");
        }
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setBalanceUsdtMicro(0L);
        user.setReferralCode(generateUniqueReferralCode());

        if (referralCode != null && !referralCode.isBlank()) {
            userRepository.findByReferralCode(referralCode.trim())
                    .ifPresent(user::setReferredBy);
        }

        user = userRepository.save(user);

        // Grant 3-day trial subscription if trial tariff exists
        Optional<Tariff> trialTariff = tariffRepository.findById("trial");
        if (trialTariff.isPresent() && trialTariff.get().getIsActive()) {
            Subscription sub = new Subscription();
            sub.setUser(user);
            sub.setTariff(trialTariff.get());
            sub.setStatus("ACTIVE");
            sub.setIsAnnual(false);
            sub.setAutoRenew(false);
            sub.setCurrentPeriodStart(Instant.now());
            sub.setCurrentPeriodEnd(Instant.now().plus(3, ChronoUnit.DAYS));
            sub.setTrafficUsedBytes(0L);
            sub.setTrafficLimitBytes(trialTariff.get().getTrafficQuotaBytes());
            subscriptionRepository.save(sub);
            log.info("Granted trial subscription to new Google user {}", user.getId());
        }

        return user;
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
        return "GG" + System.currentTimeMillis();
    }
}
