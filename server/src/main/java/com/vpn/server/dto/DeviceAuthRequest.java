package com.vpn.server.dto;

public record DeviceAuthRequest(
        String deviceUuid,
        String referralCode
) {}
