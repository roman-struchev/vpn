package com.vpn.server.repository;

import com.vpn.server.entity.P2pRelayCredit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface P2pRelayCreditRepository extends JpaRepository<P2pRelayCredit, Long> {
    Optional<P2pRelayCredit> findBySessionId(String sessionId);
    List<P2pRelayCredit> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Daily-cap enforcement (docs §8.2: 50GB/day/user) — bytes already credited to this user since `since`. */
    @Query("SELECT COALESCE(SUM(c.bytesCredited), 0) FROM P2pRelayCredit c WHERE c.user.id = :userId AND c.createdAt >= :since")
    long sumBytesCreditedSince(@Param("userId") Long userId, @Param("since") Instant since);
}
