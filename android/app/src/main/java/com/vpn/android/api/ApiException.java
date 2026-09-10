package com.vpn.android.api;

public class ApiException extends Exception {
    public final int httpCode;

    public ApiException(String message) {
        this(0, message);
    }

    public ApiException(int httpCode, String message) {
        super(message);
        this.httpCode = httpCode;
    }
}
