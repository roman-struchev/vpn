package com.vpn.server.service;

/**
 * Thrown by {@link BillingService#purchaseOrRenewSubscription} when the user's balance
 * doesn't cover the tariff's price. Carries the raw micro-USDT figures as structured
 * fields (rather than only a formatted message) so callers like UserController can hand
 * the client real numbers to localize/format themselves, instead of a client having to
 * parse them back out of an English sentence (see UX_REVIEW.md Quick Win #1 — a raw
 * "Insufficient balance. Required: 5000000, current: 0" string used to reach the web
 * dashboard's error banner verbatim).
 *
 * <p>Extends {@link IllegalStateException} so any existing catch of
 * {@code IllegalStateException} (the shared convention for business-rule failures across
 * this service/controller) keeps working unchanged for callers that don't special-case
 * this exception.
 */
public class InsufficientBalanceException extends IllegalStateException {

    private final long requiredUsdtMicro;
    private final long currentUsdtMicro;

    public InsufficientBalanceException(long requiredUsdtMicro, long currentUsdtMicro) {
        super("Insufficient balance. Required: " + requiredUsdtMicro + ", current: " + currentUsdtMicro);
        this.requiredUsdtMicro = requiredUsdtMicro;
        this.currentUsdtMicro = currentUsdtMicro;
    }

    public long getRequiredUsdtMicro() {
        return requiredUsdtMicro;
    }

    public long getCurrentUsdtMicro() {
        return currentUsdtMicro;
    }

    public long getShortfallUsdtMicro() {
        return requiredUsdtMicro - currentUsdtMicro;
    }
}
