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
import java.util.Optional;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private final SecureRandom random = new SecureRandom();

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

        // Reservation step: random k in [1..999], step 0.001 USDT (1000 micro-units)
        // Window: expected ± 0.0004 USDT (400 micro-units)
        int k = 1 + random.nextInt(999);
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
        return saved;
    }

    @Transactional
    public Subscription purchaseOrRenewSubscription(Long userId, String tariffId, boolean isAnnual) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Tariff tariff = tariffRepository.findById(tariffId)
                .orElseThrow(() -> new IllegalArgumentException("Tariff not found: " + tariffId));

        long price = isAnnual ? tariff.getAnnualPriceUsdtMicro() : tariff.getMonthlyPriceUsdtMicro();

        if (price > 0) {
            if (user.getBalanceUsdtMicro() < price) {
                throw new IllegalStateException("Insufficient balance. Required: " + price + ", current: " + user.getBalanceUsdtMicro());
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

        Instant now = Instant.now();
        Instant periodStart = now;

        Optional<Subscription> existingSub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE");
        if (existingSub.isPresent() && existingSub.get().getCurrentPeriodEnd().isAfter(now)) {
            periodStart = existingSub.get().getCurrentPeriodEnd();
        }

        Instant periodEnd = isAnnual ? periodStart.plus(365, ChronoUnit.DAYS) : periodStart.plus(30, ChronoUnit.DAYS);

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(tariff);
        sub.setStatus("ACTIVE");
        sub.setIsAnnual(isAnnual);
        sub.setAutoRenew(true);
        sub.setCurrentPeriodStart(periodStart);
        sub.setCurrentPeriodEnd(periodEnd);
        sub.setTrafficUsedBytes(0L);
        sub.setTrafficLimitBytes(tariff.getTrafficQuotaBytes());

        return subscriptionRepository.save(sub);
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
        return balanceEntryRepository.save(entry);
    }
}
