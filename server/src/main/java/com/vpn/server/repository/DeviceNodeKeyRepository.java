package com.vpn.server.repository;

import com.vpn.server.entity.DeviceNodeKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceNodeKeyRepository extends JpaRepository<DeviceNodeKey, Long> {
    Optional<DeviceNodeKey> findByDeviceIdAndNodeId(Long deviceId, Long nodeId);
    Optional<DeviceNodeKey> findByUuid(UUID uuid);
    List<DeviceNodeKey> findByNodeId(Long nodeId);

    void deleteByDeviceId(Long deviceId);
    List<DeviceNodeKey> findByDeviceId(Long deviceId);

    @Query("SELECT dnk FROM DeviceNodeKey dnk JOIN FETCH dnk.device d JOIN FETCH d.user WHERE dnk.node.id = :nodeId AND d.isActive = true")
    List<DeviceNodeKey> findActiveKeysByNodeId(@Param("nodeId") Long nodeId);
}
