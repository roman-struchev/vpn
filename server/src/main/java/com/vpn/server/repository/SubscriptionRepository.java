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
    boolean existsByUserIdAndTariffId(Long userId, String tariffId);
    
    // Excludes a subscription still covered by an active admin-granted
    // temporary tariff (see Subscription#isExpired) — otherwise a user given
    // e.g. 30 days of Pro as compensation would get flipped to EXPIRED (and
    // lose all access) the moment their *real* trial/billing period ends,
    // regardless of how much override time was left.
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.currentPeriodEnd < :now "
            + "AND (s.overrideExpiresAt IS NULL OR s.overrideExpiresAt < :now)")
    List<Subscription> findExpiredSubscriptions(@Param("now") Instant now);

    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.trafficUsedBytes >= s.trafficLimitBytes")
    List<Subscription> findQuotaExceededSubscriptions();

    @Query("SELECT s FROM Subscription s WHERE s.overrideTariff IS NOT NULL AND s.overrideExpiresAt <= :now")
    List<Subscription> findExpiredTariffOverrides(@Param("now") Instant now);

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(s.trafficUsedBytes), 0) FROM Subscription s")
    long sumTrafficUsedBytes();
}
