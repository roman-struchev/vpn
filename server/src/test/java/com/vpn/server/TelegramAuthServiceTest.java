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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

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

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TelegramAuthService telegramAuthService;

    private final String testBotToken = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";

    @BeforeEach
    void setUp() {
        telegramAuthService = new TelegramAuthService(
                userRepository,
                subscriptionRepository,
                tariffRepository,
                jwtUtil,
                transactionManager
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
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        AuthResponse resp = telegramAuthService.authenticateTelegram(initData, null);

        assertNotNull(resp);
        assertEquals("jwt_new_token", resp.token());
        verify(subscriptionRepository).save(any());
    }

    /**
     * Regression test for the create-race that produced an unhandled 500 in
     * production (duplicate key on users_email_key/telegram_id): two
     * concurrent first-time logins for the same telegramId can both see "no
     * user found" and both attempt to INSERT. The loser must recover by
     * re-reading the winner's row instead of letting the constraint violation
     * bubble up. Simulated here by making the first findByTelegramId() call
     * report "not found", the save() attempt fail as if it lost the DB race,
     * and the retry findByTelegramId() call (post-catch) return the winner's
     * already-committed row.
     */
    @Test
    void testConcurrentNewUserRaceRecoversWinnerRow() throws Exception {
        long tgId = 777888999L;
        long nowEpoch = Instant.now().getEpochSecond();
        String initData = generateValidInitData(tgId, "racer", nowEpoch);

        User winner = new User();
        winner.setId(200L);
        winner.setTelegramId(tgId);
        winner.setEmail("tg_777888999@t.me");
        winner.setRole("USER");
        winner.setStatus("ACTIVE");
        winner.setReferralCode("TGWIN");

        when(userRepository.findByTelegramId(tgId))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"users_telegram_id_key\""));
        when(jwtUtil.generateToken(200L, "tg_777888999@t.me", "USER")).thenReturn("jwt_winner_token");

        AuthResponse resp = telegramAuthService.authenticateTelegram(initData, null);

        assertNotNull(resp);
        assertEquals("jwt_winner_token", resp.token());
        assertEquals(200L, resp.userId());
        // The loser must never grant its own trial subscription for a user it
        // never actually created.
        verify(subscriptionRepository, never()).save(any());
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
