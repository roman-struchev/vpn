package com.vpn.server.controller;

import com.vpn.server.entity.*;
import com.vpn.server.repository.CryptoInvoiceRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.BillingService;
import com.vpn.server.service.SubscriptionExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TariffRepository tariffRepository;
    private final BillingService billingService;
    private final CryptoInvoiceRepository cryptoInvoiceRepository;
    private final SubscriptionExportService exportService;

    public UserController(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            TariffRepository tariffRepository,
            BillingService billingService,
            CryptoInvoiceRepository cryptoInvoiceRepository,
            SubscriptionExportService exportService
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffRepository = tariffRepository;
        this.billingService = billingService;
        this.cryptoInvoiceRepository = cryptoInvoiceRepository;
        this.exportService = exportService;
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();

        Optional<Subscription> sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE");

        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "email", user.getEmail() != null ? user.getEmail() : "",
                "role", user.getRole(),
                "balanceUsdtMicro", user.getBalanceUsdtMicro(),
                "referralCode", user.getReferralCode() != null ? user.getReferralCode() : "",
                "hasActiveSubscription", sub.isPresent(),
                "subscription", sub.map(s -> Map.of(
                        "id", s.getId(),
                        "tariffId", s.getTariff().getId(),
                        "trafficUsedBytes", s.getTrafficUsedBytes(),
                        "trafficLimitBytes", s.getTrafficLimitBytes(),
                        "expiresAt", s.getCurrentPeriodEnd().toString()
                )).orElse(Map.of())
        ));
    }

    @GetMapping("/tariffs")
    public ResponseEntity<List<Tariff>> getTariffs() {
        return ResponseEntity.ok(tariffRepository.findByIsActiveTrue());
    }

    @PostMapping("/billing/invoice")
    public ResponseEntity<?> createInvoice(
            Authentication auth,
            @RequestBody Map<String, Object> req) {
        Long userId = (Long) auth.getPrincipal();
        Long baseAmountMicro = Long.valueOf(req.get("amountMicro").toString());
        String chain = (String) req.getOrDefault("chain", "TRON");

        CryptoInvoice invoice = billingService.createInvoice(userId, chain, baseAmountMicro);

        return ResponseEntity.ok(Map.of(
                "invoiceId", invoice.getId(),
                "chain", invoice.getChain(),
                "recipientAddress", invoice.getRecipientAddress(),
                "expectedAmountUsdtMicro", invoice.getExpectedAmountUsdtMicro(),
                "expectedAmountUsdt", invoice.getExpectedAmountUsdtMicro() / 1_000_000.0,
                "deltaStepMicro", invoice.getDeltaStepMicro(),
                "status", invoice.getStatus(),
                "expiresAt", invoice.getExpiresAt().toString()
        ));
    }

    @PostMapping("/billing/purchase")
    public ResponseEntity<?> purchaseSubscription(
            Authentication auth,
            @RequestBody Map<String, Object> req) {
        Long userId = (Long) auth.getPrincipal();
        String tariffId = (String) req.get("tariffId");
        boolean isAnnual = Boolean.parseBoolean(String.valueOf(req.getOrDefault("isAnnual", false)));

        try {
            Subscription sub = billingService.purchaseOrRenewSubscription(userId, tariffId, isAnnual);
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "subscriptionId", sub.getId(),
                    "tariffId", sub.getTariff().getId(),
                    "expiresAt", sub.getCurrentPeriodEnd().toString(),
                    "trafficLimitBytes", sub.getTrafficLimitBytes()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/invoices")
    public ResponseEntity<List<CryptoInvoice>> getUserInvoices(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(cryptoInvoiceRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @GetMapping("/subscription/links")
    public ResponseEntity<?> getSubscriptionLinks(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        try {
            List<String> links = exportService.exportVlessLinks(userId);
            return ResponseEntity.ok(Map.of(
                    "count", links.size(),
                    "links", links
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
