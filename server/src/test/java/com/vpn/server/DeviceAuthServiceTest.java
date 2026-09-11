package com.vpn.server;

import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.DeviceAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceAuthServiceTest {

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

    private DeviceAuthService deviceAuthService;

    @BeforeEach
    void setUp() {
        deviceAuthService = new DeviceAuthService(
                userRepository,
                subscriptionRepository,
                tariffRepository,
                jwtUtil,
                transactionManager
        );
    }

    @Test
    void testAuthenticateExistingDevice() {
        String deviceUuid = "184924bb-1a87-4c10-a001-765bdd83b654";

        User existing = new User();
        existing.setId(7L);
        existing.setDeviceUuid(deviceUuid);
        existing.setEmail("device_" + deviceUuid + "@device.local");
        existing.setRole("USER");
        existing.setStatus("ACTIVE");
        existing.setReferralCode("REF7");

        when(userRepository.findByDeviceUuid(deviceUuid)).thenReturn(Optional.of(existing));
        when(jwtUtil.generateToken(7L, existing.getEmail(), "USER")).thenReturn("jwt_existing_token");

        AuthResponse resp = deviceAuthService.authenticateDevice(deviceUuid, null);

        assertNotNull(resp);
        assertEquals("jwt_existing_token", resp.token());
        assertEquals(7L, resp.userId());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void testAuthenticateNewDeviceWithTrial() {
        String deviceUuid = "new-device-uuid";

        User newUser = new User();
        newUser.setId(101L);
        newUser.setDeviceUuid(deviceUuid);
        newUser.setEmail("device_" + deviceUuid + "@device.local");
        newUser.setRole("USER");
        newUser.setStatus("ACTIVE");
        newUser.setReferralCode("DEVNEW");

        Tariff trial = new Tariff();
        trial.setId("trial");
        trial.setIsActive(true);
        trial.setTrafficQuotaBytes(10_000_000_000L);

        when(userRepository.findByDeviceUuid(deviceUuid)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(newUser);
        when(tariffRepository.findById("trial")).thenReturn(Optional.of(trial));
        when(jwtUtil.generateToken(any(), any(), any())).thenReturn("jwt_new_device_token");
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        AuthResponse resp = deviceAuthService.authenticateDevice(deviceUuid, null);

        assertNotNull(resp);
        assertEquals("jwt_new_device_token", resp.token());
        verify(subscriptionRepository).save(any());
    }

    /**
     * Regression test for the exact production bug (see class-level javadoc on
     * DeviceAuthConcurrencyTest): two concurrent POST /api/v1/auth/device
     * requests for the same brand-new deviceUuid both see "no user found" and
     * both attempt to INSERT with the same derived email, which collides on
     * users_email_key. The loser must recover by re-reading the winner's row
     * rather than letting DataIntegrityViolationException turn into a 500.
     */
    @Test
    void testConcurrentNewDeviceRaceRecoversWinnerRow() {
        String deviceUuid = "184924bb-1a87-4c10-a001-765bdd83b654";

        User winner = new User();
        winner.setId(202L);
        winner.setDeviceUuid(deviceUuid);
        winner.setEmail("device_" + deviceUuid + "@device.local");
        winner.setRole("USER");
        winner.setStatus("ACTIVE");
        winner.setReferralCode("DEVWIN");

        // First call (from findOrCreateDeviceUser): not found yet.
        // Second call (the post-catch retry): the winner's row is there.
        when(userRepository.findByDeviceUuid(deviceUuid))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"users_email_key\""));
        when(jwtUtil.generateToken(202L, winner.getEmail(), "USER")).thenReturn("jwt_winner_token");

        AuthResponse resp = deviceAuthService.authenticateDevice(deviceUuid, null);

        assertNotNull(resp);
        assertEquals("jwt_winner_token", resp.token());
        assertEquals(202L, resp.userId());
        // The loser must never grant its own trial subscription for a user it
        // never actually created.
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void testRaceWhereRetryAlsoFindsNothingRethrowsOriginalException() {
        String deviceUuid = "impossible-uuid";

        DataIntegrityViolationException original = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"users_email_key\"");

        when(userRepository.findByDeviceUuid(deviceUuid)).thenReturn(Optional.empty());
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(userRepository.save(any(User.class))).thenThrow(original);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class, () ->
                deviceAuthService.authenticateDevice(deviceUuid, null)
        );
        assertSame(original, thrown);
    }

    @Test
    void testBlankDeviceUuidRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                deviceAuthService.authenticateDevice("  ", null)
        );
        verifyNoInteractions(userRepository);
    }
}
