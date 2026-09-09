package com.vpn.server.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tariffs")
public class Tariff {

    @Id
    @Column(length = 32)
    private String id; // 'trial', 'basic', 'pro'

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "monthly_price_usdt_micro", nullable = false)
    private Long monthlyPriceUsdtMicro;

    @Column(name = "annual_price_usdt_micro", nullable = false)
    private Long annualPriceUsdtMicro;

    @Column(name = "traffic_quota_bytes", nullable = false)
    private Long trafficQuotaBytes;

    @Column(name = "max_devices", nullable = false)
    private Integer maxDevices;

    @Column(name = "server_pool", nullable = false, length = 32)
    private String serverPool = "paid";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Tariff() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getMonthlyPriceUsdtMicro() { return monthlyPriceUsdtMicro; }
    public void setMonthlyPriceUsdtMicro(Long monthlyPriceUsdtMicro) { this.monthlyPriceUsdtMicro = monthlyPriceUsdtMicro; }

    public Long getAnnualPriceUsdtMicro() { return annualPriceUsdtMicro; }
    public void setAnnualPriceUsdtMicro(Long annualPriceUsdtMicro) { this.annualPriceUsdtMicro = annualPriceUsdtMicro; }

    public Long getTrafficQuotaBytes() { return trafficQuotaBytes; }
    public void setTrafficQuotaBytes(Long trafficQuotaBytes) { this.trafficQuotaBytes = trafficQuotaBytes; }

    public Integer getMaxDevices() { return maxDevices; }
    public void setMaxDevices(Integer maxDevices) { this.maxDevices = maxDevices; }

    public String getServerPool() { return serverPool; }
    public void setServerPool(String serverPool) { this.serverPool = serverPool; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
