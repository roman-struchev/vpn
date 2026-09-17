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

    /**
     * Best-effort "Country" / "Country, City" label for wherever this
     * device's current public IP geolocates to — auto-fills a p2p relay
     * node's own declared location (docs/research/P2P_RELAY_FEASIBILITY.md
     * §8.4), the same way scripts/install-node.sh auto-detects a regular
     * VPS node's region from its public IP at install time. Mirrors
     * desktop/src/main/geoLocale.ts#detectNodeRegion exactly, including the
     * provider order: ip-api.com is tried FIRST (unlike
     * {@link #lookupBlocking()} above) because its response includes a full
     * country name directly, unlike ipinfo.io's bare 2-letter code — this
     * avoids porting install-node.sh's ~250-entry bash country-code table
     * into Java. ipinfo.io is still a fallback, using its raw code as a
     * last-resort label when that's all that's available.
     *
     * Blocking (matches the rest of this class's style) — callers on
     * Android's main thread must run this off it themselves; P2pRelayAgent
     * already does all its network I/O on a background executor.
     */
    public static String detectNodeRegion() {
        String[] viaIpApi = fetchRegion("http://ip-api.com/json", "country", "city");
        if (viaIpApi != null) return formatRegion(viaIpApi[0], viaIpApi[1]);

        String[] viaIpinfo = fetchRegion("https://ipinfo.io/json", "country", "city");
        if (viaIpinfo != null) return formatRegion(viaIpinfo[0], viaIpinfo[1]);

        return "default";
    }

    // Package-visible (not private) specifically so GeoLocaleTest can cover
    // this pure formatting logic directly — the network-calling methods
    // above it aren't unit-tested, matching this class's own pre-existing
    // untested lookupBlocking()/fetchCountryCode() (no MockWebServer or
    // similar HTTP-mocking dependency exists in this project yet).
    static String formatRegion(String country, String city) {
        if (country == null || country.isBlank()) return "default";
        country = countryNameForCode(country);
        return (city != null && !city.isBlank()) ? country + ", " + city : country;
    }

    /**
     * ipinfo.io only returns a 2-letter ISO code ("ME"), and on Android it is
     * effectively the only provider that ever answers: ip-api.com's free tier
     * is plain HTTP, which this app's network security config blocks. Region
     * grouping is an exact string match, so a raw code split the same
     * location into two regions ("ME, Podgorica" from Android vs
     * "Montenegro, Podgorica" from desktop/install-node.sh) — expanded to the
     * English country name here, the same label the other two produce.
     */
    // Where Android's ICU (CLDR) name differs from what ip-api.com and
    // install-node.sh's country table produce for places a node plausibly sits.
    private static final java.util.Map<String, String> COUNTRY_NAME_OVERRIDES = java.util.Map.of(
            "TR", "Turkey", "HK", "Hong Kong", "MO", "Macao", "MM", "Myanmar",
            "PS", "Palestine", "CI", "Ivory Coast", "CD", "DR Congo", "CG", "Congo");

    /** Fixes a region label persisted by an older build as "ME, Podgorica" (see countryNameForCode). */
    public static String normalizeRegion(String region) {
        if (region == null || region.isBlank() || "default".equals(region)) return region;
        int comma = region.indexOf(", ");
        return comma < 0
                ? countryNameForCode(region)
                : countryNameForCode(region.substring(0, comma)) + region.substring(comma);
    }

    static String countryNameForCode(String country) {
        if (country.length() != 2) return country;
        String override = COUNTRY_NAME_OVERRIDES.get(country.toUpperCase(Locale.ROOT));
        if (override != null) return override;
        String name = new Locale("", country.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH);
        return (name == null || name.isBlank() || name.equalsIgnoreCase(country)) ? country : name;
    }

    /** @return {country, city} (either may be null), or null if the lookup itself failed or had no usable country. */
    private static String[] fetchRegion(String url, String countryField, String cityField) {
        Request request = new Request.Builder().url(url).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject obj = JsonParser.parseString(response.body().string()).getAsJsonObject();
            String country = obj.has(countryField) && !obj.get(countryField).isJsonNull()
                    ? obj.get(countryField).getAsString() : null;
            String city = obj.has(cityField) && !obj.get(cityField).isJsonNull()
                    ? obj.get(cityField).getAsString() : null;
            if (country == null || country.isBlank()) return null;
            return new String[]{country, city};
        } catch (Exception e) {
            return null;
        }
    }
}
