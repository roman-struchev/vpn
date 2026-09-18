package com.vpn.server;

import com.vpn.server.entity.DiagnosticEvent;
import com.vpn.server.repository.DiagnosticEventRepository;
import com.vpn.server.task.DiagnosticsPruneTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The two limits that keep collecting errors from becoming its own incident.
 * Both matter: a retention window alone says nothing about a week full of
 * *distinct* issues, and a row cap alone would keep a long-dead issue forever
 * as long as the table never filled.
 */
class DiagnosticsPruneTaskTest {

    private DiagnosticEventRepository repository;
    private DiagnosticsPruneTask task;

    @BeforeEach
    void setUp() {
        repository = mock(DiagnosticEventRepository.class);
        task = new DiagnosticsPruneTask(repository);
    }

    private static DiagnosticEvent event(String fingerprint) {
        DiagnosticEvent e = new DiagnosticEvent();
        e.setFingerprint(fingerprint);
        e.setSource("NODE_AGENT");
        e.setMessage("something broke");
        return e;
    }

    @Test
    void testIssuesNotSeenWithinTheRetentionWindowAreDeleted() {
        when(repository.count()).thenReturn(10L);

        task.run();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByLastSeenAtBefore(cutoff.capture());

        long daysBack = ChronoUnit.DAYS.between(cutoff.getValue(), Instant.now());
        assertEquals(14, daysBack, "the retention window is 14 days");
    }

    @Test
    void testATableUnderTheCapLosesNothingToTheCap() {
        when(repository.count()).thenReturn(100L);

        task.run();

        verify(repository, never()).deleteAll(any());
    }

    @Test
    void testOverTheCapTheLeastRecentlySeenAreEvicted() {
        // 2003 rows against a 2000 cap: exactly the three oldest must go, and
        // they must be chosen by when they were last seen, not by id.
        when(repository.count()).thenReturn(2003L);
        List<DiagnosticEvent> oldest = List.of(event("a"), event("b"), event("c"));
        when(repository.findOldestBySeen(any(Pageable.class))).thenReturn(oldest);

        task.run();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findOldestBySeen(page.capture());
        assertEquals(3, page.getValue().getPageSize(), "only the excess is evicted");
        verify(repository).deleteAll(oldest);
    }
}
