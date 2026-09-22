package com.vpn.android.vpn;

import androidx.lifecycle.MutableLiveData;

import com.vpn.android.vpn.state.ConnectionState;

/**
 * In-process status channel between {@link XrayVpnService} and the UI. Everything
 * runs in a single app process (the service is not exported), so a plain LiveData
 * singleton is enough — no need for a bound service / Messenger / broadcast.
 */
public final class VpnStatusBus {

    public static final MutableLiveData<ConnectionState> state = new MutableLiveData<>(ConnectionState.DISCONNECTED);
    public static final MutableLiveData<String> activeRegion = new MutableLiveData<>(null);
    /** true when a pinned region preference had no online node and the last connect attempt fell back to all regions. */
    public static final MutableLiveData<Boolean> regionFallback = new MutableLiveData<>(false);
    /** Why the last attempt ended in ERROR; null in every other state. */
    public static final MutableLiveData<com.vpn.android.vpn.state.FailureReason> failureReason = new MutableLiveData<>(null);

    private VpnStatusBus() {
    }
}
