package com.vpn.android.api.model;

/**
 * One entry of GET /api/v1/user/tariffs — the catalogue the plan card reads to
 * turn the bare {@code tariffId} on a subscription into something a user
 * recognises ("Pro", "$2/mo, up to 5 devices") instead of an internal id.
 */
public class TariffInfo {
    public String id;
    public String name;
    public long monthlyPriceUsdtMicro;
    public long annualPriceUsdtMicro;
    public Integer maxDevices;
    public Long trafficQuotaBytes;
    public boolean isActive;

    public double monthlyPriceUsdt() {
        return monthlyPriceUsdtMicro / 1_000_000.0;
    }
}
