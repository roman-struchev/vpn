package com.vpn.android.vpn.xray;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The server builds a link's remark as "&lt;region&gt; · &lt;node hostname&gt;"
 * (SubscriptionExportService#REMARK_SEPARATOR). Only the region is shown to the
 * user, and it's also the key the region ping map is looked up by.
 */
public class RegionLabelTest {

    private static final String LINK_PREFIX =
            "vless://d3434d41-81de-4494-ad46-af99fada2968@37.27.250.158:443"
                    + "?encryption=none&security=reality&type=xhttp&sni=dl.google.com&pbk=k&sid=s#";

    @Test
    public void dropsNodeHostnameAfterSeparator() {
        VlessUri uri = VlessUri.parse(LINK_PREFIX + "Finland%2C%20Helsinki%20%C2%B7%20centos-4gb-hel1-2");
        assertEquals("Finland, Helsinki · centos-4gb-hel1-2", uri.getRemark());
        assertEquals("Finland, Helsinki", uri.getRegionLabel());
    }

    @Test
    public void keepsRemarkWithoutSeparator() {
        // A dash-only label (an older server) must not be truncated at the dash.
        assertEquals("eu-west-node1.example.com",
                VlessUri.parse(LINK_PREFIX + "eu-west-node1.example.com").getRegionLabel());
    }
}
