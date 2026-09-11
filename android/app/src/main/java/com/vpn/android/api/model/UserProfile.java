package com.vpn.android.api.model;

/** Mirrors the Map body of GET /api/v1/user/profile (UserController#getProfile). */
public class UserProfile {
    public long id;
    public String email;
    public String role;
    public long balanceUsdtMicro;
    public String referralCode;
    /** Ready-to-share plain web link built by the server ({@code <site>/?ref=CODE}). */
    public String referralLink;
    public String referralTelegramLink;
    public boolean hasActiveSubscription;
    public boolean isGuest;
    public SubscriptionInfo subscription;

    public static class SubscriptionInfo {
        public long id;
        public String tariffId;
        public long trafficUsedBytes;
        public long trafficLimitBytes;
        public String expiresAt;
    }

    public double balanceUsdt() {
        return balanceUsdtMicro / 1_000_000.0;
    }

    /**
     * The link to actually hand to a friend. A bare referral code is useless on its own
     * (the invitee would have to be told where to type it), and a Telegram deep link
     * excludes anyone not using Telegram — so this is a plain https URL carrying
     * {@code ?ref=CODE}, which the web signup form reads. Composed locally only as a
     * fallback for servers that predate the {@code referralLink} field.
     */
    public String shareableReferralLink(String webBaseUrl) {
        if (referralLink != null && !referralLink.isBlank()) return referralLink;
        if (referralCode == null || referralCode.isBlank()) return "";
        String base = webBaseUrl == null ? "" : webBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/?ref=" + referralCode;
    }
}
