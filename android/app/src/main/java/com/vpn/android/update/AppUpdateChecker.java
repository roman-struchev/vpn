package com.vpn.android.update;

import com.google.gson.Gson;
import com.vpn.android.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Polls this repo's GitHub Releases for a newer Android build than the one
 * currently installed. This app isn't distributed via the Play Store, so
 * .github/workflows/release.yml is the only place new APKs are published:
 * each `v<version>` tag push creates a release there with a
 * `vpn-android-<version>.apk` asset attached.
 */
public class AppUpdateChecker {

    private static final String DEFAULT_LATEST_RELEASE_URL =
            "https://api.github.com/repos/" + BuildConfig.GITHUB_REPO + "/releases/latest";

    private final String latestReleaseUrl;
    private final OkHttpClient http;
    private final Gson gson = new Gson();

    public AppUpdateChecker() {
        this(DEFAULT_LATEST_RELEASE_URL,
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build());
    }

    /** Test seam: point at a fake server instead of the real GitHub API. */
    AppUpdateChecker(String latestReleaseUrl, OkHttpClient http) {
        this.latestReleaseUrl = latestReleaseUrl;
        this.http = http;
    }

    public static class UpdateInfo {
        public final String version;
        public final String tag;
        public final String apkUrl;

        UpdateInfo(String version, String tag, String apkUrl) {
            this.version = version;
            this.tag = tag;
            this.apkUrl = apkUrl;
        }
    }

    /**
     * @return update info if the latest published release is newer than the
     * currently running {@link BuildConfig#VERSION_NAME} and has an .apk
     * asset attached, otherwise null. Does blocking network I/O — call off
     * the main thread.
     */
    public UpdateInfo checkForUpdate() throws IOException {
        return checkForUpdate(BuildConfig.VERSION_NAME);
    }

    /** @param currentVersion overridable for tests; production callers use {@link #checkForUpdate()}. */
    UpdateInfo checkForUpdate(String currentVersion) throws IOException {
        Request request = new Request.Builder()
                .url(latestReleaseUrl)
                .header("Accept", "application/vnd.github+json")
                .build();

        GithubRelease release;
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            release = gson.fromJson(response.body().string(), GithubRelease.class);
        }
        if (release == null || release.tag_name == null || release.assets == null) return null;

        String latestVersion = release.tag_name.startsWith("v")
                ? release.tag_name.substring(1) : release.tag_name;
        if (!isNewer(latestVersion, currentVersion)) return null;

        String apkUrl = null;
        for (GithubRelease.Asset asset : release.assets) {
            if (asset.name != null && asset.name.endsWith(".apk")) {
                apkUrl = asset.browser_download_url;
                break;
            }
        }
        if (apkUrl == null) return null;

        return new UpdateInfo(latestVersion, release.tag_name, apkUrl);
    }

    /**
     * Dotted-integer version comparison ("0.1.10" > "0.1.9"). A non-numeric
     * segment (e.g. a "-mvp" local dev suffix) sorts as older than any real
     * numeric segment in the same position.
     */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("[.\\-]");
        String[] b = current.split("[.\\-]");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = partAt(a, i);
            int y = partAt(b, i);
            if (x != y) return x > y;
        }
        return false;
    }

    private static int partAt(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
