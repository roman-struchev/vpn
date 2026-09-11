package com.vpn.server.dto;

public record GoogleAuthRequest(
        String idToken,
        String referralCode,
        /**
         * This install's stable device UUID (see DeviceAuthService), sent so
         * signing in with Google can absorb any guest/trial account still
         * sitting on this device instead of orphaning it — see
         * GuestMergeService. Optional: null/blank for callers with no local
         * device UUID (e.g. web), which just skips the merge.
         */
        String deviceUuid
) {
    public GoogleAuthRequest(String idToken, String referralCode) {
        this(idToken, referralCode, null);
    }
}
