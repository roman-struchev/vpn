package com.vpn.android.api;

import java.net.InetAddress;
import java.util.Arrays;

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

    private DohDns() {
    }

    public static Dns create(OkHttpClient bootstrapClient) {
        try {
            return new DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url(HttpUrl.get("https://1.1.1.1/dns-query"))
                    .bootstrapDnsHosts(Arrays.asList(
                            InetAddress.getByName("1.1.1.1"),
                            InetAddress.getByName("1.0.0.1")))
                    .build();
        } catch (Exception e) {
            // Bootstrap IPs are literal; this should not happen. Fall back to the
            // platform resolver rather than making the app unusable.
            return Dns.SYSTEM;
        }
    }
}
