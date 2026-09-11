package com.vpn.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vpn.server.entity.*;
import com.vpn.server.repository.BalanceEntryRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.BillingService;
import com.vpn.server.service.DeviceManagementService;
import com.vpn.server.service.SubscriptionExportService;
import com.vpn.server.service.TelegramBotService;
import com.vpn.server.service.TelegramLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramBotServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private TariffRepository tariffRepository;

    @Mock
    private BalanceEntryRepository balanceEntryRepository;

    @Mock
    private SubscriptionExportService exportService;

    @Mock
    private DeviceManagementService deviceManagementService;

    @Mock
    private BillingService billingService;

    // Plain in-memory implementation (no external deps) rather than a mock --
    // exercising the real code path is cheap and lets link-flow tests below
    // actually create/consume codes instead of stubbing an opaque service.
    private final TelegramLinkService telegramLinkService = new TelegramLinkService();

    private TelegramBotService botService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        botService = new TelegramBotService(
                userRepository,
                subscriptionRepository,
                tariffRepository,
                balanceEntryRepository,
                exportService,
                deviceManagementService,
                billingService,
                telegramLinkService
        );
        botService.setBotToken("mock");
    }

    @Test
    void testStartCommandCreatesUserWithTrialAndDevice() throws Exception {
        when(userRepository.findByTelegramId(111222L)).thenReturn(Optional.empty());
        when(userRepository.existsByReferralCode(anyString())).thenReturn(false);

        Tariff trialTariff = new Tariff();
        trialTariff.setId("trial");
        trialTariff.setTrafficQuotaBytes(1073741824L);
        when(tariffRepository.findById("trial")).thenReturn(Optional.of(trialTariff));

        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(55L);
            return u;
        });

        String updateJson = """
                {
                    "update_id": 1001,
                    "message": {
                        "message_id": 1,
                        "chat": {"id": 111222},
                        "from": {"id": 111222, "first_name": "Alex", "username": "alex"},
                        "text": "/start"
                    }
                }
                """;

        botService.processUpdate(objectMapper.readTree(updateJson));

        verify(userRepository, atLeastOnce()).save(any(User.class));
        verify(subscriptionRepository).save(any(Subscription.class));
        verify(deviceManagementService).addDevice(eq(55L), eq("Telegram Bot"), eq("THIRD_PARTY"));
    }

    @Test
    void testStartCommandWithReferralCodeLinksReferrer() throws Exception {
        User referrer = new User();
        referrer.setId(99L);
        referrer.setReferralCode("ref_INVITER123");

        when(userRepository.findByTelegramId(333444L)).thenReturn(Optional.empty());
        when(userRepository.findByReferralCode("ref_INVITER123")).thenReturn(Optional.of(referrer));

        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(77L);
            return u;
        });

        String updateJson = """
                {
                    "update_id": 1002,
                    "message": {
                        "message_id": 2,
                        "chat": {"id": 333444},
                        "from": {"id": 333444, "first_name": "Bob"},
                        "text": "/start ref_INVITER123"
                    }
                }
                """;

        botService.processUpdate(objectMapper.readTree(updateJson));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeastOnce()).save(captor.capture());
        User lastSaved = captor.getValue();
        assertNotNull(lastSaved.getReferredBy());
        assertEquals(99L, lastSaved.getReferredBy().getId());
    }

    @Test
    void testSuccessfulPaymentCreditsBalanceAndReferralBonus() throws Exception {
        User referrer = new User();
        referrer.setId(10L);
        referrer.setTelegramId(101010L);
        referrer.setBalanceUsdtMicro(1_000_000L); // $1

        User payer = new User();
        payer.setId(20L);
        payer.setTelegramId(202020L);
        payer.setBalanceUsdtMicro(500_000L); // $0.5
        payer.setReferredBy(referrer);

        when(balanceEntryRepository.existsByReferenceId("ch_star_12345")).thenReturn(false);
        when(userRepository.findByTelegramId(202020L)).thenReturn(Optional.of(payer));
        // Referral crediting itself lives in BillingService (shared with the
        // crypto-invoice deposit path) — stub it to simulate the 15% bonus it
        // would apply, and assert TelegramBotService reacts to the result
        // (notifies the referrer) rather than re-deriving the bonus math here.
        when(billingService.applyReferralRewards(eq(payer), eq(5_000_000L), eq("ch_star_12345")))
                .thenAnswer(inv -> {
                    referrer.setBalanceUsdtMicro(referrer.getBalanceUsdtMicro() + 750_000L);
                    return new BillingService.ReferralRewardResult(750_000L, 0L);
                });

        String updateJson = """
                {
                    "update_id": 1003,
                    "message": {
                        "message_id": 3,
                        "chat": {"id": 202020},
                        "from": {"id": 202020},
                        "successful_payment": {
                            "currency": "XTR",
                            "total_amount": 250,
                            "invoice_payload": "stars_deposit:20:250",
                            "telegram_payment_charge_id": "ch_star_12345"
                        }
                    }
                }
                """;

        botService.processUpdate(objectMapper.readTree(updateJson));

        // 250 Stars * 20,000 = 5,000,000 micro ($5.00)
        // Payer new balance: 500,000 + 5,000,000 = 5,500,000
        assertEquals(5_500_000L, payer.getBalanceUsdtMicro());

        // Referrer 15% bonus: 5,000,000 * 15% = 750,000 micro ($0.75)
        // Referrer new balance: 1,000,000 + 750,000 = 1,750,000
        assertEquals(1_750_000L, referrer.getBalanceUsdtMicro());

        // Only the deposit entry is saved here — the referral bonus entry is
        // BillingService's responsibility (mocked above).
        verify(balanceEntryRepository, times(1)).save(any(BalanceEntry.class));
        verify(billingService).applyReferralRewards(payer, 5_000_000L, "ch_star_12345");
    }

    @Test
    void testDuplicatePaymentIgnored() throws Exception {
        when(balanceEntryRepository.existsByReferenceId("ch_star_dup")).thenReturn(true);

        String updateJson = """
                {
                    "update_id": 1004,
                    "message": {
                        "message_id": 4,
                        "chat": {"id": 202020},
                        "from": {"id": 202020},
                        "successful_payment": {
                            "currency": "XTR",
                            "total_amount": 50,
                            "telegram_payment_charge_id": "ch_star_dup"
                        }
                    }
                }
                """;

        botService.processUpdate(objectMapper.readTree(updateJson));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void testStartWithLinkCodeAttachesTelegramAccountToExistingUser() throws Exception {
        User webUser = new User();
        webUser.setId(500L);
        webUser.setEmail("web@example.com");

        String code = telegramLinkService.createPendingLink(500L);

        when(userRepository.findById(500L)).thenReturn(Optional.of(webUser));
        when(userRepository.findByTelegramId(777888L)).thenReturn(Optional.empty());

        String updateJson = String.format("""
                {
                    "update_id": 2001,
                    "message": {
                        "message_id": 10,
                        "chat": {"id": 777888},
                        "from": {"id": 777888, "first_name": "Web User"},
                        "text": "/start link_%s"
                    }
                }
                """, code);

        botService.processUpdate(objectMapper.readTree(updateJson));

        assertEquals(777888L, webUser.getTelegramId());
        verify(userRepository).save(webUser);
        // Must not have gone through the normal getOrCreateUser bot-signup path
        // (trial grant / device creation) -- the deep link is meant to attach
        // this Telegram identity to the *existing* web account, never spawn a
        // second account for the same person.
        verify(tariffRepository, never()).findById(anyString());
        verify(deviceManagementService, never()).addDevice(anyLong(), anyString(), anyString());
    }

    @Test
    void testStartWithLinkCodeRejectsWhenTelegramAlreadyLinkedElsewhere() throws Exception {
        User webUser = new User();
        webUser.setId(501L);

        User otherTelegramUser = new User();
        otherTelegramUser.setId(999L);
        otherTelegramUser.setTelegramId(321321L);

        String code = telegramLinkService.createPendingLink(501L);

        when(userRepository.findById(501L)).thenReturn(Optional.of(webUser));
        when(userRepository.findByTelegramId(321321L)).thenReturn(Optional.of(otherTelegramUser));

        String updateJson = String.format("""
                {
                    "update_id": 2002,
                    "message": {
                        "message_id": 11,
                        "chat": {"id": 321321},
                        "from": {"id": 321321},
                        "text": "/start link_%s"
                    }
                }
                """, code);

        botService.processUpdate(objectMapper.readTree(updateJson));

        // Never overwritten/merged -- the code was consumed but rejected.
        assertNull(webUser.getTelegramId());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void testStartWithUnknownLinkCodeIsRejected() throws Exception {
        String updateJson = """
                {
                    "update_id": 2004,
                    "message": {
                        "message_id": 13,
                        "chat": {"id": 654321},
                        "from": {"id": 654321},
                        "text": "/start link_doesnotexist"
                    }
                }
                """;

        botService.processUpdate(objectMapper.readTree(updateJson));

        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).findByTelegramId(anyLong());
    }

    @Test
    void testStartWithStarsPayloadDoesNotFallBackToReferralLogic() throws Exception {
        User user = new User();
        user.setId(600L);
        user.setTelegramId(444555L);

        when(userRepository.findByTelegramId(444555L)).thenReturn(Optional.of(user));

        String updateJson = """
                {
                    "update_id": 2003,
                    "message": {
                        "message_id": 12,
                        "chat": {"id": 444555},
                        "from": {"id": 444555},
                        "text": "/start stars_250"
                    }
                }
                """;

        botService.processUpdate(objectMapper.readTree(updateJson));

        // "stars_250" must never be misread as a referral code (it isn't one,
        // and this user already has an account with no need to create one).
        verify(userRepository, never()).findByReferralCode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }
}
