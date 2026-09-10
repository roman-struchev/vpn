package com.vpn.server.dto;

public record TelegramAuthRequest(
        String initData,
        String referralCode
) {}
