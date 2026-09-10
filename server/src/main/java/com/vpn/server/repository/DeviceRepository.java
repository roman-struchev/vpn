package com.vpn.server.repository;

import com.vpn.server.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
    List<Device> findByUserIdAndIsActiveTrue(Long userId);
    java.util.Optional<Device> findByIdAndUserId(Long id, Long userId);
    long countByUserIdAndIsActiveTrue(Long userId);

    // Used for tariff device-limit enforcement (DeviceManagementService.
    // DEVICE_ACTIVE_WINDOW_DAYS) — a device with no lastSeenAt yet (just
    // created, hasn't had a chance to be touched) still counts.
    @Query("SELECT COUNT(d) FROM Device d WHERE d.user.id = :userId AND d.isActive = true "
            + "AND (d.lastSeenAt IS NULL OR d.lastSeenAt >= :since)")
    long countRecentlyActiveByUserId(@Param("userId") Long userId, @Param("since") Instant since);
}
