package com.vpn.server.service;

import com.vpn.server.entity.CryptoInvoice;
import com.vpn.server.repository.CryptoInvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class BlockchainPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BlockchainPaymentService.class);

    private final CryptoInvoiceRepository cryptoInvoiceRepository;
    private final BillingService billingService;

    @Value("${vpn.crypto.tron-deposit-address:TXxxDefaultDepositAddressTRC20}")
    private String defaultTronDepositAddress;

    public BlockchainPaymentService(
            CryptoInvoiceRepository cryptoInvoiceRepository,
            BillingService billingService
    ) {
        this.cryptoInvoiceRepository = cryptoInvoiceRepository;
        this.billingService = billingService;
    }

    /**
     * Reconciles an incoming blockchain deposit against open invoices within the tolerance window.
     *
     * @param chain Blockchain identifier (e.g., "TRC20", "ERC20", "BASE")
     * @param depositAddress The company receiving address
     * @param amountMicro Actual transferred amount in micro-USDT (1 USDT = 1,000,000 micro-units)
     * @param txHash Blockchain transaction hash
     * @return The credited invoice, or null if no matching pending invoice was found
     */
    @Transactional
    public CryptoInvoice processIncomingDeposit(String chain, String depositAddress, Long amountMicro, String txHash) {
        Instant now = Instant.now();
        List<CryptoInvoice> matched = cryptoInvoiceRepository.findPendingMatchingInvoice(
                chain, depositAddress, amountMicro, now
        );

        if (matched.isEmpty()) {
            log.warn("Unmatched deposit on chain {}: amount={} micro-USDT, txHash={}, address={}",
                    chain, amountMicro, txHash, depositAddress);
            return null;
        }

        // Pick the first matched invoice (random delta step guarantee ensures uniqueness)
        CryptoInvoice invoice = matched.get(0);
        log.info("Matching deposit found for invoice {}: expected={}, received={}, delta={}",
                invoice.getId(), invoice.getExpectedAmountUsdtMicro(), amountMicro,
                amountMicro - invoice.getExpectedAmountUsdtMicro());

        return billingService.creditInvoicePayment(invoice.getId(), amountMicro, txHash);
    }

    public String getDefaultTronDepositAddress() {
        return defaultTronDepositAddress;
    }
}
