package com.vpn.server.repository;

import com.vpn.server.entity.CryptoInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CryptoInvoiceRepository extends JpaRepository<CryptoInvoice, Long> {
    List<CryptoInvoice> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<CryptoInvoice> findByChainAndExpectedAmountUsdtMicroAndStatus(String chain, Long expectedAmount, String status);
    List<CryptoInvoice> findByStatusAndExpiresAtBefore(String status, Instant now);
}
