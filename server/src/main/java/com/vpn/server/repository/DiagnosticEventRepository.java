package com.vpn.server.repository;

import com.vpn.server.entity.DiagnosticEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DiagnosticEventRepository extends JpaRepository<DiagnosticEvent, Long> {

    Optional<DiagnosticEvent> findByFingerprint(String fingerprint);

    List<DiagnosticEvent> findByLastSeenAtAfterOrderByLastSeenAtDesc(Instant since, Pageable pageable);

    List<DiagnosticEvent> findBySourceAndLastSeenAtAfterOrderByLastSeenAtDesc(String source, Instant since, Pageable pageable);

    long deleteByLastSeenAtBefore(Instant cutoff);

    /** Least-recently-seen first — what the row cap evicts (see DiagnosticsPruneTask). */
    @Query("SELECT e FROM DiagnosticEvent e ORDER BY e.lastSeenAt ASC")
    List<DiagnosticEvent> findOldestBySeen(Pageable pageable);

    @Query("SELECT COALESCE(SUM(e.occurrences), 0) FROM DiagnosticEvent e WHERE e.lastSeenAt > :since")
    long sumOccurrencesSince(@Param("since") Instant since);
}
