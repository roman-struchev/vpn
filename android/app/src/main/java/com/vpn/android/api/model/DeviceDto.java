package com.vpn.android.api.model;

/** Mirrors server entity.Device as returned by GET/POST /api/v1/user/devices. */
public class DeviceDto {
    public long id;
    public String deviceName;
    public String platform;
    public Boolean isActive;
    public String createdAt;
    public String lastSeenAt;
}
