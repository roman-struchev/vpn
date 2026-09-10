package com.vpn.server.repository;

import com.vpn.server.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByTelegramId(Long telegramId);
    Optional<User> findByReferralCode(String referralCode);
    boolean existsByEmail(String email);
    boolean existsByReferralCode(String referralCode);

    @Query("SELECT COALESCE(SUM(u.balanceUsdtMicro), 0) FROM User u")
    long sumBalanceUsdtMicro();
}
