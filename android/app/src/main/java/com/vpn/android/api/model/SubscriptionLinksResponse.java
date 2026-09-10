package com.vpn.android.api.model;

import java.util.List;

/** Mirrors GET /api/v1/user/subscription/links. */
public class SubscriptionLinksResponse {
    public int count;
    public List<String> links;
}
