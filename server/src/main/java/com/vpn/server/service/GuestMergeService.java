package com.vpn.server.service;

import com.vpn.server.entity.BalanceEntry;
import com.vpn.server.entity.User;
import com.vpn.server.repository.BalanceEntryRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Folds a no-signup device-trial account (see DeviceAuthService) into the
 * real account a user just signed into on the same install, instead of
 * leaving the trial row as a permanent orphan (0 balance and an unused-trial
 * clock that nobody can reach again once AuthController#login hands back a
 * different user's token).
 *
 * Only ever pulls FROM a genuine guest row (no password/telegram/google)
 * INTO the account that was just authenticated — never the reverse, and
 * never touches two credentialed accounts. Called from AuthController right
 * after a successful login, best-effort: a merge failure must not fail the
 * login it rides along with.
 */
@Service
public class GuestMergeService {

    private static final Logger log = LoggerFactory.getLogger(GuestMergeService.class);

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BalanceEntryRepository balanceEntryRepository;
    private final com.vpn.server.grpc.AgentStreamServiceImpl agentStreamService;

    public GuestMergeService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            BalanceEntryRepository balanceEntryRepository,
            com.vpn.server.grpc.AgentStreamServiceImpl agentStreamService
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.balanceEntryRepository = balanceEntryRepository;
        this.agentStreamService = agentStreamService;
    }

    @Transactional
    public void mergeGuestIntoTarget(String deviceUuid, Long targetUserId) {
        Optional<User> guestOpt = userRepository.findByDeviceUuid(deviceUuid);
        if (guestOpt.isEmpty()) return;

        User guest = guestOpt.get();
        if (guest.getId().equals(targetUserId)) return; // logging into the account this device UUID already belongs to
        if (!isGuest(guest)) return; // never merge a second credentialed account into this one

        User target = userRepository.findById(targetUserId).orElseThrow();

        if (guest.getBalanceUsdtMicro() != null && guest.getBalanceUsdtMicro() > 0) {
            long amount = guest.getBalanceUsdtMicro();
            long newBalance = target.getBalanceUsdtMicro() + amount;
            target.setBalanceUsdtMicro(newBalance);
            userRepository.save(target);

            BalanceEntry entry = new BalanceEntry();
            entry.setUser(target);
            entry.setAmountUsdtMicro(amount);
            entry.setBalanceAfterMicro(newBalance);
            entry.setType("GUEST_MERGE");
            entry.setDescription("Merged balance from trial device account " + guest.getId());
            entry.setReferenceId("guest_merge:" + guest.getId());
            balanceEntryRepository.save(entry);
        }

        // Only hand over the guest's active (near-certainly trial) subscription
        // if the target doesn't already have one running — reassigning a
        // second ACTIVE subscription onto the same account is ambiguous
        // (which one renews? which traffic counter is "the" counter?), so if
        // the target already has one, the guest's is left behind and deleted
        // with the rest of the guest row below.
        // Nor when the target has a paid plan that ran out of traffic
        // (EXHAUSTED — still its plan until the period ends; a trial on top
        // would hide it), or has had a trial of its own: one trial per
        // account, however many devices it signs in from.
        boolean targetHasPlan =
                subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(targetUserId, "ACTIVE").isPresent()
                        || subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(targetUserId, "EXHAUSTED")
                                .filter(sub -> sub.getCurrentPeriodEnd().isAfter(java.time.Instant.now()))
                                .isPresent();
        boolean targetUsedTrial = subscriptionRepository.existsByUserIdAndTariffId(targetUserId, "trial");
        if (!targetHasPlan && !targetUsedTrial) {
            subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(guest.getId(), "ACTIVE")
                    .ifPresent(sub -> {
                        sub.setUser(target);
                        subscriptionRepository.save(sub);
                    });
        }

        Long guestId = guest.getId();
        userRepository.delete(guest); // ON DELETE CASCADE clears its remaining devices/subscriptions/balance entries/invoices
        log.info("Merged guest device account {} into {}", guestId, targetUserId);
        agentStreamService.pushConfigSyncToAll();
    }

    private boolean isGuest(User u) {
        return u.getPasswordHash() == null && u.getTelegramId() == null && u.getGoogleSub() == null;
    }
}
