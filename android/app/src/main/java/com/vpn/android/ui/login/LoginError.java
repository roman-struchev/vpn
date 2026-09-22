package com.vpn.android.ui.login;

import com.vpn.android.api.ApiException;

import java.io.IOException;
import java.util.Locale;

/**
 * What went wrong signing in, in terms the screen can say in the user's
 * language. The server answers in English prose meant for logs, and a raw
 * IOException reads like "Unable to resolve host ..." — neither belongs on
 * a sign-in form. Pure Java so it is unit tested.
 */
enum LoginError {
    INVALID_CREDENTIALS,
    EMAIL_TAKEN,
    PASSWORD_TOO_SHORT,
    ACCOUNT_BLOCKED,
    NETWORK,
    GENERIC;

    static LoginError classify(Throwable error) {
        if (error instanceof IOException) return NETWORK;
        if (!(error instanceof ApiException) || error.getMessage() == null) return GENERIC;
        String message = error.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("invalid email or password")) return INVALID_CREDENTIALS;
        if (message.contains("already registered")) return EMAIL_TAKEN;
        if (message.contains("at least 6")) return PASSWORD_TOO_SHORT;
        if (message.contains("suspended") || message.contains("blocked")) return ACCOUNT_BLOCKED;
        return GENERIC;
    }
}
