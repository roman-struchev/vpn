package com.vpn.server.repository;

import com.vpn.server.entity.ConnTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ConnTelemetryRepository extends JpaRepository<ConnTelemetry, Long> {
    
    @Query("SELECT t FROM ConnTelemetry t WHERE t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<ConnTelemetry> findRecent(@Param("since") Instant since);

    @Query("SELECT t.operator, t.region, t.transport, COUNT(t), SUM(CASE WHEN t.isWhitelistSuspected = true THEN 1 ELSE 0 END) " +
           "FROM ConnTelemetry t WHERE t.createdAt >= :since GROUP BY t.operator, t.region, t.transport")
    List<Object[]> aggregateByOperatorAndRegion(@Param("since") Instant since);
}
