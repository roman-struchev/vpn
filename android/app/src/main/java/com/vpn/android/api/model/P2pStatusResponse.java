package com.vpn.android.api.model;

/** Mirrors GET /api/v1/user/p2p/status. */
public class P2pStatusResponse {
    public boolean termsAccepted;
    public boolean isGuest;
    public long bytesCreditedToday;
    public long dailyCapBytes;
    public long remainingCapBytesToday;
}
