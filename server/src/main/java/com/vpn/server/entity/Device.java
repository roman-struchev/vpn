package com.vpn.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @JsonIgnore: this entity is serialized directly by GET /api/v1/user/devices
    // (UserController.getDevices). Without it, Jackson tries to initialize this
    // LAZY proxy outside the (already-closed, open-in-view: false) Hibernate
    // session and the whole response 500s — reproduced live: the endpoint works
    // until a user has any device, then breaks permanently for that user. See
    // docs/ROADMAP_PROGRESS.md "Пост-Фаза-10" for the write-up.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "device_name", nullable = false, length = 128)
    private String deviceName;

    @Column(nullable = false, length = 32)
    private String platform; // ANDROID, WINDOWS, MACOS, THIRD_PARTY

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    public Device() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
