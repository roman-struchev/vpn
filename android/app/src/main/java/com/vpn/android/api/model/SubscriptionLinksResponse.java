package com.vpn.android.api.model;

import java.util.List;

/** Mirrors GET /api/v1/user/subscription/links. */
public class SubscriptionLinksResponse {
    public int count;
    public List<String> links;
    /** Present only when a region was requested — see ApiClient#getSubscriptionLinks(String). */
    public String requestedRegion;
    /** Null when no region was requested; false means it had no online node and the server fell back to all nodes. */
    public Boolean requestedRegionAvailable;
}
