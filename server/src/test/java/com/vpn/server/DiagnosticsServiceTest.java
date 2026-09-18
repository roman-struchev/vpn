package com.vpn.server;

import com.vpn.server.entity.DiagnosticEvent;
import com.vpn.server.repository.DiagnosticEventRepository;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.DiagnosticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The properties this store has to hold: a burst of the same failure must
 * cost one row, identifiers must not survive into that row, and nothing a
 * reporter sends may break the reporter.
 */
class DiagnosticsServiceTest {

    private DiagnosticEventRepository repository;
    private DiagnosticsService service;
    private final Map<String, DiagnosticEvent> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(DiagnosticEventRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        NodeRepository nodeRepository = mock(NodeRepository.class);
        stored.clear();

        // A tiny in-memory stand-in for the table, so aggregation can be
        // asserted on rather than inferred from save() call counts.
        when(repository.findByFingerprint(anyString()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0, String.class))));
        when(repository.save(any(DiagnosticEvent.class))).thenAnswer(i -> {
            DiagnosticEvent e = i.getArgument(0);
            stored.put(e.getFingerprint(), e);
            return e;
        });

        service = new DiagnosticsService(repository, userRepository, nodeRepository);
    }

    private static DiagnosticsService.Report report(String message, String reporterId) {
        return new DiagnosticsService.Report(
                "NODE_AGENT", "ERROR", "xray", "XRAY_EXITED", message, null, null, "0.1.13", reporterId, null, null);
    }

    @Test
    void testRepeatedFailuresFoldIntoOneRowWithACounter() {
        // The whole point: a crash-looping agent must cost one row, not one
        // row per report, or collecting errors becomes its own incident.
        for (int i = 0; i < 50; i++) {
            service.record(List.of(report("xray exited with code 1", "node-7")));
        }

        assertEquals(1, stored.size());
        DiagnosticEvent event = stored.values().iterator().next();
        assertEquals(50L, event.getOccurrences());
        assertEquals(1L, event.getReporters(), "one looping machine is not fifty machines");
    }

    @Test
    void testOccurrencesDifferingOnlyInIdsOrAddressesAreTheSameIssue() {
        service.record(List.of(report("dial tcp 10.0.0.1:443 failed after 3000ms", "node-1")));
        service.record(List.of(report("dial tcp 203.0.113.9:443 failed after 4120ms", "node-2")));

        assertEquals(1, stored.size(), "the addresses vary, the failure does not");
        DiagnosticEvent event = stored.values().iterator().next();
        assertEquals(2L, event.getOccurrences());
        assertEquals(2L, event.getReporters(), "two machines, not one looping");
        assertTrue(event.getMessage().contains("<ip>"), event.getMessage());
        assertFalse(event.getMessage().contains("10.0.0.1"), "the address must not be stored");
    }

    @Test
    void testGenuinelyDifferentFailuresStaySeparate() {
        service.record(List.of(report("xray exited with code 1", "node-1")));
        service.record(List.of(new DiagnosticsService.Report(
                "NODE_AGENT", "ERROR", "config", "CONFIG_REJECTED", "config sync rejected", null, null, "0.1.13", "node-1", null, null)));

        assertEquals(2, stored.size());
    }

    @Test
    void testIdentifiersAreMaskedOutOfWhatIsStored() {
        service.record(List.of(new DiagnosticsService.Report(
                "ANDROID", "ERROR", "api", "LOGIN_FAILED",
                "login failed for someone@example.com (device 4f1c0e2a-9d3b-4c77-8f11-0a2b3c4d5e6f)",
                "token a3f9c2b7e4d18f0a5c6b7d8e9f0a1b2c3d4e5f60 rejected",
                Map.of("ip", "198.51.100.7"),
                "0.1.13", "device-abc", null, null)));

        DiagnosticEvent event = stored.values().iterator().next();
        assertFalse(event.getMessage().contains("someone@example.com"), event.getMessage());
        assertFalse(event.getMessage().contains("4f1c0e2a"), event.getMessage());
        assertFalse(event.getSampleDetail().contains("a3f9c2b7e4d18f0a"), event.getSampleDetail());
        assertFalse(event.getSampleContext().contains("198.51.100.7"), event.getSampleContext());
    }

    @Test
    void testOversizedFieldsAreClippedToTheirColumns() {
        service.record(List.of(new DiagnosticsService.Report(
                "DESKTOP", "ERROR", "c".repeat(500), "k".repeat(500), "m".repeat(5000), "d".repeat(50_000),
                Map.of("k".repeat(200), "v".repeat(500)), "0.1.13", "r".repeat(500), null, null)));

        DiagnosticEvent event = stored.values().iterator().next();
        assertTrue(event.getMessage().length() <= 512, "message column is VARCHAR(512)");
        assertTrue(event.getComponent().length() <= 64);
        assertTrue(event.getCode().length() <= 64);
        assertTrue(event.getSampleDetail().length() <= 2000);
        assertTrue(event.getSampleContext().length() <= 1000);
        assertTrue(event.getLastReporter().length() <= 64);
    }

    @Test
    void testOneRequestCannotSubmitMoreThanTheBatchCap() {
        List<DiagnosticsService.Report> flood = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            flood.add(report("failure number " + i + " of many distinct ones", "node-1"));
        }

        int accepted = service.record(flood);

        assertEquals(DiagnosticsService.MAX_EVENTS_PER_REPORT, accepted);
        assertTrue(stored.size() <= DiagnosticsService.MAX_EVENTS_PER_REPORT);
    }

    @Test
    void testAReportWithNothingUsableIsDroppedRatherThanStored() {
        assertEquals(0, service.record(List.of(report("   ", "node-1"))));
        assertEquals(0, service.record(List.of(report(null, "node-1"))));
        assertEquals(0, service.record(List.of()));
        assertEquals(0, service.record(null));
        assertTrue(stored.isEmpty());
    }

    @Test
    void testAnUnknownSourceIsNormalisedRatherThanStoredVerbatim() {
        service.record(List.of(new DiagnosticsService.Report(
                "'; DROP TABLE users; --", "ERROR", "x", "Y", "something broke", null, null, null, null, null, null)));

        assertEquals("UNKNOWN", stored.values().iterator().next().getSource());
    }

    @Test
    void testARepositoryFailureNeverPropagatesToTheReporter() {
        // A client calling this is already in a failure path; error reporting
        // must not add a second one on top.
        when(repository.save(any(DiagnosticEvent.class))).thenThrow(new RuntimeException("database is down"));

        assertDoesNotThrow(() -> service.record(List.of(report("xray exited with code 1", "node-1"))));
    }

    @Test
    void testAppVersionsAccumulateOldestFirstAcrossReports() {
        service.record(List.of(new DiagnosticsService.Report(
                "ANDROID", "ERROR", "tunnel", "TUNNEL_FAILED", "tunnel did not come up", null, null, "0.1.12", "d1", null, null)));
        service.record(List.of(new DiagnosticsService.Report(
                "ANDROID", "ERROR", "tunnel", "TUNNEL_FAILED", "tunnel did not come up", null, null, "0.1.13", "d2", null, null)));
        service.record(List.of(new DiagnosticsService.Report(
                "ANDROID", "ERROR", "tunnel", "TUNNEL_FAILED", "tunnel did not come up", null, null, "0.1.13", "d3", null, null)));

        DiagnosticEvent event = stored.values().iterator().next();
        assertEquals("0.1.12,0.1.13", event.getAppVersions(), "an issue's builds, oldest first, deduplicated");
    }
}
