package com.vpn.server.repository;

import com.vpn.server.entity.SubscriptionAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SubscriptionAccessLogRepository extends JpaRepository<SubscriptionAccessLog, Long> {

    @Query("SELECT COUNT(DISTINCT s.ipAddress) FROM SubscriptionAccessLog s WHERE s.userId = :userId AND s.createdAt >= :since")
    long countDistinctIpsSince(@Param("userId") Long userId, @Param("since") Instant since);
}
