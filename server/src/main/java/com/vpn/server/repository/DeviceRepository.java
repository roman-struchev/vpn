package com.vpn.server.repository;

import com.vpn.server.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
    List<Device> findByUserIdAndIsActiveTrue(Long userId);
    long countByUserIdAndIsActiveTrue(Long userId);
}
