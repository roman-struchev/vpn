package com.vpn.server;

import com.vpn.server.repository.ConnTelemetryRepository;
import com.vpn.server.repository.SubscriptionAccessLogRepository;
import com.vpn.server.task.TelemetryRetentionTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Both tables here were append-only with nothing ever deleting from them,
 * while nothing ever read rows older than a day either — and both are fed by
 * endpoints reachable without authentication, so a window alone would not
 * bound them.
 */
class TelemetryRetentionTaskTest {

    private ConnTelemetryRepository telemetry;
    private SubscriptionAccessLogRepository accessLog;
    private TelemetryRetentionTask task;

    @BeforeEach
    void setUp() {
        telemetry = mock(ConnTelemetryRepository.class);
        accessLog = mock(SubscriptionAccessLogRepository.class);
        task = new TelemetryRetentionTask(telemetry, accessLog);
    }

    @Test
    void testBothTablesLoseRowsOlderThanTheirWindow() {
        task.run();

        ArgumentCaptor<Instant> telemetryCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(telemetry).deleteByCreatedAtBefore(telemetryCutoff.capture());
        assertEquals(30, ChronoUnit.DAYS.between(telemetryCutoff.getValue(), Instant.now()),
                "telemetry keeps 30 days — far beyond the 24h anything reads");

        ArgumentCaptor<Instant> accessCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(accessLog).deleteByCreatedAtBefore(accessCutoff.capture());
        assertEquals(7, ChronoUnit.DAYS.between(accessCutoff.getValue(), Instant.now()),
                "the access log keeps 7 days — its reads span minutes");
    }

    @Test
    void testATableInsideItsCapIsNotTrimmedFurther() {
        when(telemetry.count()).thenReturn(1_000L);
        when(accessLog.count()).thenReturn(1_000L);

        task.run();

        verify(telemetry, never()).deleteOldestBeyond(anyLong());
        verify(accessLog, never()).deleteOldestBeyond(anyLong());
    }

    @Test
    void testABurstInsideTheWindowIsStillCappedByRowCount() {
        // The window says nothing about how many rows arrive inside it, and
        // both feeding endpoints are open.
        when(telemetry.count()).thenReturn(5_000_000L);
        when(accessLog.count()).thenReturn(5_000_000L);

        task.run();

        verify(telemetry).deleteOldestBeyond(500_000L);
        verify(accessLog).deleteOldestBeyond(200_000L);
    }
}
