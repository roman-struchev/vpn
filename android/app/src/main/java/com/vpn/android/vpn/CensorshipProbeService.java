package com.vpn.android.vpn;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Runs the two HTTP probes behind {@link CensorshipVerdict}: a known-reachable
 * Russian host (default gosuslugi.ru, per PLAN.md §6) and a foreign test host.
 * Both probes run on the underlying network path (outside the VPN tunnel),
 * since the whole point is to tell "operator blocks this" apart from
 * "our tunnel/node is down".
 */
public class CensorshipProbeService {

    private static final String DEFAULT_WHITELIST_HOST = "https://www.gosuslugi.ru/";
    private static final String DEFAULT_FOREIGN_TEST_HOST = "https://www.google.com/generate_204";

    private final OkHttpClient client;
    private final String whitelistUrl;
    private final String foreignTestUrl;

    public CensorshipProbeService() {
        this(DEFAULT_WHITELIST_HOST, DEFAULT_FOREIGN_TEST_HOST);
    }

    public CensorshipProbeService(String whitelistUrl, String foreignTestUrl) {
        this.whitelistUrl = whitelistUrl;
        this.foreignTestUrl = foreignTestUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .build();
    }

    public CensorshipVerdict.Result probe() {
        boolean whitelistReachable = isReachable(whitelistUrl);
        boolean foreignReachable = isReachable(foreignTestUrl);
        return CensorshipVerdict.evaluate(whitelistReachable, foreignReachable);
    }

    private boolean isReachable(String url) {
        Request request = new Request.Builder().url(url).head().build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful() || response.isRedirect();
        } catch (IOException e) {
            return false;
        }
    }
}
