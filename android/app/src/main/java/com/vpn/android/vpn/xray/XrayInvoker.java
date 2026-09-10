package com.vpn.android.vpn.xray;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import libXray.DialerController;
import libXray.LibXray;

/**
 * Thin wrapper around the native libXray {@code invoke(String)} entrypoint
 * (API version 3 — see the "API" section of the XTLS/libXray README bundled
 * with the .aar fetched by scripts/fetch-libxray.sh). libXray's strongly
 * typed Java request/response classes (RunXrayRequest, GetXrayStateResponse,
 * ...) cannot carry the {@code method}/{@code payload} fields — gobind marks
 * them unsupported — so the raw JSON envelope is the only usable surface:
 *
 * <pre>{"apiVersion":3,"method":"runXray","payload":{"xrayJson":"..."}}</pre>
 * <pre>{"success":true,"data":{},"error":""}</pre>
 */
public final class XrayInvoker {

    private static final long API_VERSION = 3L;

    private XrayInvoker() {
    }

    public static final class InvokeException extends RuntimeException {
        public InvokeException(String message) {
            super(message);
        }
    }

    /** Registers the VpnService.protect()-backed controller used for every Go-initiated socket. */
    public static void registerDialerController(DialerController controller) {
        LibXray.registerDialerController(controller);
    }

    public static void setDns(DialerController controller, String dnsServerHostPort) {
        try {
            LibXray.setDNS(controller, dnsServerHostPort);
        } catch (Exception e) {
            throw new InvokeException("setDNS failed: " + e.getMessage());
        }
    }

    public static void resetDns() {
        LibXray.resetDNS();
    }

    public static void runXray(String xrayJson) {
        JsonObject payload = new JsonObject();
        payload.addProperty("xrayJson", xrayJson);
        invoke("runXray", payload);
    }

    public static void stopXray() {
        invoke("stopXray", new JsonObject());
    }

    public static boolean getXrayState() {
        JsonObject data = invoke("getXrayState", new JsonObject());
        return data.has("running") && data.get("running").getAsBoolean();
    }

    private static JsonObject invoke(String method, JsonObject payload) {
        JsonObject request = new JsonObject();
        request.addProperty("apiVersion", API_VERSION);
        request.addProperty("method", method);
        request.add("payload", payload);

        String responseJson = LibXray.invoke(request.toString());
        JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

        boolean success = response.has("success") && response.get("success").getAsBoolean();
        if (!success) {
            String error = response.has("error") ? response.get("error").getAsString() : "unknown libXray error";
            throw new InvokeException(method + " failed: " + error);
        }
        return response.has("data") && response.get("data").isJsonObject()
                ? response.getAsJsonObject("data")
                : new JsonObject();
    }
}
