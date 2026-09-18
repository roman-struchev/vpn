package com.vpn.server.repository;

import com.vpn.server.entity.ConnTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ConnTelemetryRepository extends JpaRepository<ConnTelemetry, Long> {
    
    /** Retention sweep — see TelemetryRetentionTask. */
    long deleteByCreatedAtBefore(Instant cutoff);

    /**
     * Keeps only the newest {@code keep} rows. Expressed as one bulk statement
     * rather than loading the excess into memory first: this is the path that
     * runs precisely when the table has grown to something worth not loading.
     */
    @Modifying
    @Query(value = "DELETE FROM conn_telemetry WHERE id NOT IN "
            + "(SELECT id FROM conn_telemetry ORDER BY created_at DESC LIMIT :keep)", nativeQuery = true)
    int deleteOldestBeyond(@Param("keep") long keep);

    @Query("SELECT t FROM ConnTelemetry t WHERE t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<ConnTelemetry> findRecent(@Param("since") Instant since);

    // failureCount = 0 marks a successful-connect report (clients now report on
    // success too, not just failure — see XrayVpnService/VpnController
    // reportTelemetry on the success path). Without this split, totalReports
    // was just an absolute failure count with no denominator to compute a real
    // failure rate against, and there was no successful-connect latency signal
    // at all (connectTimeMs was always 0 on the failure-only path).
    @Query("SELECT t.operator, t.region, t.transport, " +
           "COUNT(t), " +
           "SUM(CASE WHEN t.failureCount = 0 THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN t.failureCount > 0 THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN t.isWhitelistSuspected = true THEN 1 ELSE 0 END), " +
           "AVG(CASE WHEN t.failureCount = 0 THEN t.connectTimeMs ELSE NULL END) " +
           "FROM ConnTelemetry t WHERE t.createdAt >= :since GROUP BY t.operator, t.region, t.transport")
    List<Object[]> aggregateByOperatorAndRegion(@Param("since") Instant since);
}
