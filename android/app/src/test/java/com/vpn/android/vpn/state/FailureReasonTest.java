package com.vpn.android.vpn.state;

import com.vpn.android.api.ApiException;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class FailureReasonTest {

    @Test
    public void classifiesWhatTheServerAndNetworkReport() {
        assertEquals(FailureReason.SESSION_EXPIRED, FailureReason.classify(new ApiException(401, "Unauthorized")));
        assertEquals(FailureReason.NO_SUBSCRIPTION,
                FailureReason.classify(new ApiException(400, "Active subscription not found")));
        assertEquals(FailureReason.NO_SUBSCRIPTION,
                FailureReason.classify(new ApiException(400, "Subscription has expired")));
        assertEquals(FailureReason.NETWORK, FailureReason.classify(new IOException("timeout")));
        assertEquals(FailureReason.NO_SERVERS,
                FailureReason.classify(new IllegalStateException("No subscription links available for this account")));
        assertEquals(FailureReason.UNKNOWN, FailureReason.classify(new ApiException(500, "boom")));
        assertEquals(FailureReason.UNKNOWN, FailureReason.classify(null));
    }
}
