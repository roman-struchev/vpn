package com.vpn.android.diagnostics;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.vpn.android.BuildConfig;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ships the failures this app hits to the server (POST
 * /api/v1/client/diagnostics), so problems on real devices are visible
 * without a user having to notice, care, and write in about them.
 *
 * Mirrors the node agent's and desktop's collectors: a bounded buffer that
 * folds repeats (see {@link DiagnosticsBuffer}), drained on a timer off the
 * main thread. Three rules this must never break:
 *
 *  - it must not throw into the code that is already failing;
 *  - it must not block anything the user is waiting on (hence the executor);
 *  - it must not become a flood, which is what the buffer and the server's
 *    own per-source valve are for.
 *
 * What it does NOT send: anything identifying the person. The reporter id is
 * this install's device UUID (the same one the app already authenticates
 * with), the payload is the failure's own text, and the server masks
 * addresses, e-mails and identifiers out of it again on arrival.
 */
public final class DiagnosticsReporter {

    private static final String TAG = "Diagnostics";
    private static final long FLUSH_INTERVAL_SECONDS = 120;

    private static volatile DiagnosticsReporter instance;

    private final DiagnosticsBuffer buffer = new DiagnosticsBuffer();
    private final ApiClient apiClient;
    private final String reporterId;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "diagnostics-reporter");
                t.setDaemon(true);
                return t;
            });

    private DiagnosticsReporter(Context context) {
        TokenStore tokenStore = new TokenStore(context.getApplicationContext());
        this.apiClient = new ApiClient(tokenStore);
        this.reporterId = tokenStore.getOrCreateDeviceUuid();
    }

    /** Call once, from Application#onCreate. Safe to call again; later calls no-op. */
    public static synchronized void init(Context context) {
        if (instance != null) {
            return;
        }
        try {
            DiagnosticsReporter reporter = new DiagnosticsReporter(context);
            instance = reporter;
            reporter.executor.scheduleWithFixedDelay(
                    reporter::flush, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
            installCrashHandler();
        } catch (Throwable t) {
            // An app that cannot set up error reporting must still start.
            Log.w(TAG, "Could not initialise diagnostics reporting", t);
        }
    }

    /**
     * Records a failure. Never throws — every call site is, by definition,
     * already handling something that went wrong.
     */
    public static void error(String component, String code, String message, Throwable error) {
        report("ERROR", component, code, message, error, null);
    }

    public static void error(String component, String code, String message, Throwable error, Map<String, String> context) {
        report("ERROR", component, code, message, error, context);
    }

    public static void warn(String component, String code, String message) {
        report("WARN", component, code, message, null, null);
    }

    private static void report(String severity, String component, String code,
                               String message, Throwable error, Map<String, String> context) {
        DiagnosticsReporter reporter = instance;
        if (reporter == null) {
            return;
        }
        try {
            Map<String, String> fullContext = new LinkedHashMap<>();
            fullContext.put("android", String.valueOf(Build.VERSION.SDK_INT));
            fullContext.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            if (context != null) {
                fullContext.putAll(context);
            }
            reporter.buffer.add(severity, component, code, message, stackOf(error), fullContext);
        } catch (Throwable t) {
            Log.w(TAG, "Could not record a diagnostic report", t);
        }
    }

    /** Sends immediately rather than waiting for the timer — used for a crash. */
    public static void flushNow() {
        DiagnosticsReporter reporter = instance;
        if (reporter != null) {
            reporter.flush();
        }
    }

    private void flush() {
        try {
            List<DiagnosticsBuffer.Event> events = buffer.drain();
            if (events.isEmpty()) {
                return;
            }
            apiClient.submitDiagnostics("ANDROID", BuildConfig.VERSION_NAME, reporterId, events);
        } catch (Throwable t) {
            // Being unable to reach the server is itself one of the things
            // worth reporting; there is nothing to do about it here, and
            // re-queueing would let an offline spell grow without bound.
            Log.d(TAG, "Could not ship diagnostics: " + t);
        }
    }

    /**
     * Catches what no explicit call site can: a crash. Chains to whatever
     * handler was already installed so the app still dies the way Android
     * expects it to — this only gets the report out first.
     */
    private static void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                error("app", "UNCAUGHT_EXCEPTION",
                        "Uncaught exception on thread " + thread.getName(), throwable);
                flushNow();
            } catch (Throwable ignored) {
                // Never get in the way of the crash itself.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static String stackOf(Throwable error) {
        if (error == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(error.toString());
        StackTraceElement[] frames = error.getStackTrace();
        // A handful of frames is enough to place a failure; the whole trace
        // would be clipped by the server anyway.
        for (int i = 0; i < Math.min(frames.length, 12); i++) {
            sb.append("\n\tat ").append(frames[i]);
        }
        if (error.getCause() != null && error.getCause() != error) {
            sb.append("\nCaused by: ").append(error.getCause());
        }
        return sb.toString();
    }
}
