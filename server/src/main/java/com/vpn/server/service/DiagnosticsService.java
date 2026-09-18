package com.vpn.server.service;

import com.vpn.server.entity.DiagnosticEvent;
import com.vpn.server.entity.Node;
import com.vpn.server.entity.User;
import com.vpn.server.repository.DiagnosticEventRepository;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Collects the failures that node agents and client apps hit, so they can be
 * read together instead of living in one machine's log until someone happens
 * to look (the repo owner's ask: "на нодах и клиентах могут происходить
 * ошибки ... собирай их и отправляй на сервер ... чтобы проанализировать
 * через LLM как все работает и нужны ли какие-то правки").
 *
 * Three things this has to get right, in order:
 *
 * 1. STAY BOUNDED. Reports arrive from machines that are, by definition,
 *    misbehaving — a crash-looping agent can produce thousands a minute.
 *    Every occurrence of one issue therefore folds into a single row keyed by
 *    a fingerprint of its *masked* message, so an incident costs one row and
 *    a counter, not a row per report. Sizes are clipped per field, per
 *    request, and per source-per-minute; DiagnosticsPruneTask enforces the
 *    retention window and the hard row cap on top.
 *
 * 2. STAY ANALYZABLE. The masking is what makes aggregation work at all:
 *    "connect to 10.0.0.1:443 failed" and "connect to 10.0.0.2:443 failed"
 *    are the same issue, and only become one row once the address is masked.
 *    The same masking keeps identifiers out of the stored text.
 *
 * 3. NEVER BREAK THE REPORTER. Nothing here is allowed to fail a caller's
 *    real work: ingest is best-effort and swallows its own errors, because
 *    the client calling it is already in a failure path.
 */
@Service
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    public static final Set<String> KNOWN_SOURCES = Set.of("NODE_AGENT", "ANDROID", "DESKTOP", "WEB", "SERVER");

    // Field caps. Chosen to keep one row small enough that even a full table
    // of them is a few MB, while leaving a stack trace readable.
    static final int MAX_MESSAGE_LENGTH = 512;
    static final int MAX_DETAIL_LENGTH = 2000;
    static final int MAX_CONTEXT_LENGTH = 1000;
    static final int MAX_CONTEXT_ENTRIES = 12;
    static final int MAX_COMPONENT_LENGTH = 64;
    static final int MAX_CODE_LENGTH = 64;
    static final int MAX_VERSION_LIST_LENGTH = 200;

    /** Per request — a reporter with more than this to say is looping, and the rest adds nothing. */
    public static final int MAX_EVENTS_PER_REPORT = 20;

    /** Per source per minute, across all reporters: the flood valve. */
    static final int MAX_EVENTS_PER_SOURCE_PER_MINUTE = 600;

    // Masking patterns, applied in this order. Deliberately blunt: the goal is
    // that two occurrences of the same problem produce the same text, and that
    // identifiers do not get stored, not perfect classification.
    private static final Pattern UUID_PATTERN =
            Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}\\b");
    private static final Pattern LONG_HEX_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{16,}\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private final DiagnosticEventRepository repository;
    private final UserRepository userRepository;
    private final NodeRepository nodeRepository;

    // Flood valve state: source -> (minute bucket, count so far). Deliberately
    // in-memory and per-instance — this exists to stop a loop from filling the
    // table, not to be an exact quota, and it must not cost a database round
    // trip on a path that runs while things are already going wrong.
    private final Map<String, long[]> rateBuckets = new HashMap<>();

    public DiagnosticsService(DiagnosticEventRepository repository,
                              UserRepository userRepository,
                              NodeRepository nodeRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.nodeRepository = nodeRepository;
    }

    /**
     * One reported failure, as it arrives from a client or a node agent.
     * Everything except the message is optional — a reporter in trouble should
     * be able to say the minimum and still be heard.
     */
    public record Report(
            String source,
            String severity,
            String component,
            String code,
            String message,
            String detail,
            Map<String, String> context,
            String appVersion,
            String reporterId,
            Long userId,
            Long nodeId
    ) {}

    /** How many of the submitted reports were actually recorded. */
    @Transactional
    public int record(List<Report> reports) {
        if (reports == null || reports.isEmpty()) {
            return 0;
        }
        int accepted = 0;
        for (Report report : reports.subList(0, Math.min(reports.size(), MAX_EVENTS_PER_REPORT))) {
            try {
                if (recordOne(report)) {
                    accepted++;
                }
            } catch (DataIntegrityViolationException e) {
                // Two reporters hit the same brand-new issue at once and both
                // inserted it; the loser's row is the redundant one. Not worth
                // a retry — the winner already recorded the occurrence.
                log.debug("Concurrent insert for the same diagnostic fingerprint, dropping one: {}", e.getMessage());
            } catch (RuntimeException e) {
                // Never let diagnostics break the thing reporting them.
                log.warn("Could not record a diagnostic report: {}", e.toString());
            }
        }
        return accepted;
    }

    private boolean recordOne(Report report) {
        String message = clip(trimToNull(report.message()), MAX_MESSAGE_LENGTH);
        if (message == null) {
            return false;
        }
        String source = normalizeSource(report.source());
        if (!withinRateLimit(source)) {
            return false;
        }

        String severity = "WARN".equalsIgnoreCase(report.severity()) ? "WARN" : "ERROR";
        String component = clip(defaultIfBlank(report.component(), "unknown"), MAX_COMPONENT_LENGTH);
        String code = clip(defaultIfBlank(report.code(), "UNSPECIFIED"), MAX_CODE_LENGTH);
        String maskedMessage = mask(message);
        String fingerprint = fingerprintOf(source, component, code, maskedMessage);

        DiagnosticEvent event = repository.findByFingerprint(fingerprint).orElse(null);
        Instant now = Instant.now();
        if (event == null) {
            event = new DiagnosticEvent();
            event.setFingerprint(fingerprint);
            event.setSource(source);
            event.setComponent(component);
            event.setCode(code);
            event.setMessage(maskedMessage);
            event.setFirstSeenAt(now);
            event.setOccurrences(0L);
            event.setReporters(0L);
        }

        event.setSeverity(severity);
        event.setOccurrences(event.getOccurrences() + 1);
        event.setLastSeenAt(now);
        // Approximate distinct-reporter count: enough to tell "one machine
        // stuck in a loop" (reporters stays 1) from "this is happening to
        // everyone", without storing a set of reporter ids per issue.
        String reporter = clip(trimToNull(report.reporterId()), 64);
        if (reporter != null && !reporter.equals(event.getLastReporter())) {
            event.setReporters(event.getReporters() + 1);
            event.setLastReporter(reporter);
        } else if (event.getReporters() == 0) {
            event.setReporters(1L);
        }

        event.setSampleDetail(clip(mask(trimToNull(report.detail())), MAX_DETAIL_LENGTH));
        event.setSampleContext(renderContext(report.context()));
        event.setAppVersions(mergeAppVersions(event.getAppVersions(), report.appVersion()));

        if (report.userId() != null) {
            userRepository.findById(report.userId()).ifPresent(event::setLastUser);
        }
        if (report.nodeId() != null) {
            nodeRepository.findById(report.nodeId()).ifPresent(event::setLastNode);
        }

        repository.save(event);
        return true;
    }

    private synchronized boolean withinRateLimit(String source) {
        long minute = Instant.now().getEpochSecond() / 60;
        long[] bucket = rateBuckets.computeIfAbsent(source, s -> new long[]{minute, 0});
        if (bucket[0] != minute) {
            bucket[0] = minute;
            bucket[1] = 0;
        }
        if (bucket[1] >= MAX_EVENTS_PER_SOURCE_PER_MINUTE) {
            return false;
        }
        bucket[1]++;
        return true;
    }

    static String normalizeSource(String raw) {
        if (raw == null) {
            return "UNKNOWN";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return KNOWN_SOURCES.contains(upper) ? upper : "UNKNOWN";
    }

    /**
     * Replaces the parts of a message that vary between occurrences of the
     * same problem — addresses, ids, ports, counters — so those occurrences
     * share one fingerprint. Ordered longest-pattern-first: a UUID would
     * otherwise be eaten by the plain-number rule and stop matching as a UUID.
     */
    static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = UUID_PATTERN.matcher(text).replaceAll("<uuid>");
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("<email>");
        masked = IPV4_PATTERN.matcher(masked).replaceAll("<ip>");
        masked = LONG_HEX_PATTERN.matcher(masked).replaceAll("<hex>");
        masked = NUMBER_PATTERN.matcher(masked).replaceAll("#");
        return masked;
    }

    static String fingerprintOf(String source, String component, String code, String maskedMessage) {
        String material = source + "|" + component + "|" + code + "|" + maskedMessage;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; this cannot happen on a JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Keeps the set of app versions an issue has been seen on, oldest first,
     * so an analysis can tell a long-standing bug from one a release
     * introduced. Bounded like everything else here.
     */
    static String mergeAppVersions(String existing, String appVersion) {
        String version = trimToNull(appVersion);
        if (version == null) {
            return existing;
        }
        version = version.replace(",", " ").trim();
        if (existing == null || existing.isBlank()) {
            return clip(version, MAX_VERSION_LIST_LENGTH);
        }
        List<String> known = new ArrayList<>(Arrays.asList(existing.split(",")));
        if (known.contains(version)) {
            return existing;
        }
        known.add(version);
        while (String.join(",", known).length() > MAX_VERSION_LIST_LENGTH && known.size() > 1) {
            known.remove(0);
        }
        return String.join(",", known);
    }

    /** A small, masked JSON object — never the caller's map verbatim. */
    private String renderContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("{");
        int entries = 0;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            if (entries >= MAX_CONTEXT_ENTRIES || json.length() >= MAX_CONTEXT_LENGTH) {
                break;
            }
            String key = clip(entry.getKey(), 40);
            String value = clip(mask(entry.getValue()), 120);
            if (key == null || value == null) {
                continue;
            }
            if (entries > 0) {
                json.append(',');
            }
            json.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"');
            entries++;
        }
        json.append('}');
        return entries == 0 ? null : clip(json.toString(), MAX_CONTEXT_LENGTH);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    // ---------------------------------------------------------------- reads

    /**
     * Everything an analysis needs about one issue. Flat and self-describing
     * on purpose: whatever reads this — a human, a script, or a model being
     * asked "what is broken and does it need fixing" — should not have to
     * join anything or know the schema.
     */
    public record IssueView(
            String fingerprint,
            String source,
            String severity,
            String component,
            String code,
            String message,
            long occurrences,
            long reporters,
            List<String> appVersions,
            String sampleDetail,
            String sampleContext,
            Long lastUserId,
            Long lastNodeId,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {}

    public record DiagnosticsSummary(
            Instant generatedAt,
            int windowHours,
            long totalOccurrences,
            int distinctIssues,
            Map<String, Long> occurrencesBySource,
            Map<String, Long> occurrencesByComponent,
            List<IssueView> issues
    ) {}

    @Transactional(readOnly = true)
    public DiagnosticsSummary summarize(int windowHours, String source, int limit) {
        int hours = windowHours > 0 ? windowHours : 24;
        int cap = limit > 0 ? Math.min(limit, 200) : 50;
        Instant since = Instant.now().minusSeconds(hours * 3600L);

        List<DiagnosticEvent> events = (source == null || source.isBlank())
                ? repository.findByLastSeenAtAfterOrderByLastSeenAtDesc(since, PageRequest.of(0, cap))
                : repository.findBySourceAndLastSeenAtAfterOrderByLastSeenAtDesc(
                        normalizeSource(source), since, PageRequest.of(0, cap));

        List<IssueView> issues = events.stream()
                .sorted(Comparator.comparingLong(DiagnosticEvent::getOccurrences).reversed())
                .map(DiagnosticsService::toView)
                .toList();

        Map<String, Long> bySource = new TreeMap<>();
        Map<String, Long> byComponent = new TreeMap<>();
        for (DiagnosticEvent e : events) {
            bySource.merge(e.getSource(), e.getOccurrences(), Long::sum);
            byComponent.merge(e.getComponent(), e.getOccurrences(), Long::sum);
        }

        return new DiagnosticsSummary(
                Instant.now(),
                hours,
                repository.sumOccurrencesSince(since),
                issues.size(),
                bySource,
                byComponent,
                issues
        );
    }

    private static IssueView toView(DiagnosticEvent e) {
        List<String> versions = (e.getAppVersions() == null || e.getAppVersions().isBlank())
                ? List.of()
                : Arrays.stream(e.getAppVersions().split(",")).filter(v -> !v.isBlank()).toList();
        return new IssueView(
                e.getFingerprint(),
                e.getSource(),
                e.getSeverity(),
                e.getComponent(),
                e.getCode(),
                e.getMessage(),
                e.getOccurrences(),
                e.getReporters(),
                versions,
                e.getSampleDetail(),
                e.getSampleContext(),
                e.getLastUserId(),
                e.getLastNodeId(),
                e.getFirstSeenAt(),
                e.getLastSeenAt()
        );
    }
}
