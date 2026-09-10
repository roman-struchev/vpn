package com.vpn.server.repository;

import com.vpn.server.entity.BalanceEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BalanceEntryRepository extends JpaRepository<BalanceEntry, Long> {
    List<BalanceEntry> findByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByReferenceId(String referenceId);
    long countByUserIdAndType(Long userId, String type);
}
