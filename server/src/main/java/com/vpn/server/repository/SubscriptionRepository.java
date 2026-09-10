package com.vpn.server.repository;

import com.vpn.server.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Optional<Subscription> findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(Long userId, String status);
    List<Subscription> findByUserId(Long userId);
    
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.currentPeriodEnd < :now")
    List<Subscription> findExpiredSubscriptions(@Param("now") Instant now);

    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.trafficUsedBytes >= s.trafficLimitBytes")
    List<Subscription> findQuotaExceededSubscriptions();

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(s.trafficUsedBytes), 0) FROM Subscription s")
    long sumTrafficUsedBytes();
}
