package com.vpn.android.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.vpn.android.vpn.xray.VlessUri;

import org.junit.Test;

import java.util.List;

/**
 * Latency is measured for the selected region only (a "ping" is a real TCP
 * connection to a node's live inbound, so measuring the whole list cost the
 * fleet a connection per region on every screen open). That makes picking the
 * right link out of the response the part worth being sure about.
 */
public class SelectedRegionPingTest {

    private static String link(String host, String remark) {
        return "vless://d3434d41-81de-4494-ad46-af99fada2968@" + host + ":443"
                + "?encryption=none&security=reality&type=xhttp&sni=dl.google.com&pbk=k&sid=s#"
                + remark.replace(" ", "%20").replace("·", "%C2%B7").replace(",", "%2C");
    }

    @Test
    public void picksANodeThatIsActuallyInTheRequestedRegion() {
        VlessUri found = ApiClient.firstLinkForRegion(
                List.of(link("203.0.113.10", "Germany, Berlin · node-a"),
                        link("203.0.113.11", "Finland, Helsinki · node-b")),
                "Finland, Helsinki");
        assertEquals("203.0.113.11", found.getHost());
    }

    @Test
    public void reportsNothingWhenTheServerFellBackToAnotherRegion() {
        // The server serves any online node when the asked-for region has none
        // (requestedRegionAvailable: false) — calling that node's latency
        // "Finland" would be a plain lie, so there is simply no number.
        assertNull(ApiClient.firstLinkForRegion(
                List.of(link("203.0.113.10", "Germany, Berlin · node-a")),
                "Finland, Helsinki"));
    }

    @Test
    public void skipsAMalformedLinkInsteadOfLosingTheMeasurement() {
        VlessUri found = ApiClient.firstLinkForRegion(
                List.of("not-a-vless-link",
                        link("203.0.113.11", "Finland, Helsinki · node-b")),
                "Finland, Helsinki");
        assertEquals("203.0.113.11", found.getHost());
    }

    @Test
    public void survivesAnEmptyOrAbsentLinkList() {
        assertNull(ApiClient.firstLinkForRegion(null, "Finland, Helsinki"));
        assertNull(ApiClient.firstLinkForRegion(List.of(), "Finland, Helsinki"));
    }
}
