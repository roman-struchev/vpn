package com.vpn.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vpn.server.entity.*;
import com.vpn.server.repository.BalanceEntryRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.DeviceManagementService;
import com.vpn.server.service.SubscriptionExportService;
import com.vpn.server.service.TelegramBotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
                deviceManagementService
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

        // Verify balance entries saved for deposit and referral bonus
        verify(balanceEntryRepository, times(2)).save(any(BalanceEntry.class));
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
}
