package com.vpn.server.repository;

import com.vpn.server.entity.SubscriptionAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SubscriptionAccessLogRepository extends JpaRepository<SubscriptionAccessLog, Long> {

    /** Retention sweep — see TelemetryRetentionTask. */
    long deleteByCreatedAtBefore(Instant cutoff);

    /** Keeps only the newest {@code keep} rows, as one bulk statement. */
    @Modifying
    @Query(value = "DELETE FROM subscription_access_log WHERE id NOT IN "
            + "(SELECT id FROM subscription_access_log ORDER BY created_at DESC LIMIT :keep)", nativeQuery = true)
    int deleteOldestBeyond(@Param("keep") long keep);

    @Query("SELECT COUNT(DISTINCT s.ipAddress) FROM SubscriptionAccessLog s WHERE s.userId = :userId AND s.createdAt >= :since")
    long countDistinctIpsSince(@Param("userId") Long userId, @Param("since") Instant since);
}
