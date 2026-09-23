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
    boolean existsByUserIdAndStatus(Long userId, String status);
    
    // Excludes a subscription still covered by an active admin-granted
    // temporary tariff (see Subscription#isExpired) — otherwise a user given
    // e.g. 30 days of Pro as compensation would get flipped to EXPIRED (and
    // lose all access) the moment their *real* trial/billing period ends,
    // regardless of how much override time was left.
    // EXHAUSTED counts: a paid plan that ran out of traffic is still paid for
    // until its end, and is what auto-renewal has to pick up then.
    @Query("SELECT s FROM Subscription s WHERE s.status IN ('ACTIVE', 'EXHAUSTED') AND s.currentPeriodEnd < :now "
            + "AND (s.overrideExpiresAt IS NULL OR s.overrideExpiresAt < :now)")
    List<Subscription> findExpiredSubscriptions(@Param("now") Instant now);

    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.trafficUsedBytes >= s.trafficLimitBytes")
    List<Subscription> findQuotaExceededSubscriptions();

    @Query("SELECT s FROM Subscription s WHERE s.overrideTariff IS NOT NULL AND s.overrideExpiresAt <= :now")
    List<Subscription> findExpiredTariffOverrides(@Param("now") Instant now);

    // Paid plans that will try to auto-renew before :until and haven't had
    // their low-balance heads-up yet (RenewalNotifier).
    @Query("SELECT s FROM Subscription s WHERE s.status IN ('ACTIVE', 'EXHAUSTED') AND s.autoRenew = true "
            + "AND s.currentPeriodEnd > :now AND s.currentPeriodEnd <= :until "
            + "AND s.renewalReminderSentAt IS NULL AND LOWER(s.tariff.id) <> 'trial'")
    List<Subscription> findRenewalsDueBefore(@Param("now") Instant now, @Param("until") Instant until);

    List<Subscription> findByUserIdAndStatus(Long userId, String status);

    // Annual plans whose monthly traffic period has rolled over.
    @Query("SELECT s FROM Subscription s WHERE s.status IN ('ACTIVE', 'EXHAUSTED') "
            + "AND s.trafficResetAt IS NOT NULL AND s.trafficResetAt <= :now")
    List<Subscription> findTrafficResetsDue(@Param("now") Instant now);

    // 90% or more of the quota used, no heads-up yet this traffic period.
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.trafficLimitBytes > 0 "
            + "AND s.trafficUsedBytes * 10 >= s.trafficLimitBytes * 9 AND s.trafficWarningSentAt IS NULL")
    List<Subscription> findLowTrafficUnwarned();

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(s.trafficUsedBytes), 0) FROM Subscription s")
    long sumTrafficUsedBytes();
}
