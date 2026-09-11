package com.vpn.server.controller;

import com.vpn.server.dto.BalanceHistoryEntryResponse;
import com.vpn.server.entity.*;
import com.vpn.server.repository.BalanceEntryRepository;
import com.vpn.server.repository.CryptoInvoiceRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.AntiEnumerationService;
import com.vpn.server.service.BillingService;
import com.vpn.server.service.DeviceManagementService;
import com.vpn.server.service.InsufficientBalanceException;
import com.vpn.server.service.SubscriptionExportService;
import com.vpn.server.service.TelegramLinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
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
    private final BalanceEntryRepository balanceEntryRepository;
    private final SubscriptionExportService exportService;
    private final DeviceManagementService deviceManagementService;
    private final AntiEnumerationService antiEnumerationService;
    private final TelegramLinkService telegramLinkService;

    public UserController(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            TariffRepository tariffRepository,
            BillingService billingService,
            CryptoInvoiceRepository cryptoInvoiceRepository,
            BalanceEntryRepository balanceEntryRepository,
            SubscriptionExportService exportService,
            DeviceManagementService deviceManagementService,
            AntiEnumerationService antiEnumerationService,
            TelegramLinkService telegramLinkService
    ) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffRepository = tariffRepository;
        this.billingService = billingService;
        this.cryptoInvoiceRepository = cryptoInvoiceRepository;
        this.balanceEntryRepository = balanceEntryRepository;
        this.exportService = exportService;
        this.deviceManagementService = deviceManagementService;
        this.antiEnumerationService = antiEnumerationService;
        this.telegramLinkService = telegramLinkService;
    }

    /** Public origin of the web dashboard, used to build shareable referral links. */
    @Value("${vpn.public.web-base-url:https://vpn.struchev.site}")
    private String publicWebBaseUrl = "https://vpn.struchev.site";

    @Value("${vpn.telegram.bot-username:MyVpnBot}")
    private String telegramBotUsername = "MyVpnBot";

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
        // Ready-to-share referral links, built server-side so every client (web,
        // desktop, Android, Telegram Mini App) hands out the same URLs and none of
        // them has to hardcode a host or the bot username. The plain-web link is the
        // primary one — it works for anyone, including invitees who don't use
        // Telegram; the Telegram deep link stays as an extra channel. `?ref=CODE` is
        // what web/src/App.tsx reads to prefill the signup form's referral field.
        response.put("referralLink", buildReferralWebLink(user.getReferralCode()));
        response.put("referralTelegramLink", buildReferralTelegramLink(user.getReferralCode()));
        // Lets the web dashboard's Top Up modal tell whether Telegram Stars is
        // actually usable yet: a Stars payment can only be completed inside
        // Telegram (the Bot API can't push an invoice into an arbitrary web
        // session), so the client needs to know whether this account already
        // has a Telegram chat attached (see POST /telegram-link) before it can
        // offer the Stars denomination buttons instead of the "connect
        // Telegram first" prompt.
        response.put("telegramLinked", user.getTelegramId() != null);
        // No password/Telegram/Google credential — this is a no-signup
        // device-trial account (see DeviceAuthService), not one the user
        // consciously created. Clients use this to hide account-management UI
        // (devices, logout) that doesn't make sense for a profile that isn't
        // really "theirs" yet, and show a sign-in/register CTA instead — see
        // AuthController#upgrade and GuestMergeService for what happens when
        // they do.
        response.put("isGuest", user.getPasswordHash() == null && user.getTelegramId() == null && user.getGoogleSub() == null);
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

    private String buildReferralWebLink(String referralCode) {
        if (referralCode == null || referralCode.isBlank()) return "";
        String base = publicWebBaseUrl == null ? "" : publicWebBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/?ref=" + referralCode;
    }

    private String buildReferralTelegramLink(String referralCode) {
        if (referralCode == null || referralCode.isBlank()) return "";
        if (telegramBotUsername == null || telegramBotUsername.isBlank()) return "";
        return "https://t.me/" + telegramBotUsername.trim().replaceFirst("^@", "") + "?start=" + referralCode;
    }

    /**
     * Starts linking the caller's web account to a Telegram chat, so Telegram
     * Stars top-ups (which can only be completed inside Telegram -- the Bot
     * API can't push an invoice into an arbitrary web session) can be attached
     * to this account. Returns a short-lived opaque code and the deep link
     * that carries it; opening the link and hitting /start in the bot lets
     * TelegramBotService#handleAccountLinkStart (see there) attach the chat
     * that opened it to this user, once the user taps it.
     */
    @PostMapping("/telegram-link")
    public ResponseEntity<?> createTelegramLink(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String code = telegramLinkService.createPendingLink(userId);
        return ResponseEntity.ok(Map.of(
                "code", code,
                "deepLink", buildTelegramLinkDeepLink(code)
        ));
    }

    private String buildTelegramLinkDeepLink(String code) {
        String username = telegramBotUsername == null ? "" : telegramBotUsername.trim().replaceFirst("^@", "");
        return "https://t.me/" + username + "?start=link_" + code;
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
        } catch (InsufficientBalanceException e) {
            // Structured shortfall instead of the raw "Required: 5000000, current: 0"
            // message (which is still set as `error` for any caller that only reads
            // that field, e.g. a stale client build) — the web dashboard uses the
            // *Micro fields to render its own localized "top up $X.XX more" copy
            // rather than showing this sentence verbatim. See UX_REVIEW.md Quick Win #1.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "INSUFFICIENT_BALANCE");
            body.put("requiredUsdtMicro", e.getRequiredUsdtMicro());
            body.put("currentUsdtMicro", e.getCurrentUsdtMicro());
            body.put("shortfallUsdtMicro", e.getShortfallUsdtMicro());
            return ResponseEntity.badRequest().body(body);
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

        Object amountObj = req.get("amountMicro");
        if (amountObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "amountMicro is required"));
        }
        Long amountMicro;
        try {
            amountMicro = Long.valueOf(amountObj.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid amountMicro format"));
        }

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

    /**
     * The user's own balance-ledger history -- every actual fund movement
     * (deposits, subscription debits, referral bonuses, refunds, manual
     * adjustments), not just crypto deposit invoices like GET /invoices above.
     * The web dashboard merges this with /invoices into one "billing history"
     * card. Capped rather than paginated: this codebase has no list-endpoint
     * pagination convention to follow, and 100 rows is already far more than
     * the dashboard displays at once.
     */
    @GetMapping("/balance-history")
    public ResponseEntity<List<BalanceHistoryEntryResponse>> getBalanceHistory(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<BalanceHistoryEntryResponse> history = balanceEntryRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .limit(100)
                .map(BalanceHistoryEntryResponse::from)
                .toList();
        return ResponseEntity.ok(history);
    }

    /**
     * @param region optional (e.g. "nl-ams", see GET /regions) — restricts the
     *               returned links to that region's ONLINE nodes; falls back to
     *               every online node (today's "auto" behavior) if the region
     *               has none right now, see SubscriptionExportService#
     *               exportVlessLinksForOwnApp(Long, String). Omitted entirely,
     *               this is the original unscoped response shape (no
     *               requestedRegion/requestedRegionAvailable fields) so existing
     *               clients that don't yet know about regions are unaffected.
     */
    @GetMapping("/subscription/links")
    public ResponseEntity<?> getSubscriptionLinks(
            Authentication auth, HttpServletRequest request,
            @RequestParam(required = false) String region) {
        Long userId = (Long) auth.getPrincipal();
        antiEnumerationService.recordAccessAndEnforce(userId, request.getRemoteAddr());
        try {
            if (region != null && !region.isBlank()) {
                SubscriptionExportService.RegionScopedLinks result = exportService.exportVlessLinksForOwnApp(userId, region);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("count", result.links().size());
                body.put("links", result.links());
                body.put("requestedRegion", region);
                body.put("requestedRegionAvailable", result.requestedRegionAvailable());
                return ResponseEntity.ok(body);
            }
            List<String> links = exportService.exportVlessLinksForOwnApp(userId);
            return ResponseEntity.ok(Map.of(
                    "count", links.size(),
                    "links", links
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Lists regions with at least one ONLINE node this user's subscription can
     * reach, each with a rough load indicator — see SubscriptionExportService#
     * getAvailableRegions for the heuristic. Powers the desktop/Android region
     * picker (product ask: choose a connection region and see its congestion).
     */
    @GetMapping("/regions")
    public ResponseEntity<?> getRegions(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        try {
            List<SubscriptionExportService.RegionSummary> regions = exportService.getAvailableRegions(userId);
            return ResponseEntity.ok(Map.of("regions", regions));
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

    /**
     * Called by native clients on a successful connect for the device they
     * already registered (locally-persisted deviceId) — keeps it counting as
     * recently active (DeviceManagementService.DEVICE_ACTIVE_WINDOW_DAYS)
     * without any user action. A 404 here means the client should fall back
     * to POST /devices to register fresh (e.g. it was revoked elsewhere).
     */
    @PostMapping("/devices/{deviceId}/touch")
    public ResponseEntity<?> touchDevice(
            Authentication auth,
            @PathVariable Long deviceId) {
        Long userId = (Long) auth.getPrincipal();
        try {
            deviceManagementService.touchDevice(userId, deviceId);
            return ResponseEntity.ok(Map.of("status", "OK"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
