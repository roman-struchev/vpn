package com.vpn.server.task;

import com.vpn.server.entity.DiagnosticEvent;
import com.vpn.server.repository.DiagnosticEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Keeps diagnostic_events bounded from both ends, so collecting errors from
 * the whole fleet can never itself become the problem (DiagnosticsService
 * caps what a single burst can insert; this caps what accumulates over time).
 *
 * Two independent limits, because either alone has a hole: a retention window
 * on its own says nothing about a week in which thousands of *distinct*
 * issues appear, and a row cap on its own would keep a long-dead issue
 * forever just because the table never filled up.
 */
@Component
public class DiagnosticsPruneTask {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsPruneTask.class);

    /**
     * Long enough to still be looking at something reported over a weekend, or
     * to compare "before and after" across a release; short enough that the
     * table reflects how the fleet behaves *now*, which is the question this
     * data exists to answer.
     */
    static final long RETENTION_DAYS = 14;

    /**
     * Hard ceiling on distinct issues. Well above what a healthy fleet
     * produces (each row is one *kind* of failure, not one occurrence), and
     * small enough that the whole table stays a few MB and can be read in one
     * pass by an analysis.
     */
    static final int MAX_ROWS = 2000;

    private final DiagnosticEventRepository repository;

    public DiagnosticsPruneTask(DiagnosticEventRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    @Transactional
    public void run() {
        pruneExpired();
        enforceRowCap();
    }

    private void pruneExpired() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        long removed = repository.deleteByLastSeenAtBefore(cutoff);
        if (removed > 0) {
            log.info("Pruned {} diagnostic issue(s) not seen in over {} days", removed, RETENTION_DAYS);
        }
    }

    private void enforceRowCap() {
        long total = repository.count();
        if (total <= MAX_ROWS) {
            return;
        }
        int excess = (int) Math.min(total - MAX_ROWS, Integer.MAX_VALUE);
        // Least-recently-seen go first: an issue nothing has reported in a
        // while is the one worth losing when the table is full.
        List<DiagnosticEvent> evictable = repository.findOldestBySeen(PageRequest.of(0, excess));
        repository.deleteAll(evictable);
        log.info("Diagnostics table over its {}-row cap — evicted the {} least recently seen issue(s)", MAX_ROWS, evictable.size());
    }
}
