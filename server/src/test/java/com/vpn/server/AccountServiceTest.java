package com.vpn.server;

import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.entity.Device;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.User;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {

    private UserRepository userRepository;
    private SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final OneTimeCodeService codes = new OneTimeCodeService();
    private MailService mailService;
    private TelegramBotService bot;
    private DeviceManagementService devices;
    private AccountService account;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        mailService = mock(MailService.class);
        bot = mock(TelegramBotService.class);
        devices = mock(DeviceManagementService.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.generateToken(anyLong(), any(), any())).thenReturn("jwt");
        account = new AccountService(userRepository, subscriptionRepository, encoder, jwt, codes, mailService, bot, devices);

        user = new User();
        user.setId(1L);
        user.setStatus("ACTIVE");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void aTelegramAccountSignsIntoTheAppWithACode() {
        user.setTelegramId(55L);
        String code = codes.createLoginCode(1L);

        AuthResponse res = account.loginWithCode(code);

        assertEquals(1L, res.userId());
        assertThrows(IllegalArgumentException.class, () -> account.loginWithCode(code));
    }

    @Test
    void passwordResetCodeGoesToTelegramAndResetsThePassword() {
        user.setEmail("a@b.c");
        user.setTelegramId(55L);
        user.setPasswordHash(encoder.encode("oldpass"));
        when(userRepository.findByEmail("a@b.c")).thenReturn(Optional.of(user));
        AtomicReference<String> sent = new AtomicReference<>();
        doAnswer(i -> { sent.set(i.getArgument(1)); return null; }).when(bot).sendTextMessage(eq(55L), anyString(), isNull());

        account.requestPasswordReset(" A@B.C ");
        String code = sent.get().replaceAll("(?s).*<b>(\\d{6})</b>.*", "$1");
        account.confirmPasswordReset("a@b.c", code, "newpass1");

        assertTrue(encoder.matches("newpass1", user.getPasswordHash()));
    }

    @Test
    void resetForAnUnknownAddressSaysNothingAndSendsNothing() {
        when(userRepository.findByEmail("nobody@x.y")).thenReturn(Optional.empty());
        account.requestPasswordReset("nobody@x.y");
        verifyNoInteractions(bot);
        verify(mailService, never()).send(any(), any(), any());
    }

    @Test
    void aWrongResetCodeIsRefused() {
        user.setEmail("a@b.c");
        when(userRepository.findByEmail("a@b.c")).thenReturn(Optional.of(user));
        codes.createResetCode(1L);
        assertThrows(IllegalArgumentException.class, () -> account.confirmPasswordReset("a@b.c", "xxxxxx", "newpass1"));
    }

    @Test
    void aTelegramAccountCanAddEmailAndPassword() {
        user.setTelegramId(55L);
        when(userRepository.existsByEmail("me@x.y")).thenReturn(false);

        account.setCredentials(1L, "Me@X.y", null, "secret1");

        assertEquals("me@x.y", user.getEmail());
        assertTrue(encoder.matches("secret1", user.getPasswordHash()));
    }

    @Test
    void changingAPasswordNeedsTheCurrentOne() {
        user.setEmail("a@b.c");
        user.setPasswordHash(encoder.encode("oldpass"));
        assertThrows(IllegalArgumentException.class, () -> account.setCredentials(1L, null, "wrong", "newpass1"));
        account.setCredentials(1L, null, "oldpass", "newpass1");
        assertTrue(encoder.matches("newpass1", user.getPasswordHash()));
    }

    @Test
    void deletingRemovesEveryWayInAndStopsPlans() {
        user.setEmail("a@b.c");
        user.setTelegramId(55L);
        user.setGoogleSub("g");
        user.setPasswordHash("h");
        UUID oldToken = user.getSubscriptionToken();
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(3L);
        when(devices.getUserDevices(1L)).thenReturn(List.of(d));
        Subscription active = new Subscription();
        active.setStatus("ACTIVE");
        active.setAutoRenew(true);
        when(subscriptionRepository.findByUserIdAndStatus(1L, "ACTIVE")).thenReturn(List.of(active));

        account.deleteAccount(1L);

        verify(devices).deleteDevice(1L, 3L);
        assertEquals("CANCELLED", active.getStatus());
        assertFalse(active.getAutoRenew());
        assertEquals("DELETED", user.getStatus());
        assertNull(user.getEmail());
        assertNull(user.getTelegramId());
        assertNull(user.getGoogleSub());
        assertNull(user.getPasswordHash());
        assertNotEquals(oldToken, user.getSubscriptionToken());
    }
}
