package com.vpn.android.api.model;

/** Mirrors the Map body of GET /api/v1/user/profile (UserController#getProfile). */
public class UserProfile {
    public long id;
    public String email;
    public String role;
    public long balanceUsdtMicro;
    public String referralCode;
    public boolean hasActiveSubscription;
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
}
