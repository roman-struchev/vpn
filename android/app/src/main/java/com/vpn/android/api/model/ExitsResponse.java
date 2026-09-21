package com.vpn.android.api.model;

import java.util.List;

/** Mirrors GET /api/v1/user/p2p/exits. Same shape as a relay listing — the peers are the same devices, used the other way. */
public class ExitsResponse {
    public List<RelayInfo> exits;
}
