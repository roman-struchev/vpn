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
    boolean existsByTxHash(String txHash);
    void deleteByStatusAndExpiresAtBefore(String status, Instant cutoff);

    @org.springframework.data.jpa.repository.Query("SELECT i FROM CryptoInvoice i WHERE i.status = 'PENDING' AND i.chain = :chain AND i.recipientAddress = :address AND :amountMicro >= i.toleranceMinMicro AND :amountMicro <= i.toleranceMaxMicro AND i.expiresAt > :now")
    List<CryptoInvoice> findPendingMatchingInvoice(
        @org.springframework.data.repository.query.Param("chain") String chain,
        @org.springframework.data.repository.query.Param("address") String address,
        @org.springframework.data.repository.query.Param("amountMicro") Long amountMicro,
        @org.springframework.data.repository.query.Param("now") Instant now
    );
}
