package com.vpn.android.vpn.state;

import com.vpn.android.api.ApiException;

import java.io.IOException;
import java.util.Locale;

/**
 * Why a connection attempt ended in ERROR, so the screen can say something
 * more useful than "Error" and offer the one action that helps. Pure Java,
 * like the rest of this package, so it is covered by plain JUnit.
 */
public enum FailureReason {
    /** No active plan — expired, out of traffic, or never had one. Fix: get a plan. */
    NO_SUBSCRIPTION,
    /** The sign-in is no longer valid and could not be renewed. Fix: sign in. */
    SESSION_EXPIRED,
    /** The account has no server it can use right now. Fix: try later / another region. */
    NO_SERVERS,
    /** Our API could not be reached at all. Fix: check the connection, retry. */
    NETWORK,
    /** Anything else. */
    UNKNOWN;

    public static FailureReason classify(Throwable error) {
        if (error instanceof ApiException) {
            ApiException api = (ApiException) error;
            if (api.httpCode == 401) return SESSION_EXPIRED;
            String message = api.getMessage() == null ? "" : api.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("subscription")) return NO_SUBSCRIPTION;
            return UNKNOWN;
        }
        if (error instanceof IOException) return NETWORK;
        String message = error == null || error.getMessage() == null
                ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("no subscription links") || message.contains("no usable subscription links")) {
            return NO_SERVERS;
        }
        return UNKNOWN;
    }
}
