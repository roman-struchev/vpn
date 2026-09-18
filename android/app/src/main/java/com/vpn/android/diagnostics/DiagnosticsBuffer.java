package com.vpn.android.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded, in-memory hold of failures waiting to be shipped to the server
 * (see {@link DiagnosticsReporter}, which owns the sending, the threading and
 * the Android bits).
 *
 * Kept as a plain Java class with no Android dependencies deliberately: the
 * behaviour that actually matters here — that a retry loop costs one entry,
 * that the buffer cannot grow without bound, and that dropping is visible
 * rather than silent — is exactly what a JVM unit test can pin down, and it
 * would otherwise need a device to exercise at all.
 */
public final class DiagnosticsBuffer {

    /** Distinct issues held at once; past this the oldest is evicted. */
    public static final int MAX_EVENTS = 30;
    public static final int MAX_MESSAGE_LENGTH = 500;
    public static final int MAX_DETAIL_LENGTH = 2000;

    /** One failure, as the client reports it. */
    public static final class Event {
        public final String severity;
        public final String component;
        public final String code;
        public final String message;
        public final String detail;
        public final Map<String, String> context;

        public Event(String severity, String component, String code, String message,
                     String detail, Map<String, String> context) {
            this.severity = severity;
            this.component = component;
            this.code = code;
            this.message = message;
            this.detail = detail;
            this.context = context == null ? null : new LinkedHashMap<>(context);
        }

        String key() {
            return component + "|" + code + "|" + message;
        }
    }

    private final List<Event> events = new ArrayList<>();
    private int dropped = 0;

    /**
     * Adds a failure unless the same one is already waiting. Returns whether
     * it was kept, so a caller can tell "buffered" from "already known"
     * without reaching into the list.
     */
    public synchronized boolean add(String severity, String component, String code,
                                    String message, String detail, Map<String, String> context) {
        String clippedMessage = clip(message, MAX_MESSAGE_LENGTH);
        if (clippedMessage == null) {
            return false;
        }
        Event event = new Event(
                "WARN".equalsIgnoreCase(severity) ? "WARN" : "ERROR",
                isBlank(component) ? "unknown" : component,
                isBlank(code) ? "UNSPECIFIED" : code,
                clippedMessage,
                clip(detail, MAX_DETAIL_LENGTH),
                context);

        for (Event existing : events) {
            if (existing.key().equals(event.key())) {
                return false;
            }
        }
        if (events.size() >= MAX_EVENTS) {
            events.remove(0);
            dropped++;
        }
        events.add(event);
        return true;
    }

    /**
     * Takes everything buffered, leaving it empty. When reports had to be
     * dropped since the last drain, a note about that is appended — an
     * analysis reading occurrence counts should be able to see that the real
     * number was higher, rather than silently under-counting.
     */
    public synchronized List<Event> drain() {
        if (events.isEmpty()) {
            return List.of();
        }
        List<Event> drained = new ArrayList<>(events);
        events.clear();
        if (dropped > 0) {
            drained.add(new Event("WARN", "diagnostics", "REPORTS_DROPPED",
                    "Dropped " + dropped + " diagnostic report(s): more distinct failures than the client buffer holds",
                    null, null));
            dropped = 0;
        }
        return drained;
    }

    public synchronized int size() {
        return events.size();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= max) {
            return trimmed;
        }
        int end = max;
        if (Character.isHighSurrogate(trimmed.charAt(end - 1))) {
            end--;
        }
        return trimmed.substring(0, end);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
