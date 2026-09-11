package com.vpn.server.repository;

import com.vpn.server.entity.BalanceEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BalanceEntryRepository extends JpaRepository<BalanceEntry, Long> {
    List<BalanceEntry> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByReferenceId(String referenceId);
    long countByUserIdAndType(Long userId, String type);

    /**
     * The referral money handed out so far (BillingService#applyReferralRewards writes
     * exactly these two entry types), i.e. what the admin dashboard reports as the cost
     * of the referral program. COALESCE keeps it 0 rather than null on an empty table.
     */
    @Query("SELECT COALESCE(SUM(b.amountUsdtMicro), 0) FROM BalanceEntry b "
            + "WHERE b.type IN ('REFERRAL_BONUS', 'REFERRAL_WELCOME_BONUS')")
    long sumReferralPayoutsMicro();

    @Query("SELECT COUNT(b) FROM BalanceEntry b "
            + "WHERE b.type IN ('REFERRAL_BONUS', 'REFERRAL_WELCOME_BONUS')")
    long countReferralPayouts();

    /**
     * Per-user referral payouts as {userId, sumMicro} rows — one grouped query so the
     * admin user list doesn't need an extra SELECT per user.
     */
    @Query("SELECT b.user.id, COALESCE(SUM(b.amountUsdtMicro), 0) FROM BalanceEntry b "
            + "WHERE b.type IN ('REFERRAL_BONUS', 'REFERRAL_WELCOME_BONUS') GROUP BY b.user.id")
    List<Object[]> sumReferralPayoutsGroupedByUser();
}
