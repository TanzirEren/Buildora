package com.tenix.buildora.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** Tiny HTTP helper for the AI client (no Android dependency). */
final class Http {
    private Http() {}

    static HttpURLConnection open(String url, String method, Map<String, String> headers, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "Buildora/2.1 (Android)");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        return c;
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }

    static String errorBody(HttpURLConnection c) {
        try { return readAll(c.getErrorStream()); } catch (Exception e) { return ""; }
    }

    /** Pulls "error.message" (or message / detail) out of a JSON error body, else a short slice of the text. */
    static String niceError(int code, String body) {
        String msg = null;
        try {
            Object o = com.tenix.buildora.core.MiniJson.parse(body);
            Map<String, Object> m = com.tenix.buildora.core.MiniJson.object(o);
            if (m != null) {
                Object e = m.get("error");
                if (e instanceof Map) {
                    Object mm = ((Map<?, ?>) e).get("message");
                    if (mm instanceof String) msg = (String) mm;
                } else if (e instanceof String) msg = (String) e;
                if (msg == null && m.get("message") instanceof String) msg = (String) m.get("message");
                if (msg == null && m.get("detail") instanceof String) msg = (String) m.get("detail");
            }
        } catch (Exception ignored) { }
        if (msg == null) {
            msg = body == null ? "" : body.trim();
            if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
        }
        String hint;
        switch (code) {
            case 401: hint = "Invalid or missing API key."; break;
            case 402: hint = "Payment or credits required."; break;
            case 403: hint = "Access denied for this key or model."; break;
            case 404: hint = "Not found - check the base URL and model id."; break;
            case 429: hint = "Rate limit reached - wait a moment or switch model."; break;
            default: hint = code >= 500 ? "The provider had a server problem." : "";
        }
        return "HTTP " + code + (msg.isEmpty() ? "" : ": " + msg) + (hint.isEmpty() ? "" : "\n" + hint);
    }
}
