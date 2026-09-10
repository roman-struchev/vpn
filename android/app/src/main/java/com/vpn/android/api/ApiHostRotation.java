package com.vpn.android.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 10 hardening ("Пул резервных доменов для API сервера"): if the
 * primary API domain is blocked/poisoned, fall through to the next
 * configured backup domain rather than failing outright. Pure, in-memory,
 * per-process — no persistence of "which host worked last" across app
 * restarts, which is an acceptable simplification for this MVP pass.
 */
public class ApiHostRotation {

    private final List<String> hosts;
    private int currentIndex = 0;

    public ApiHostRotation(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("At least one API host is required");
        }
        this.hosts = new ArrayList<>(hosts);
    }

    public String current() {
        return hosts.get(currentIndex);
    }

    public int size() {
        return hosts.size();
    }

    /** Advances to the next host (wrapping around) and returns it. */
    public String advance() {
        currentIndex = (currentIndex + 1) % hosts.size();
        return current();
    }
}
