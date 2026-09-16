package com.vpn.android.api;

import org.junit.Test;

import java.net.InetAddress;
import java.util.List;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;

/**
 * DnsOverHttps sends every hostname through a real DNS-over-HTTPS query, with
 * no shortcut for a literal IP address (verified against the library's own
 * bytecode: Companion.isPrivateHost only gates its private/public resolution
 * policy, it never returns the address directly). Since ApiClient's current
 * API host is the literal 217.216.79.46, that meant every real request on a
 * device failed with UnknownHostException ("Server unavailable") — Cloudflare
 * has nothing to resolve for the string "217.216.79.46" as a hostname. These
 * tests exercise DohDns's fix with zero network access: a literal address
 * must resolve locally without ever reaching the DoH delegate.
 */
public class DohDnsTest {

    @Test
    public void literalIpv4AddressResolvesWithoutADnsQuery() throws Exception {
        List<InetAddress> resolved = DohDns.create(new OkHttpClient()).lookup("217.216.79.46");

        assertEquals(1, resolved.size());
        assertEquals(InetAddress.getByName("217.216.79.46"), resolved.get(0));
    }

    @Test
    public void literalIpv6AddressResolvesWithoutADnsQuery() throws Exception {
        List<InetAddress> resolved = DohDns.create(new OkHttpClient()).lookup("::1");

        assertEquals(1, resolved.size());
        assertEquals(InetAddress.getByName("::1"), resolved.get(0));
    }
}
