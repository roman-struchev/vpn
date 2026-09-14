package com.vpn.android.util;

import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vpn.android.api.TokenStore;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Decides whether the Russian-routing control (ConnectFragment) should be
 * shown at all — only relevant to a user actually in Russia, or a
 * Russian-speaking user abroad. Two signals, OR'd together, mirroring
 * desktop/src/main/geoLocale.ts:
 * <ol>
 *     <li>Device locale is Russian ({@link #isDeviceLocaleRussian()}) — cheap, synchronous.</li>
 *     <li>This install's public IP, at first launch (before ever connecting the VPN,
 *     so it reflects the user's real location, not a tunnel exit) resolved to Russia.
 *     One-shot: looked up once and cached forever in {@link TokenStore}, never
 *     re-checked, and never done at all if the locale signal already answered yes.</li>
 * </ol>
 */
public final class GeoLocale {

    private static final String TAG = "GeoLocale";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();

    private GeoLocale() {
    }

    public static boolean isDeviceLocaleRussian() {
        return Locale.getDefault().getLanguage().equalsIgnoreCase("ru");
    }

    /**
     * Runs the geo-IP lookup on a background thread (via {@link Async}) and caches the
     * result in {@code tokenStore} — call only when {@link #isDeviceLocaleRussian()} is
     * false and {@link TokenStore#getOriginalIpIsRussia()} is null (not yet looked up),
     * to avoid a pointless network call when the locale signal already settled it.
     */
    public static void lookupOriginalIpIsRussiaAsync(TokenStore tokenStore, Async.OnSuccess<Boolean> onResult) {
        Async.run(
                () -> lookupBlocking(),
                isRussia -> {
                    tokenStore.saveOriginalIpIsRussia(isRussia);
                    onResult.accept(isRussia);
                },
                error -> {
                    // Never block/break anything on a failed lookup — just leave it
                    // uncached so the app can retry on a future launch, and treat
                    // "unknown" as "not confirmed Russian" for this signal (the
                    // locale signal alone still applies).
                    Log.w(TAG, "Geo-IP lookup for original-IP-is-Russia failed", error);
                    onResult.accept(false);
                });
    }

    private static boolean lookupBlocking() throws IOException {
        String country = fetchCountryCode("https://ipinfo.io/json", "country");
        if (country == null) {
            country = fetchCountryCode("http://ip-api.com/json", "countryCode");
        }
        return "RU".equalsIgnoreCase(country);
    }

    private static String fetchCountryCode(String url, String field) {
        Request request = new Request.Builder().url(url).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject obj = JsonParser.parseString(response.body().string()).getAsJsonObject();
            return obj.has(field) ? obj.get(field).getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
