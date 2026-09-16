package com.vpn.android.update;

import com.vpn.android.api.TestHttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * End-to-end integration test for the in-app updater: a real
 * {@link TestHttpServer} plays the part of api.github.com's
 * /repos/{owner}/{repo}/releases/latest endpoint, and AppUpdateChecker's real
 * OkHttp request/JSON-parsing/version-compare pipeline runs against it — no
 * mocking of the HTTP layer, matching GuestProfileIntegrationTest's pattern
 * for this codebase.
 */
public class AppUpdateCheckerIntegrationTest {

    private TestHttpServer server;
    private AppUpdateChecker checker;

    @Before
    public void setUp() throws IOException {
        server = new TestHttpServer();
        checker = new AppUpdateChecker(server.getBaseUrl() + "/releases/latest", new OkHttpClient());
    }

    @After
    public void tearDown() {
        if (server != null) server.close();
    }

    @Test
    public void newerReleaseWithApkAssetIsReportedAsAnUpdate() throws Exception {
        server.register("GET", "/releases/latest", request -> new TestHttpServer.Response(200, "{"
                + "\"tag_name\":\"v0.1.6\","
                + "\"assets\":["
                + "  {\"name\":\"vpn-android-0.1.6.apk\",\"browser_download_url\":\"https://example.com/vpn-android-0.1.6.apk\"},"
                + "  {\"name\":\"AuraVPN-0.1.6.dmg\",\"browser_download_url\":\"https://example.com/AuraVPN-0.1.6.dmg\"}"
                + "]}"));

        AppUpdateChecker.UpdateInfo update = checker.checkForUpdate("0.1.5");

        assertEquals("0.1.6", update.version);
        assertEquals("v0.1.6", update.tag);
        assertEquals("https://example.com/vpn-android-0.1.6.apk", update.apkUrl);
    }

    @Test
    public void sameVersionIsNotReportedAsAnUpdate() throws Exception {
        server.register("GET", "/releases/latest", request -> new TestHttpServer.Response(200, "{"
                + "\"tag_name\":\"v0.1.5\","
                + "\"assets\":[{\"name\":\"vpn-android-0.1.5.apk\",\"browser_download_url\":\"https://example.com/a.apk\"}]"
                + "}"));

        assertNull(checker.checkForUpdate("0.1.5"));
    }

    @Test
    public void olderReleaseIsNotReportedAsAnUpdate() throws Exception {
        server.register("GET", "/releases/latest", request -> new TestHttpServer.Response(200, "{"
                + "\"tag_name\":\"v0.1.4\","
                + "\"assets\":[{\"name\":\"vpn-android-0.1.4.apk\",\"browser_download_url\":\"https://example.com/a.apk\"}]"
                + "}"));

        assertNull(checker.checkForUpdate("0.1.5"));
    }

    @Test
    public void releaseWithoutAnApkAssetIsIgnored() throws Exception {
        server.register("GET", "/releases/latest", request -> new TestHttpServer.Response(200, "{"
                + "\"tag_name\":\"v0.1.6\","
                + "\"assets\":[{\"name\":\"AuraVPN-0.1.6.dmg\",\"browser_download_url\":\"https://example.com/a.dmg\"}]"
                + "}"));

        assertNull(checker.checkForUpdate("0.1.5"));
    }

    @Test
    public void serverErrorIsTreatedAsNoUpdateAvailable() throws Exception {
        server.register("GET", "/releases/latest", request -> new TestHttpServer.Response(404, "{\"message\":\"Not Found\"}"));

        assertNull(checker.checkForUpdate("0.1.5"));
    }

    @Test
    public void doubleDigitPatchVersionSortsNumericallyNotLexically() throws Exception {
        server.register("GET", "/releases/latest", request -> new TestHttpServer.Response(200, "{"
                + "\"tag_name\":\"v0.1.10\","
                + "\"assets\":[{\"name\":\"vpn-android-0.1.10.apk\",\"browser_download_url\":\"https://example.com/a.apk\"}]"
                + "}"));

        // Lexical comparison would wrongly treat "0.1.9" > "0.1.10".
        AppUpdateChecker.UpdateInfo update = checker.checkForUpdate("0.1.9");

        assertEquals("0.1.10", update.version);
    }
}
