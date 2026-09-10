package com.vpn.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.TelegramAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramAuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private TariffRepository tariffRepository;

    @Mock
    private JwtUtil jwtUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TelegramAuthService telegramAuthService;

    private final String testBotToken = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";

    @BeforeEach
    void setUp() {
        telegramAuthService = new TelegramAuthService(
                userRepository,
                subscriptionRepository,
                tariffRepository,
                jwtUtil
        );
        telegramAuthService.setBotToken(testBotToken);
    }

    private String generateValidInitData(long tgId, String username, long authDateEpoch) throws Exception {
        String userJson = "{\"id\":" + tgId + ",\"first_name\":\"Test\",\"username\":\"" + username + "\",\"language_code\":\"ru\"}";
        String dataCheckString = "auth_date=" + authDateEpoch + "\nuser=" + userJson;

        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] secretKey = macSha256("WebAppData".getBytes(StandardCharsets.UTF_8), testBotToken.getBytes(StandardCharsets.UTF_8));
        mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        byte[] hashBytes = mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder();
        for (byte b : hashBytes) {
            hex.append(String.format("%02x", b));
        }

        return "auth_date=" + authDateEpoch +
                "&user=" + URLEncoder.encode(userJson, StandardCharsets.UTF_8) +
                "&hash=" + hex;
    }

    private byte[] macSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    @Test
    void testAuthenticateExistingUser() throws Exception {
        long tgId = 987654321L;
        long nowEpoch = Instant.now().getEpochSecond();
        String initData = generateValidInitData(tgId, "roman_test", nowEpoch);

        User existing = new User();
        existing.setId(42L);
        existing.setTelegramId(tgId);
        existing.setEmail("tg_987654321@t.me");
        existing.setRole("USER");
        existing.setStatus("ACTIVE");
        existing.setReferralCode("REF42");

        when(userRepository.findByTelegramId(tgId)).thenReturn(Optional.of(existing));
        when(jwtUtil.generateToken(42L, "tg_987654321@t.me", "USER")).thenReturn("jwt_mock_token");

        AuthResponse resp = telegramAuthService.authenticateTelegram(initData, null);

        assertNotNull(resp);
        assertEquals("jwt_mock_token", resp.token());
        assertEquals(42L, resp.userId());
        assertEquals("USER", resp.role());
    }

    @Test
    void testAuthenticateNewUserWithTrial() throws Exception {
        long tgId = 555123456L;
        long nowEpoch = Instant.now().getEpochSecond();
        String initData = generateValidInitData(tgId, "newbie", nowEpoch);

        User newUser = new User();
        newUser.setId(100L);
        newUser.setTelegramId(tgId);
        newUser.setEmail("tg_555123456@t.me");
        newUser.setRole("USER");
        newUser.setStatus("ACTIVE");
        newUser.setReferralCode("TGNEW");

        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setIsActive(true);
        trial.setTrafficQuotaBytes(10_000_000_000L);

        when(userRepository.findByTelegramId(tgId)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(newUser);
        when(tariffRepository.findById("trial")).thenReturn(Optional.of(trial));
        when(jwtUtil.generateToken(any(), any(), any())).thenReturn("jwt_new_token");

        AuthResponse resp = telegramAuthService.authenticateTelegram(initData, null);

        assertNotNull(resp);
        assertEquals("jwt_new_token", resp.token());
        verify(subscriptionRepository).save(any());
    }

    @Test
    void testInvalidSignatureThrows() {
        String badInitData = "auth_date=" + Instant.now().getEpochSecond() +
                "&user=" + URLEncoder.encode("{\"id\":123}", StandardCharsets.UTF_8) +
                "&hash=0000000000000000000000000000000000000000000000000000000000000000";

        assertThrows(IllegalArgumentException.class, () ->
                telegramAuthService.authenticateTelegram(badInitData, null)
        );
    }
}
