package com.vpn.android.diagnostics;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * What the buffer has to guarantee on a phone: a failure that repeats (a
 * reconnect loop on a bad network is the normal case) costs one entry, the
 * buffer cannot grow without bound while offline, and losing reports is
 * visible in the data rather than silent.
 */
public class DiagnosticsBufferTest {

    private static boolean add(DiagnosticsBuffer buffer, String message) {
        return buffer.add("ERROR", "vpn", "TUNNEL_START_FAILED", message, null, null);
    }

    @Test
    public void repeatedFailuresCostOneEntry() {
        DiagnosticsBuffer buffer = new DiagnosticsBuffer();

        for (int i = 0; i < 200; i++) {
            add(buffer, "Tunnel start failed (transport=XHTTP)");
        }

        assertEquals(1, buffer.size());
        assertEquals(1, buffer.drain().size());
    }

    @Test
    public void differentFailuresAreKeptApart() {
        DiagnosticsBuffer buffer = new DiagnosticsBuffer();

        add(buffer, "Tunnel start failed (transport=XHTTP)");
        buffer.add("ERROR", "vpn", "PROFILE_LOAD_FAILED", "Failed to load the VPN profile", null, null);

        assertEquals(2, buffer.size());
    }

    @Test
    public void bufferIsCappedAndSaysSoWhenItHadToDrop() {
        DiagnosticsBuffer buffer = new DiagnosticsBuffer();

        for (int i = 0; i < DiagnosticsBuffer.MAX_EVENTS * 2; i++) {
            add(buffer, "failure number " + i);
        }

        assertEquals(DiagnosticsBuffer.MAX_EVENTS, buffer.size());
        List<DiagnosticsBuffer.Event> drained = buffer.drain();
        assertEquals(DiagnosticsBuffer.MAX_EVENTS + 1, drained.size());
        DiagnosticsBuffer.Event notice = drained.get(drained.size() - 1);
        assertEquals("REPORTS_DROPPED", notice.code);
        assertTrue(notice.message, notice.message.contains("Dropped"));
    }

    @Test
    public void drainEmptiesSoNothingIsReportedTwice() {
        DiagnosticsBuffer buffer = new DiagnosticsBuffer();
        add(buffer, "Tunnel start failed (transport=XHTTP)");

        assertEquals(1, buffer.drain().size());
        assertTrue(buffer.drain().isEmpty());
    }

    @Test
    public void aReportWithoutAMessageIsIgnored() {
        DiagnosticsBuffer buffer = new DiagnosticsBuffer();

        assertFalse(add(buffer, null));
        assertFalse(add(buffer, "   "));
        assertEquals(0, buffer.size());
    }

    @Test
    public void oversizedTextIsClippedWithoutSplittingACharacter() {
        DiagnosticsBuffer buffer = new DiagnosticsBuffer();
        String emoji = "📱"; // one emoji, two Java chars

        buffer.add("ERROR", "vpn", "CODE", "x" + emoji.repeat(500), "d".repeat(50_000), null);

        DiagnosticsBuffer.Event event = buffer.drain().get(0);
        assertEquals(DiagnosticsBuffer.MAX_MESSAGE_LENGTH - 1, event.message.length());
        assertFalse(Character.isHighSurrogate(event.message.charAt(event.message.length() - 1)));
        assertEquals(DiagnosticsBuffer.MAX_DETAIL_LENGTH, event.detail.length());
    }

    @Test
    public void missingComponentAndCodeGetUsableDefaults() {
        DiagnosticsBuffer buffer = new DiagnosticsBuffer();

        buffer.add(null, null, null, "something broke", null, Map.of("k", "v"));

        DiagnosticsBuffer.Event event = buffer.drain().get(0);
        assertEquals("ERROR", event.severity);
        assertEquals("unknown", event.component);
        assertEquals("UNSPECIFIED", event.code);
        assertEquals("v", event.context.get("k"));
    }
}
