package com.vpn.server.service;

import com.vpn.server.entity.*;
import com.vpn.server.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private final SecureRandom random = new SecureRandom();

    // Referrer gets a recurring cut of every deposit their invitee makes;
    // the invitee additionally gets a one-time bonus on their first deposit
    // only, so it reads as a welcome gift rather than a standing discount.
    private static final long REFERRER_BONUS_PERCENT = 15;
    private static final long REFEREE_WELCOME_BONUS_PERCENT = 10;
    // Trial's "period end" sentinel for "no real time limit" (see
    // Subscription#hasNoExpiry, which treats anything past its own 10-year
    // threshold as unlimited) — used here and by DeviceAuthService/
    // TelegramAuthService's trial grants.
    static final long NO_EXPIRY_DAYS = 36_500;

    private final UserRepository userRepository;
    private final TariffRepository tariffRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BalanceEntryRepository balanceEntryRepository;
    private final CryptoInvoiceRepository cryptoInvoiceRepository;

    public BillingService(
            UserRepository userRepository,
            TariffRepository tariffRepository,
            SubscriptionRepository subscriptionRepository,
            BalanceEntryRepository balanceEntryRepository,
            CryptoInvoiceRepository cryptoInvoiceRepository) {
        this.userRepository = userRepository;
        this.tariffRepository = tariffRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.balanceEntryRepository = balanceEntryRepository;
        this.cryptoInvoiceRepository = cryptoInvoiceRepository;
    }

    @Value("${vpn.crypto.tron-deposit-address:TXxxDefaultDepositAddressTRC20}")
    private String defaultTronDepositAddress;

    // Ethereum/Base/Arbitrum/Polygon all use the same 0x address space for a given
    // key (docs/PLAN.md §7: "один адрес приёма на сеть" — one EVM address covers
    // every EVM-compatible chain the scanner is pointed at, only rpc-url/chain-id
    // differ per network in vpn.blockchain.evm.*).
    @Value("${vpn.crypto.evm-deposit-address:}")
    private String defaultEvmDepositAddress;

    @Transactional
    public CryptoInvoice createInvoice(Long userId, String chain, Long baseAmountUsdtMicro) {
        String recipientAddress = resolveDefaultDepositAddress(chain);
        if (recipientAddress == null || recipientAddress.isBlank()) {
            throw new IllegalStateException(
                    "No deposit address configured for chain " + chain
                            + " (set vpn.crypto." + (isEvmChain(chain) ? "evm" : "tron") + "-deposit-address)");
        }
        return createInvoice(userId, chain, baseAmountUsdtMicro, recipientAddress);
    }

    /**
     * Picks the right default deposit address for the invoice's chain. Previously this
     * always returned the TRON address regardless of {@code chain} — an invoice created
     * with chain=ETHEREUM/BASE/ARBITRUM/POLYGON silently got a Tron (base58) address as
     * its recipient, which no EVM wallet can send USDT to. See docs/ROADMAP_PROGRESS.md
     * "Пост-Фаза-10" for the write-up.
     */
    private String resolveDefaultDepositAddress(String chain) {
        return isEvmChain(chain) ? defaultEvmDepositAddress : defaultTronDepositAddress;
    }

    private static boolean isEvmChain(String chain) {
        if (chain == null) return false;
        switch (chain.toUpperCase()) {
            case "ETHEREUM":
            case "ERC20":
            case "BASE":
            case "ARBITRUM":
            case "POLYGON":
                return true;
            default:
                return false;
        }
    }

    @Transactional
    public CryptoInvoice createInvoice(Long userId, String chain, Long baseAmountUsdtMicro, String recipientAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (baseAmountUsdtMicro <= 0) {
            throw new IllegalArgumentException("Base amount must be positive");
        }

        // Reservation step: random k in [1..99], step 0.001 USDT (1000 micro-units) —
        // max +$0.099 on top of the requested amount. Window is ±0.0004 USDT (a
        // 0.0008 diameter, well under the 0.001 step), so non-overlapping windows
        // never depend on k's range — 99 concurrent PENDING invoices at the exact
        // same base amount (invoices expire after 2h) is already generous headroom
        // for early volume, and keeps the surcharge small enough not to look like a
        // pricing bug (previously up to +$0.999 on a $1 top-up, with zero UI
        // explanation of why — see docs/ROADMAP_PROGRESS.md "Пост-Фаза-10").
        int k = 1 + random.nextInt(99);
        int deltaStepMicro = k * 1000;
        long expectedAmountMicro = baseAmountUsdtMicro + deltaStepMicro;
        long toleranceMin = expectedAmountMicro - 400;
        long toleranceMax = expectedAmountMicro + 400;

        CryptoInvoice invoice = new CryptoInvoice();
        invoice.setUser(user);
        invoice.setChain(chain.toUpperCase());
        invoice.setToken("USDT");
        invoice.setBaseAmountUsdtMicro(baseAmountUsdtMicro);
        invoice.setDeltaStepMicro(deltaStepMicro);
        invoice.setExpectedAmountUsdtMicro(expectedAmountMicro);
        invoice.setToleranceMinMicro(toleranceMin);
        invoice.setToleranceMaxMicro(toleranceMax);
        invoice.setRecipientAddress(recipientAddress);
        invoice.setStatus("PENDING");
        invoice.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));

        return cryptoInvoiceRepository.save(invoice);
    }

    @Transactional
    public CryptoInvoice creditInvoicePayment(Long invoiceId, Long actualAmountMicro, String txHash) {
        CryptoInvoice invoice = cryptoInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));

        if (!"PENDING".equals(invoice.getStatus())) {
            log.warn("Invoice {} is not pending (status: {}), skipping", invoiceId, invoice.getStatus());
            return invoice;
        }

        // Guard against double-crediting if already claimed or credited
        if (txHash != null && balanceEntryRepository.existsByReferenceId(txHash)) {
            log.info("Transaction {} already credited via balance entry, marking invoice {} PAID without duplicate balance adjustment", txHash, invoiceId);
            invoice.setStatus("PAID");
            invoice.setPaidAt(Instant.now());
            invoice.setTxHash(txHash);
            invoice.setActualAmountUsdtMicro(actualAmountMicro);
            return cryptoInvoiceRepository.save(invoice);
        }

        invoice.setStatus("PAID");
        invoice.setPaidAt(Instant.now());
        invoice.setTxHash(txHash);
        invoice.setActualAmountUsdtMicro(actualAmountMicro);
        CryptoInvoice saved = cryptoInvoiceRepository.save(invoice);

        // Credit actual received amount to user balance
        User user = invoice.getUser();
        long newBalance = user.getBalanceUsdtMicro() + actualAmountMicro;
        user.setBalanceUsdtMicro(newBalance);
        userRepository.save(user);

        BalanceEntry entry = new BalanceEntry();
        entry.setUser(user);
        entry.setAmountUsdtMicro(actualAmountMicro);
        entry.setBalanceAfterMicro(newBalance);
        entry.setType("DEPOSIT");
        entry.setDescription("Deposit via " + invoice.getChain() + " (invoice #" + invoice.getId() + ")");
        entry.setReferenceId(txHash != null ? txHash : String.valueOf(invoice.getId()));
        balanceEntryRepository.save(entry);

        log.info("Successfully credited {} micro-USDT to user {} (new balance: {})", actualAmountMicro, user.getId(), newBalance);

        applyReferralRewards(user, actualAmountMicro, txHash != null ? txHash : String.valueOf(invoice.getId()));

        return saved;
    }

    public record ReferralRewardResult(long referrerBonusMicro, long refereeWelcomeBonusMicro) {}

    /**
     * Applies referral rewards for a deposit: a recurring {@value #REFERRER_BONUS_PERCENT}%
     * cut to the referrer, and (only on the referred user's first-ever deposit) a
     * one-time {@value #REFEREE_WELCOME_BONUS_PERCENT}% welcome bonus to the referred
     * user themselves. Shared by every deposit path (crypto invoices, Telegram Stars)
     * so referral rules don't drift between them.
     */
    @Transactional
    public ReferralRewardResult applyReferralRewards(User referredUser, long depositMicro, String referenceIdPrefix) {
        User referrer = referredUser.getReferredBy();
        if (referrer == null) {
            return new ReferralRewardResult(0, 0);
        }

        long referrerBonusMicro = depositMicro * REFERRER_BONUS_PERCENT / 100;
        if (referrerBonusMicro > 0) {
            creditBonus(referrer, referrerBonusMicro, "REFERRAL_BONUS",
                    "Referral bonus " + REFERRER_BONUS_PERCENT + "% from user #" + referredUser.getId(),
                    "ref_bonus:" + referenceIdPrefix);
        }

        boolean isFirstDeposit = balanceEntryRepository.countByUserIdAndType(referredUser.getId(), "DEPOSIT") <= 1;
        long refereeWelcomeBonusMicro = 0;
        if (isFirstDeposit) {
            refereeWelcomeBonusMicro = depositMicro * REFEREE_WELCOME_BONUS_PERCENT / 100;
            if (refereeWelcomeBonusMicro > 0) {
                creditBonus(referredUser, refereeWelcomeBonusMicro, "REFERRAL_WELCOME_BONUS",
                        "Referral welcome bonus " + REFEREE_WELCOME_BONUS_PERCENT + "% on first deposit",
                        "ref_welcome:" + referenceIdPrefix);
            }
        }

        return new ReferralRewardResult(referrerBonusMicro, refereeWelcomeBonusMicro);
    }

    private void creditBonus(User user, long amountMicro, String type, String description, String referenceId) {
        if (referenceId != null && balanceEntryRepository.existsByReferenceId(referenceId)) {
            log.info("Bonus {} already credited, skipping duplicate", referenceId);
            return;
        }
        long newBalance = user.getBalanceUsdtMicro() + amountMicro;
        user.setBalanceUsdtMicro(newBalance);
        userRepository.save(user);

        BalanceEntry entry = new BalanceEntry();
        entry.setUser(user);
        entry.setAmountUsdtMicro(amountMicro);
        entry.setBalanceAfterMicro(newBalance);
        entry.setType(type);
        entry.setDescription(description);
        entry.setReferenceId(referenceId);
        balanceEntryRepository.save(entry);
    }

    @Transactional
    public Subscription purchaseOrRenewSubscription(Long userId, String tariffId, boolean isAnnual) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Tariff tariff = tariffRepository.findById(tariffId)
                .orElseThrow(() -> new IllegalArgumentException("Tariff not found: " + tariffId));

        // The trial is a one-time onboarding taste, not a permanently renewable
        // free tier (docs/PLAN.md §2: "1 ГБ — это пробный период, а не бесплатный
        // тариф"). Without this check, price=0 skips the balance check below
        // entirely and a user could click "renew" on trial forever — any prior
        // subscription row for this tariff (active, expired, or cancelled) means
        // the trial was already used.
        if ("trial".equalsIgnoreCase(tariff.getId())
                && subscriptionRepository.existsByUserIdAndTariffId(userId, tariff.getId())) {
            throw new IllegalStateException("Trial has already been used on this account. Choose a paid plan to continue.");
        }

        Instant now = Instant.now();
        Optional<Subscription> existingSub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE");
        Subscription liveSub = existingSub.filter(s -> s.getCurrentPeriodEnd().isAfter(now)).orElse(null);

        // Switching starts the new plan at once and drops what is left of the
        // current one. For a cheaper plan that means paying to get less, so it
        // is refused here and done through scheduleNextTariff instead.
        if (liveSub != null && "trial".equalsIgnoreCase(tariff.getId()) && isPaid(liveSub.getTariff())) {
            throw new IllegalStateException("The trial can't replace a paid plan that is still running.");
        }
        if (liveSub != null && isDowngrade(liveSub.getTariff(), tariff)) {
            throw new IllegalStateException("A cheaper plan starts when the current one ends: schedule the change instead of buying it now.");
        }

        long price = isAnnual ? tariff.getAnnualPriceUsdtMicro() : tariff.getMonthlyPriceUsdtMicro();

        if (price > 0) {
            if (user.getBalanceUsdtMicro() < price) {
                throw new InsufficientBalanceException(price, user.getBalanceUsdtMicro());
            }

            long newBalance = user.getBalanceUsdtMicro() - price;
            user.setBalanceUsdtMicro(newBalance);
            userRepository.save(user);

            BalanceEntry entry = new BalanceEntry();
            entry.setUser(user);
            entry.setAmountUsdtMicro(-price);
            entry.setBalanceAfterMicro(newBalance);
            entry.setType("SUBSCRIPTION_DEBIT");
            entry.setDescription("Subscription " + tariff.getName() + " (" + (isAnnual ? "Annual" : "Monthly") + ")");
            balanceEntryRepository.save(entry);
        }

        Instant periodStart = now;
        Tariff carriedNextTariff = null;

        if (liveSub != null) {
            Subscription oldSub = liveSub;
            if (oldSub.getTariff() != null && oldSub.getTariff().getId().equalsIgnoreCase(tariffId)) {
                periodStart = oldSub.getCurrentPeriodEnd();
                // The new row queued after it renews from here on. Left on,
                // the old row would auto-renew too when it ends and charge the
                // same period a second time.
                carriedNextTariff = oldSub.getNextTariff();
                oldSub.setAutoRenew(false);
                oldSub.setNextTariff(null);
                subscriptionRepository.save(oldSub);
            } else {
                // Switching or upgrading tariff (e.g. from trial to pro): new plan takes effect immediately
                oldSub.setStatus("SUPERSEDED");
                oldSub.setAutoRenew(false);
                subscriptionRepository.save(oldSub);
                periodStart = now;
            }
        }

        boolean isTrial = "trial".equalsIgnoreCase(tariffId);
        // Trial has no real time limit — traffic quota is the only cap
        // (QuotaEnforcementTask#findQuotaExceededSubscriptions), independent
        // of clock time, so a P2P-relay-earned traffic credit (see
        // docs/research/P2P_RELAY_FEASIBILITY.md) isn't capped by a
        // meanwhile-expired trial period. See Subscription#hasNoExpiry.
        Instant periodEnd = isTrial
                ? periodStart.plus(NO_EXPIRY_DAYS, ChronoUnit.DAYS)
                : (isAnnual ? periodStart.plus(365, ChronoUnit.DAYS) : periodStart.plus(30, ChronoUnit.DAYS));
        boolean autoRenew = !isTrial;

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(tariff);
        sub.setStatus("ACTIVE");
        sub.setIsAnnual(isAnnual);
        sub.setAutoRenew(autoRenew);
        sub.setCurrentPeriodStart(periodStart);
        sub.setCurrentPeriodEnd(periodEnd);
        sub.setTrafficUsedBytes(0L);
        sub.setTrafficLimitBytes(tariff.getTrafficQuotaBytes());
        sub.setNextTariff(carriedNextTariff);

        return subscriptionRepository.save(sub);
    }

    /**
     * Schedules a move to a cheaper plan from the end of the current paid
     * period: nothing is charged now, auto-renewal buys {@code tariffId}
     * instead of the current plan. A null or the current plan's id cancels a
     * scheduled move.
     */
    @Transactional
    public Subscription scheduleNextTariff(Long userId, String tariffId) {
        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .filter(s -> s.getCurrentPeriodEnd().isAfter(Instant.now()))
                .orElseThrow(() -> new IllegalStateException("No active subscription to schedule a plan change for."));

        if (tariffId == null || tariffId.isBlank() || tariffId.equalsIgnoreCase(sub.getTariff().getId())) {
            sub.setNextTariff(null);
            return subscriptionRepository.save(sub);
        }

        Tariff next = tariffRepository.findById(tariffId)
                .orElseThrow(() -> new IllegalArgumentException("Tariff not found: " + tariffId));
        if (!isDowngrade(sub.getTariff(), next)) {
            throw new IllegalStateException("Only a move to a cheaper paid plan is scheduled; a pricier plan can be bought now.");
        }
        sub.setNextTariff(next);
        sub.setAutoRenew(true);
        return subscriptionRepository.save(sub);
    }

    /** A paid plan to a cheaper paid plan. The trial is never a target: it is one-shot. */
    private static boolean isDowngrade(Tariff current, Tariff target) {
        if (current == null || target == null || "trial".equalsIgnoreCase(target.getId())) {
            return false;
        }
        return isPaid(current) && isPaid(target) && monthlyPrice(target) < monthlyPrice(current);
    }

    private static boolean isPaid(Tariff tariff) {
        return tariff != null && monthlyPrice(tariff) > 0;
    }

    private static long monthlyPrice(Tariff tariff) {
        return tariff.getMonthlyPriceUsdtMicro() != null ? tariff.getMonthlyPriceUsdtMicro() : 0L;
    }

    @Transactional
    public BalanceEntry claimTransaction(Long userId, String chain, String txHash, Long amountMicro) {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("Transaction hash cannot be empty");
        }
        if (amountMicro == null || amountMicro <= 0) {
            throw new IllegalArgumentException("Declared amount must be greater than 0");
        }
        String cleanTxHash = txHash.trim();

        if (cryptoInvoiceRepository.existsByTxHash(cleanTxHash) || balanceEntryRepository.existsByReferenceId(cleanTxHash)) {
            throw new IllegalStateException("Transaction has already been claimed or credited: " + cleanTxHash);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        long newBalance = user.getBalanceUsdtMicro() + amountMicro;
        user.setBalanceUsdtMicro(newBalance);
        userRepository.save(user);

        BalanceEntry entry = new BalanceEntry();
        entry.setUser(user);
        entry.setAmountUsdtMicro(amountMicro);
        entry.setBalanceAfterMicro(newBalance);
        entry.setType("DEPOSIT");
        entry.setDescription("Claimed deposit (" + (chain != null ? chain : "CRYPTO") + "): " + cleanTxHash);
        entry.setReferenceId(cleanTxHash);

        log.info("User {} successfully claimed {} micro-USDT with tx {}", userId, amountMicro, cleanTxHash);
        BalanceEntry savedEntry = balanceEntryRepository.save(entry);

        // Mark any matching pending invoice for this user as PAID to prevent scanner from double-crediting
        List<CryptoInvoice> userInvoices = cryptoInvoiceRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (CryptoInvoice inv : userInvoices) {
            if ("PENDING".equals(inv.getStatus()) && (chain == null || chain.equalsIgnoreCase(inv.getChain()))) {
                inv.setStatus("PAID");
                inv.setPaidAt(Instant.now());
                inv.setTxHash(cleanTxHash);
                inv.setActualAmountUsdtMicro(amountMicro);
                cryptoInvoiceRepository.save(inv);
                log.info("Matched pending invoice {} to claimed tx {}, marked PAID", inv.getId(), cleanTxHash);
                break;
            }
        }

        return savedEntry;
    }
}
