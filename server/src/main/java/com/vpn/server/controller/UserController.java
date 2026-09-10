package com.vpn.server.controller;

import com.vpn.server.entity.*;
import com.vpn.server.repository.CryptoInvoiceRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.AntiEnumerationService;
import com.vpn.server.service.BillingService;
import com.vpn.server.service.DeviceManagementService;
import com.vpn.server.service.SubscriptionExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
    private final DeviceManagementService deviceManagementService;
    private final AntiEnumerationService antiEnumerationService;

    public UserController(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            TariffRepository tariffRepository,
            BillingService billingService,
            CryptoInvoiceRepository cryptoInvoiceRepository,
            SubscriptionExportService exportService,
            DeviceManagementService deviceManagementService,
            AntiEnumerationService antiEnumerationService
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffRepository = tariffRepository;
        this.billingService = billingService;
        this.cryptoInvoiceRepository = cryptoInvoiceRepository;
        this.exportService = exportService;
        this.deviceManagementService = deviceManagementService;
        this.antiEnumerationService = antiEnumerationService;
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userRepository.findById(userId).orElseThrow();

        Optional<Subscription> sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE");

        // Map.of() can't hold a null value, so an absent subscription used to
        // serialize as "subscription":{} instead of null. Every client reads
        // this field with a plain truthiness check (e.g. DashboardView.tsx:
        // `const sub = user.subscription; sub ? sub.tariffId.toUpperCase() : ...`)
        // where {} is truthy but has no tariffId — every user without an active
        // subscription got a hard crash (blank dashboard, no error boundary) on
        // the web client. LinkedHashMap allows null so this now serializes as a
        // real JSON null, which every truthiness check already handles correctly.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("email", user.getEmail() != null ? user.getEmail() : "");
        response.put("role", user.getRole());
        response.put("balanceUsdtMicro", user.getBalanceUsdtMicro());
        response.put("referralCode", user.getReferralCode() != null ? user.getReferralCode() : "");
        response.put("hasActiveSubscription", sub.isPresent());
        // So the client can hide/disable the trial tariff's action button once
        // it's been used — the trial is one-shot (see BillingService.
        // purchaseOrRenewSubscription), but the client has no other way to know
        // that from just the *current* subscription (which may by now be a
        // different, paid tariff, or none at all if the trial expired).
        response.put("hasUsedTrial", subscriptionRepository.existsByUserIdAndTariffId(userId, "trial"));
        response.put("subscription", sub.map(s -> Map.of(
                "id", s.getId(),
                "tariffId", s.getTariff().getId(),
                "trafficUsedBytes", s.getTrafficUsedBytes(),
                "trafficLimitBytes", s.getTrafficLimitBytes(),
                "expiresAt", s.getCurrentPeriodEnd().toString()
        )).orElse(null));

        return ResponseEntity.ok(response);
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
        // Field name matches CryptoInvoice.baseAmountUsdtMicro and what
        // web/src/api.ts createCryptoInvoice() actually sends. Was "amountMicro"
        // here — a real key-name mismatch with the client that made every
        // top-up-invoice request from the website NPE (uncaught, surfaced as a
        // generic 403 to the browser) — see docs/ROADMAP_PROGRESS.md "Пост-Фаза-10".
        Object rawAmount = req.get("baseAmountUsdtMicro");
        if (rawAmount == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "baseAmountUsdtMicro is required"));
        }
        Long baseAmountMicro = Long.valueOf(rawAmount.toString());
        String chain = (String) req.getOrDefault("chain", "TRON");

        CryptoInvoice invoice = billingService.createInvoice(userId, chain, baseAmountMicro);

        return ResponseEntity.ok(Map.of(
                "invoiceId", invoice.getId(),
                "chain", invoice.getChain(),
                "recipientAddress", invoice.getRecipientAddress(),
                "expectedAmountUsdtMicro", invoice.getExpectedAmountUsdtMicro(),
                "expectedAmountUsdt", invoice.getExpectedAmountUsdtMicro() / 1_000_000.0,
                "deltaStepMicro", invoice.getDeltaStepMicro(),
                // Were missing entirely — DashboardView.tsx's "Acceptable window"
                // line divided undefined/1_000_000 and rendered "NaN - NaN".
                "toleranceMinMicro", invoice.getToleranceMinMicro(),
                "toleranceMaxMicro", invoice.getToleranceMaxMicro(),
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

    @PostMapping("/billing/claim-tx")
    public ResponseEntity<?> claimTransaction(
            Authentication auth,
            @RequestBody Map<String, Object> req) {
        Long userId = (Long) auth.getPrincipal();
        String chain = (String) req.getOrDefault("chain", "TRON");
        String txHash = (String) req.get("txHash");
        Long amountMicro = Long.valueOf(req.get("amountMicro").toString());

        try {
            BalanceEntry entry = billingService.claimTransaction(userId, chain, txHash, amountMicro);
            return ResponseEntity.ok(Map.of(
                    "status", "CLAIMED",
                    "amountMicro", entry.getAmountUsdtMicro(),
                    "balanceAfterMicro", entry.getBalanceAfterMicro(),
                    "txHash", entry.getReferenceId()
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
    public ResponseEntity<?> getSubscriptionLinks(Authentication auth, HttpServletRequest request) {
        Long userId = (Long) auth.getPrincipal();
        antiEnumerationService.recordAccessAndEnforce(userId, request.getRemoteAddr());
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

    @GetMapping("/devices")
    public ResponseEntity<List<Device>> getDevices(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(deviceManagementService.getUserDevices(userId));
    }

    @PostMapping("/devices")
    public ResponseEntity<?> addDevice(
            Authentication auth,
            @RequestBody Map<String, String> req) {
        Long userId = (Long) auth.getPrincipal();
        String name = req.get("deviceName");
        String platform = req.get("platform");
        try {
            Device device = deviceManagementService.addDevice(userId, name, platform);
            return ResponseEntity.ok(Map.of(
                    "status", "CREATED",
                    "deviceId", device.getId(),
                    "deviceName", device.getDeviceName(),
                    "platform", device.getPlatform()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<?> deleteDevice(
            Authentication auth,
            @PathVariable Long deviceId) {
        Long userId = (Long) auth.getPrincipal();
        try {
            deviceManagementService.deleteDevice(userId, deviceId);
            return ResponseEntity.ok(Map.of("status", "REVOKED", "deviceId", deviceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
