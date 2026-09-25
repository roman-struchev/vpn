package com.vpn.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @JsonIgnore: not serialized by any controller today (UserController.getProfile
    // builds its own Map instead of returning the entity) — see Device.user for why
    // this is kept consistent pre-emptively rather than only fixed where it already bit.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tariff_id", nullable = false)
    private Tariff tariff;

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE"; // ACTIVE, EXPIRED, CANCELLED

    @Column(name = "is_annual", nullable = false)
    private Boolean isAnnual = false;

    @Column(name = "auto_renew", nullable = false)
    private Boolean autoRenew = true;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Column(name = "traffic_used_bytes", nullable = false)
    private Long trafficUsedBytes = 0L;

    @Column(name = "traffic_limit_bytes", nullable = false)
    private Long trafficLimitBytes;

    // Admin-granted temporary plan change (e.g. goodwill/compensation), separate
    // from the real billing tariff above — auto-renew and invoicing still use
    // `tariff`. While overrideExpiresAt is in the future, getEffectiveTariff()
    // returns this instead; QuotaEnforcementTask clears all three columns and
    // restores trafficLimitBytes from the snapshot once it's past.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "override_tariff_id")
    private Tariff overrideTariff;

    @Column(name = "override_expires_at")
    private Instant overrideExpiresAt;

    @Column(name = "override_previous_traffic_limit_bytes")
    private Long overridePreviousTrafficLimitBytes;

    // Plan to auto-renew into when this period ends, set by a scheduled move
    // to a cheaper plan (BillingService#scheduleNextTariff). Null = renew the
    // same plan.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "next_tariff_id")
    private Tariff nextTariff;

    // When the "balance won't cover the renewal" heads-up went out for this
    // period (RenewalNotifier). Null = not yet.
    @Column(name = "renewal_reminder_sent_at")
    private Instant renewalReminderSentAt;

    // Annual plans only: when the used-traffic counter next resets to zero,
    // so "N GB per month" holds for all twelve months (QuotaEnforcementTask).
    // Null for monthly plans, whose renewal is the reset.
    @Column(name = "traffic_reset_at")
    private Instant trafficResetAt;

    // When the "90% of traffic used" heads-up went out this traffic period.
    @Column(name = "traffic_warning_sent_at")
    private Instant trafficWarningSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Subscription() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Tariff getTariff() { return tariff; }
    public void setTariff(Tariff tariff) { this.tariff = tariff; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getIsAnnual() { return isAnnual; }
    public void setIsAnnual(Boolean isAnnual) { this.isAnnual = isAnnual; }

    public Boolean getAutoRenew() { return autoRenew; }
    public void setAutoRenew(Boolean autoRenew) { this.autoRenew = autoRenew; }

    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(Instant currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }

    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }

    public Long getTrafficUsedBytes() { return trafficUsedBytes; }
    public void setTrafficUsedBytes(Long trafficUsedBytes) { this.trafficUsedBytes = trafficUsedBytes; }

    public Long getTrafficLimitBytes() { return trafficLimitBytes; }
    public void setTrafficLimitBytes(Long trafficLimitBytes) { this.trafficLimitBytes = trafficLimitBytes; }

    public Tariff getOverrideTariff() { return overrideTariff; }
    public void setOverrideTariff(Tariff overrideTariff) { this.overrideTariff = overrideTariff; }

    public Instant getOverrideExpiresAt() { return overrideExpiresAt; }
    public void setOverrideExpiresAt(Instant overrideExpiresAt) { this.overrideExpiresAt = overrideExpiresAt; }

    public Long getOverridePreviousTrafficLimitBytes() { return overridePreviousTrafficLimitBytes; }
    public void setOverridePreviousTrafficLimitBytes(Long bytes) { this.overridePreviousTrafficLimitBytes = bytes; }

    public Tariff getNextTariff() { return nextTariff; }
    public void setNextTariff(Tariff nextTariff) { this.nextTariff = nextTariff; }

    /** The plan auto-renewal should buy when this period ends. */
    public Tariff getRenewalTariff() {
        return nextTariff != null ? nextTariff : tariff;
    }

    /** What auto-renewal will charge the balance when this period ends. */
    public long getRenewalPriceUsdtMicro() {
        Tariff t = getRenewalTariff();
        Long price = Boolean.TRUE.equals(isAnnual) ? t.getAnnualPriceUsdtMicro() : t.getMonthlyPriceUsdtMicro();
        return price != null ? price : 0L;
    }

    public Instant getTrafficResetAt() { return trafficResetAt; }
    public void setTrafficResetAt(Instant trafficResetAt) { this.trafficResetAt = trafficResetAt; }

    public Instant getTrafficWarningSentAt() { return trafficWarningSentAt; }
    public void setTrafficWarningSentAt(Instant trafficWarningSentAt) { this.trafficWarningSentAt = trafficWarningSentAt; }

    public Instant getRenewalReminderSentAt() { return renewalReminderSentAt; }
    public void setRenewalReminderSentAt(Instant renewalReminderSentAt) { this.renewalReminderSentAt = renewalReminderSentAt; }

    /** The tariff that should actually govern device limits/server pool/plan gating right now. */
    public Tariff getEffectiveTariff() {
        if (overrideTariff != null && overrideExpiresAt != null && overrideExpiresAt.isAfter(Instant.now())) {
            return overrideTariff;
        }
        return tariff;
    }

    /**
     * Whether neither the real billing period nor an active admin-granted
     * override still covers "now" — an active override keeps access alive
     * past the real currentPeriodEnd (e.g. a temporary tariff grant meant to
     * outlast a trial's real period), so this must never be replaced with a
     * bare {@code currentPeriodEnd.isBefore(now)} check. Used both for
     * per-request access checks (SubscriptionExportService,
     * DeviceManagementService, TelegramBotService) and by
     * QuotaEnforcementTask to decide whether to flip status to EXPIRED.
     */
    // Trial tariffs no longer carry a real time limit (BillingService/
    // DeviceAuthService/TelegramAuthService set currentPeriodEnd 100 years
    // out for trial instead of 3 days) — the traffic quota is the only real
    // limit now (QuotaEnforcementTask#findQuotaExceededSubscriptions already
    // enforces that independently of time). A literal ~100-years-out date is
    // meaningless to show a user, so callers use this to render "no time
    // limit" instead of the sentinel value itself.
    private static final long NO_EXPIRY_THRESHOLD_DAYS = 3650; // 10 years — far beyond any real paid period

    public boolean hasNoExpiry() {
        return currentPeriodEnd.isAfter(Instant.now().plus(NO_EXPIRY_THRESHOLD_DAYS, java.time.temporal.ChronoUnit.DAYS));
    }

    public boolean isExpired() {
        Instant now = Instant.now();
        if (overrideTariff != null && overrideExpiresAt != null && overrideExpiresAt.isAfter(now)) {
            return false;
        }
        return currentPeriodEnd.isBefore(now);
    }

    /**
     * When to actually tell the user their access ends — the real
     * currentPeriodEnd, except while an active override outlasts it (an
     * override is meant to extend access, never to cut a still-running real
     * period short, so this is a max, not a plain override).
     */
    /** When a still-running admin-granted override ends, else null. */
    public Instant getActiveOverrideExpiresAt() {
        if (overrideTariff != null && overrideExpiresAt != null && overrideExpiresAt.isAfter(Instant.now())) {
            return overrideExpiresAt;
        }
        return null;
    }

    public Instant getEffectiveExpiresAt() {
        if (overrideTariff != null && overrideExpiresAt != null && overrideExpiresAt.isAfter(currentPeriodEnd)) {
            return overrideExpiresAt;
        }
        return currentPeriodEnd;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void preUpdate() { this.updatedAt = Instant.now(); }
}
