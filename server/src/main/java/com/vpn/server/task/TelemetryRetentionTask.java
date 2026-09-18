package com.vpn.server.task;

import com.vpn.server.repository.ConnTelemetryRepository;
import com.vpn.server.repository.SubscriptionAccessLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Retention for the two append-only tables that had none: every row ever
 * written to them stayed forever, while nothing ever read the old ones.
 *
 * <ul>
 *   <li><b>conn_telemetry</b> — one row per connect report from every client
 *       and every node. Read in exactly two places: the routing decision
 *       (DynamicRoutingService, last 10 minutes) and the admin dashboard
 *       (AdminController, last 24 hours). Nothing looks further back, and
 *       POST /api/v1/client/telemetry is open, so this table grew with use
 *       and could be grown deliberately.</li>
 *   <li><b>subscription_access_log</b> — one row per subscription-export
 *       access, read by AntiEnumerationService over a window measured in
 *       minutes. Same shape of problem.</li>
 * </ul>
 *
 * Both get a retention window far longer than anything that reads them (so
 * there is still history to look at when investigating something) plus a hard
 * row cap, because a window alone bounds nothing against a burst inside it.
 */
@Component
public class TelemetryRetentionTask {

    private static final Logger log = LoggerFactory.getLogger(TelemetryRetentionTask.class);

    /** 30x the longest read (24h), so trends stay available without the table being unbounded. */
    static final long TELEMETRY_RETENTION_DAYS = 30;
    /** Orders of magnitude beyond the reads (minutes), short enough to stay small. */
    static final long ACCESS_LOG_RETENTION_DAYS = 7;

    /**
     * Ceilings for a burst inside the window — both endpoints that feed these
     * tables are reachable without authentication, so the window alone is not
     * a bound. Generous enough that ordinary traffic never reaches them.
     */
    static final long MAX_TELEMETRY_ROWS = 500_000;
    static final long MAX_ACCESS_LOG_ROWS = 200_000;

    private final ConnTelemetryRepository connTelemetryRepository;
    private final SubscriptionAccessLogRepository accessLogRepository;

    public TelemetryRetentionTask(ConnTelemetryRepository connTelemetryRepository,
                                  SubscriptionAccessLogRepository accessLogRepository) {
        this.connTelemetryRepository = connTelemetryRepository;
        this.accessLogRepository = accessLogRepository;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 180_000)
    @Transactional
    public void run() {
        pruneTelemetry();
        pruneAccessLog();
    }

    private void pruneTelemetry() {
        Instant cutoff = Instant.now().minus(TELEMETRY_RETENTION_DAYS, ChronoUnit.DAYS);
        long removed = connTelemetryRepository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("Pruned {} connection telemetry row(s) older than {} days", removed, TELEMETRY_RETENTION_DAYS);
        }
        long total = connTelemetryRepository.count();
        if (total > MAX_TELEMETRY_ROWS) {
            long evicted = connTelemetryRepository.deleteOldestBeyond(MAX_TELEMETRY_ROWS);
            log.warn("Connection telemetry over its {}-row cap — evicted the {} oldest row(s)", MAX_TELEMETRY_ROWS, evicted);
        }
    }

    private void pruneAccessLog() {
        Instant cutoff = Instant.now().minus(ACCESS_LOG_RETENTION_DAYS, ChronoUnit.DAYS);
        long removed = accessLogRepository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("Pruned {} subscription access log row(s) older than {} days", removed, ACCESS_LOG_RETENTION_DAYS);
        }
        long total = accessLogRepository.count();
        if (total > MAX_ACCESS_LOG_ROWS) {
            long evicted = accessLogRepository.deleteOldestBeyond(MAX_ACCESS_LOG_ROWS);
            log.warn("Subscription access log over its {}-row cap — evicted the {} oldest row(s)", MAX_ACCESS_LOG_ROWS, evicted);
        }
    }
}
