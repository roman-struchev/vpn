package com.vpn.server.dto;

public record LoginRequest(
        String email,
        String password,
        /**
         * This install's stable device UUID (see DeviceAuthService), sent so a
         * login into an existing account can absorb any guest/trial account
         * still sitting on this device instead of orphaning it — see
         * GuestMergeService. Optional: null/blank for callers with no local
         * device UUID (e.g. web), which just skips the merge.
         */
        String deviceUuid
) {
    public LoginRequest(String email, String password) {
        this(email, password, null);
    }
}
