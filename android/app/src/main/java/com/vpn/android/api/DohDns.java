package com.vpn.android.api;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * DNS-over-HTTPS resolver for the API client itself, per PLAN.md §6: "Клиент
 * не зависит от DNS провайдера (НСДИ обязательна, подмена штатна)". Bootstrapped
 * with literal Cloudflare IPs so resolving the DoH host doesn't itself depend
 * on the (untrusted) local resolver.
 */
public final class DohDns {

    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private DohDns() {
    }

    public static Dns create(OkHttpClient bootstrapClient) {
        Dns doh;
        try {
            doh = new DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url(HttpUrl.get("https://1.1.1.1/dns-query"))
                    .bootstrapDnsHosts(Arrays.asList(
                            InetAddress.getByName("1.1.1.1"),
                            InetAddress.getByName("1.0.0.1")))
                    .build();
        } catch (Exception e) {
            // Bootstrap IPs are literal; this should not happen. Fall back to the
            // platform resolver rather than making the app unusable.
            doh = Dns.SYSTEM;
        }
        Dns delegate = doh;
        // DnsOverHttps sends every hostname straight through a real DNS query,
        // with no shortcut for a literal address (see its Companion.isPrivateHost:
        // that only gates the private/public policy check, it never returns the
        // address directly) — so a bare-IP API host (like the current temporary
        // 217.216.79.46 test server, or any bare-IP backup domain) would get
        // queried as if it were a hostname and fail with UnknownHostException on
        // every real request. Resolve literal addresses locally instead.
        return hostname -> isLiteralAddress(hostname)
                ? Collections.singletonList(InetAddress.getByName(hostname))
                : delegate.lookup(hostname);
    }

    private static boolean isLiteralAddress(String host) {
        return host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches();
    }
}
