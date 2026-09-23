package com.vpn.android.ui.login;

import com.vpn.android.api.ApiException;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class LoginErrorTest {

    @Test
    public void mapsServerMessagesToLocalizableErrors() {
        assertEquals(LoginError.INVALID_CREDENTIALS, LoginError.classify(new ApiException(400, "Invalid email or password")));
        assertEquals(LoginError.EMAIL_TAKEN, LoginError.classify(new ApiException(400, "Email already registered")));
        assertEquals(LoginError.PASSWORD_TOO_SHORT,
                LoginError.classify(new ApiException(400, "Password must be at least 6 characters")));
        assertEquals(LoginError.ACCOUNT_BLOCKED, LoginError.classify(new ApiException(409, "Account is suspended or blocked")));
        assertEquals(LoginError.CODE_INVALID,
                LoginError.classify(new ApiException(400, "The code is wrong or has expired. Get a new one.")));
        assertEquals(LoginError.NETWORK, LoginError.classify(new IOException("Unable to resolve host")));
        assertEquals(LoginError.GENERIC, LoginError.classify(new ApiException(500, "whatever")));
    }
}
