package com.vpn.server.repository;

import com.vpn.server.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByTelegramId(Long telegramId);
    Optional<User> findByGoogleSub(String googleSub);
    Optional<User> findByReferralCode(String referralCode);
    Optional<User> findBySubscriptionToken(UUID subscriptionToken);
    boolean existsByEmail(String email);
    boolean existsByReferralCode(String referralCode);
    long countByReferredBy_Id(Long userId);

    @Query("SELECT COALESCE(SUM(u.balanceUsdtMicro), 0) FROM User u")
    long sumBalanceUsdtMicro();
}
