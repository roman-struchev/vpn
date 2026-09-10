package com.vpn.android.vpn.xray;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the {@code vless://uuid@host:port?query#remark} links produced by
 * SubscriptionExportService.buildVlessUrl on the server. Query parameter
 * names match that method's output: encryption, security, type, path, sni,
 * pbk, sid.
 */
public final class VlessUri {

    private final String uuid;
    private final String host;
    private final int port;
    private final Map<String, String> params;
    private final String remark;

    private VlessUri(String uuid, String host, int port, Map<String, String> params, String remark) {
        this.uuid = uuid;
        this.host = host;
        this.port = port;
        this.params = params;
        this.remark = remark;
    }

    public static VlessUri parse(String link) {
        if (link == null || !link.startsWith("vless://")) {
            throw new IllegalArgumentException("Not a vless:// link: " + link);
        }
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed vless link: " + link, e);
        }

        String uuid = uri.getUserInfo();
        String host = uri.getHost();
        int port = uri.getPort();
        if (uuid == null || uuid.isEmpty() || host == null || port <= 0) {
            throw new IllegalArgumentException("Incomplete vless link: " + link);
        }

        Map<String, String> params = parseQuery(uri.getRawQuery());
        String remark = decode(uri.getRawFragment());

        return new VlessUri(uuid, host, port, params, remark != null ? remark : "");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private static String decode(String value) {
        if (value == null) return null;
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    public String getUuid() {
        return uuid;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getParam(String key, String defaultValue) {
        String v = params.get(key);
        return v != null ? v : defaultValue;
    }

    public Map<String, String> getParams() {
        return Collections.unmodifiableMap(params);
    }

    public String getRemark() {
        return remark;
    }
}
