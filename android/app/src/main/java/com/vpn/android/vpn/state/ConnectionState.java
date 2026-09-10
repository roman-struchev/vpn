package com.vpn.android.vpn.state;

/**
 * The five states from PLAN.md §9 ("Disconnected / Connecting / Connected /
 * Reconnecting / Error") plus the honest operator-restriction screen from §6,
 * modeled as its own state rather than folded into ERROR so the UI can show
 * a different, non-alarming message.
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    OPERATOR_BLOCKED,
    ERROR
}
