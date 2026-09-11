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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class TelegramAuthService {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthService.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TariffRepository tariffRepository;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    // See DeviceAuthService's newUserTransactionTemplate for why the "create
    // new user" insert needs its own REQUIRES_NEW transaction rather than a
    // plain @Transactional method called from within this same class.
    private final TransactionTemplate newUserTransactionTemplate;

    @Value("${vpn.telegram.bot-token:}")
    private String botToken;

    public TelegramAuthService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            TariffRepository tariffRepository,
            JwtUtil jwtUtil,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffRepository = tariffRepository;
        this.jwtUtil = jwtUtil;
        this.newUserTransactionTemplate = new TransactionTemplate(transactionManager);
        this.newUserTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public record TelegramUser(
            long id,
            String firstName,
            String lastName,
            String username,
            String languageCode
    ) {}

    public AuthResponse authenticateTelegram(String initData, String referralCode) {
        TelegramUser tgUser = validateAndParseInitData(initData);

        User user = findOrCreateTelegramUser(tgUser, referralCode);

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, user.getId(), user.getEmail(), user.getRole(), user.getReferralCode());
    }

    /**
     * Find-or-create keyed by telegramId, resilient to two concurrent
     * requests racing to create the same never-before-seen telegramId (see
     * DeviceAuthService#findOrCreateDeviceUser for the full race explanation).
     */
    private User findOrCreateTelegramUser(TelegramUser tgUser, String referralCode) {
        Optional<User> existing = userRepository.findByTelegramId(tgUser.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return newUserTransactionTemplate.execute(status -> createNewTelegramUser(tgUser, referralCode));
        } catch (DataIntegrityViolationException e) {
            return userRepository.findByTelegramId(tgUser.id()).orElseThrow(() -> e);
        }
    }

    public TelegramUser validateAndParseInitData(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new IllegalArgumentException("initData cannot be empty");
        }

        try {
            Map<String, String> params = parseQueryString(initData);
            String hash = params.remove("hash");
            if (hash == null) {
                throw new IllegalArgumentException("initData missing hash");
            }

            // If botToken is configured and not empty, perform full HMAC-SHA256 validation
            if (botToken != null && !botToken.isBlank() && !"mock".equalsIgnoreCase(botToken)) {
                List<String> sortedKeys = new ArrayList<>(params.keySet());
                Collections.sort(sortedKeys);

                StringBuilder dataCheckSb = new StringBuilder();
                for (int i = 0; i < sortedKeys.size(); i++) {
                    String key = sortedKeys.get(i);
                    dataCheckSb.append(key).append("=").append(params.get(key));
                    if (i < sortedKeys.size() - 1) {
                        dataCheckSb.append("\n");
                    }
                }

                String dataCheckString = dataCheckSb.toString();
                byte[] secretKey = hmacSha256("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
                byte[] calculatedHashBytes = hmacSha256(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
                String calculatedHash = bytesToHex(calculatedHashBytes);

                if (!calculatedHash.equalsIgnoreCase(hash)) {
                    throw new IllegalArgumentException("Invalid Telegram initData signature");
                }

                // Check auth_date not older than 24 hours
                String authDateStr = params.get("auth_date");
                if (authDateStr != null) {
                    long authDateEpoch = Long.parseLong(authDateStr);
                    long nowEpoch = Instant.now().getEpochSecond();
                    if (nowEpoch - authDateEpoch > 86400) {
                        throw new IllegalArgumentException("initData has expired (older than 24h)");
                    }
                }
            }

            String userJson = params.get("user");
            if (userJson == null) {
                throw new IllegalArgumentException("initData missing user payload");
            }

            JsonNode root = objectMapper.readTree(userJson);
            long id = root.path("id").asLong();
            String firstName = root.path("first_name").asText("");
            String lastName = root.path("last_name").asText("");
            String username = root.path("username").asText("");
            String lang = root.path("language_code").asText("en");

            return new TelegramUser(id, firstName, lastName, username, lang);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse Telegram initData", e);
            throw new IllegalArgumentException("Malformed Telegram initData: " + e.getMessage());
        }
    }

    private User createNewTelegramUser(TelegramUser tgUser, String referralCode) {
        User user = new User();
        user.setTelegramId(tgUser.id());
        user.setEmail("tg_" + tgUser.id() + "@t.me");
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
            log.info("Granted trial subscription to new Telegram user {}", user.getId());
        }

        return user;
    }

    private Map<String, String> parseQueryString(String query) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, value);
            }
        }
        return map;
    }

    private byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 calculation error", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
        return "TG" + System.currentTimeMillis();
    }
}
