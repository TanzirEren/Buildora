package com.tenix.buildora.ai;

import com.tenix.buildora.core.MiniJson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Streaming chat client for OpenAI-compatible and Anthropic endpoints. Call run() on a worker thread. */
public final class ChatClient {

    public static final class Img {
        public final String mime, base64;
        public Img(String mime, String base64) { this.mime = mime; this.base64 = base64; }
    }

    public static final class Msg {
        public final String role;      // system | user | assistant
        public final String text;
        public final List<Img> images = new ArrayList<>();
        public Msg(String role, String text) { this.role = role; this.text = text; }
    }

    public interface Callback {
        void onDelta(String text);
        void onDone(String full);
        void onError(String message, String partial);
        /** Token counts reported by the provider (0 when it did not report them). */
        default void onUsage(int promptTokens, int completionTokens) { }
        /** The client moved on to another API key because the previous one hit a limit or was rejected. */
        default void onKeySwitch(String message) { }
    }

    public static final class Options {
        public double temperature = 0.3;
        public int maxTokens = 0;       // 0 = provider default
        public boolean stream = true;
        public boolean streamUsage;      // ask OpenAI-style servers to report usage in the stream
    }

    private volatile int usageIn, usageOut;

    /** Estimated tokens for a text (about 3.6 characters per token). */
    public static int estimateTokens(String s) { return s == null ? 0 : (int) Math.ceil(s.length() / 3.6); }

    /** Hosts known to accept stream_options.include_usage. */
    public static boolean knowsStreamUsage(AiProvider p) {
        String u = p.baseUrl == null ? "" : p.baseUrl.toLowerCase(java.util.Locale.ROOT);
        String[] hosts = {"api.openai.com", "openrouter.ai", "api.groq.com", "api.deepseek.com", "api.mistral.ai", "api.x.ai",
                "api.together.xyz", "api.fireworks.ai", "api.cerebras.ai", "api.sambanova.ai", "integrate.api.nvidia.com"};
        for (String h : hosts) if (u.contains(h)) return true;
        return false;
    }

    /** How long a key should rest after an HTTP error (0 = do not rotate keys for this error). */
    static long restMillis(int code, String body, String retryAfter) {
        String b = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        switch (code) {
            case 401: return 24L * 3600000L;
            case 403: return b.contains("quota") || b.contains("limit") ? 6L * 3600000L : 24L * 3600000L;
            case 402: return 12L * 3600000L;
            case 429: {
                boolean quota = b.contains("quota") || b.contains("billing") || b.contains("credit") || b.contains("insufficient")
                        || b.contains("daily") || b.contains("per day") || b.contains("exceeded your") || b.contains("monthly");
                if (quota) return 6L * 3600000L;
                long ra = 0;
                try { if (retryAfter != null) ra = (long) (Double.parseDouble(retryAfter.trim()) * 1000); } catch (NumberFormatException ignored) { }
                return Math.min(Math.max(ra, 60000L), 6L * 3600000L);
            }
            default: return 0;
        }
    }

    static String restWhy(int code) {
        switch (code) { case 401: return "key rejected"; case 403: return "key not allowed"; case 402: return "credits used up"; default: return "limit reached"; }
    }

    private volatile boolean cancelled;
    private volatile HttpURLConnection conn;

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = conn;
        if (c != null) { try { c.disconnect(); } catch (Exception ignored) { } }
    }

    public boolean isCancelled() { return cancelled; }

    // ------------------------------------------------------------------ request bodies

    static String openAiBody(String model, List<Msg> msgs, Options o, boolean stream) {
        StringBuilder sb = new StringBuilder("{\"model\":").append(Jx.q(model)).append(",\"messages\":[");
        for (int i = 0; i < msgs.size(); i++) {
            Msg m = msgs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"role\":").append(Jx.q(m.role)).append(",\"content\":");
            if (m.images.isEmpty()) sb.append(Jx.q(m.text));
            else {
                sb.append("[{\"type\":\"text\",\"text\":").append(Jx.q(m.text)).append('}');
                for (Img im : m.images)
                    sb.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":").append(Jx.q("data:" + im.mime + ";base64," + im.base64)).append("}}");
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append("],\"temperature\":").append(o.temperature);
        if (o.maxTokens > 0) sb.append(",\"max_tokens\":").append(o.maxTokens);
        sb.append(",\"stream\":").append(stream);
        if (stream && o.streamUsage) sb.append(",\"stream_options\":{\"include_usage\":true}");
        sb.append('}');
        return sb.toString();
    }

    static String anthropicBody(String model, List<Msg> msgs, Options o, boolean stream) {
        StringBuilder system = new StringBuilder();
        StringBuilder sb = new StringBuilder("{\"model\":").append(Jx.q(model));
        List<Msg> chat = new ArrayList<>();
        for (Msg m : msgs) {
            if (m.role.equals("system")) { if (system.length() > 0) system.append("\n\n"); system.append(m.text); }
            else chat.add(m);
        }
        if (system.length() > 0) sb.append(",\"system\":").append(Jx.q(system.toString()));
        sb.append(",\"max_tokens\":").append(o.maxTokens > 0 ? o.maxTokens : 8192);
        sb.append(",\"temperature\":").append(Math.min(1.0, o.temperature));
        sb.append(",\"stream\":").append(stream).append(",\"messages\":[");
        for (int i = 0; i < chat.size(); i++) {
            Msg m = chat.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"role\":").append(Jx.q(m.role)).append(",\"content\":[");
            for (Img im : m.images)
                sb.append("{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":").append(Jx.q(im.mime))
                        .append(",\"data\":").append(Jx.q(im.base64)).append("}},");
            sb.append("{\"type\":\"text\",\"text\":").append(Jx.q(m.text.isEmpty() ? " " : m.text)).append("}]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ------------------------------------------------------------------ run

    public void run(AiProvider p, String model, List<Msg> msgs, Options o, Callback cb) {
        StringBuilder full = new StringBuilder();
        boolean anth = p.kind == AiProvider.KIND_ANTHROPIC;
        String url = p.base() + (anth ? "/messages" : "/chat/completions");
        String body = anth ? anthropicBody(model, msgs, o, o.stream) : openAiBody(model, msgs, o, o.stream);
        usageIn = 0; usageOut = 0;
        final List<AiProvider.KeySlot> tried = new ArrayList<>();
        AiProvider.KeySlot slot = pickFirst(p, tried);
        int attempt = 0;
        while (true) {
            attempt++;
            if (slot != null && !tried.contains(slot)) tried.add(slot);
            Map<String, String> h = ModelFetcher.headers(p, slot == null ? "" : slot.key);
            h.put("Content-Type", "application/json");
            h.put("Accept", o.stream ? "text/event-stream" : "application/json");
            try {
                HttpURLConnection c = Http.open(url, "POST", h, 180000);
                conn = c;
                c.setDoOutput(true);
                byte[] data = body.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) { os.write(data); }
                int code = c.getResponseCode();
                if (code != 200) {
                    String eb = Http.errorBody(c);
                    String ra = c.getHeaderField("Retry-After");
                    c.disconnect();
                    long rest = slot == null ? 0 : restMillis(code, eb, ra);
                    if (rest > 0 && full.length() == 0 && !cancelled) {
                        p.block(slot, System.currentTimeMillis() + rest, "HTTP " + code + " (" + restWhy(code) + ")");
                        AiProvider.KeySlot nx = p.nextKey(System.currentTimeMillis(), tried);
                        if (nx != null) {
                            cb.onKeySwitch("API key " + keyName(p, slot) + " " + restWhy(code) + " - switched to key " + keyName(p, nx));
                            slot = nx;
                            attempt = 0;
                            continue;
                        }
                    }
                    if ((code == 429 || code == 502 || code == 503 || code == 504) && attempt < 3 && !cancelled && full.length() == 0) {
                        try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { }
                        continue;
                    }
                    String msg = Http.niceError(code, eb);
                    if (rest > 0 && p.keyCount() > 1) msg += "\nAll " + p.keyCount() + " API keys are limited or rejected right now.";
                    cb.onError(msg, full.toString());
                    return;
                }
                if (o.stream) readSse(c, anth, full, cb);
                else {
                    String resp = Http.readAll(c.getInputStream());
                    String t = anth ? parseAnthropicFull(resp) : parseOpenAiFull(resp);
                    readUsageFull(resp, anth);
                    full.append(t);
                    if (!t.isEmpty()) cb.onDelta(t);
                }
                c.disconnect();
                if (slot != null && full.length() > 0) p.markUsed(slot);
                if (usageIn > 0 || usageOut > 0) cb.onUsage(usageIn, usageOut);
                if (cancelled) { cb.onDone(full.toString()); return; }
                if (full.length() == 0) { cb.onError("The model returned an empty reply.", ""); return; }
                cb.onDone(full.toString());
                return;
            } catch (IOException e) {
                if (cancelled) { cb.onDone(full.toString()); return; }
                if (attempt < 2 && full.length() == 0 && !(e instanceof java.net.UnknownHostException)) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { }
                    continue;
                }
                String m = e instanceof java.net.UnknownHostException ? "No internet connection (cannot reach " + p.base() + ")." : String.valueOf(e.getMessage());
                cb.onError(m, full.toString());
                return;
            } catch (Exception e) {
                cb.onError(String.valueOf(e), full.toString());
                return;
            }
        }
    }

    private static AiProvider.KeySlot pickFirst(AiProvider p, List<AiProvider.KeySlot> tried) {
        if (p.keyCount() == 0) return null;
        AiProvider.KeySlot k = p.nextKey(System.currentTimeMillis(), tried);
        return k != null ? k : p.soonestKey(tried);
    }

    private static String keyName(AiProvider p, AiProvider.KeySlot k) {
        int i = p.keys.indexOf(k) + 1;
        return "#" + i + (k.label.isEmpty() ? "" : " (" + k.label + ")");
    }

    private void readUsageFull(String resp, boolean anth) {
        try { absorbUsage(MiniJson.object(MiniJson.parse(resp)), anth); } catch (Exception ignored) { }
    }

    /** Reads token counts from an OpenAI-style "usage" object or the Anthropic message / delta usage. */
    void absorbUsage(Map<String, Object> m, boolean anth) {
        if (m == null) return;
        Map<String, Object> u = MiniJson.object(m.get("usage"));
        if (u == null) { Map<String, Object> msg = MiniJson.object(m.get("message")); if (msg != null) u = MiniJson.object(msg.get("usage")); }
        if (u == null) return;
        int in = num(u.get(anth ? "input_tokens" : "prompt_tokens")), out = num(u.get(anth ? "output_tokens" : "completion_tokens"));
        if (in > 0) usageIn = in;
        if (out > 0) usageOut = out;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    private void readSse(HttpURLConnection c, boolean anth, StringBuilder full, Callback cb) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String line;
        String event = "";
        while ((line = br.readLine()) != null) {
            if (cancelled) return;
            if (line.startsWith("event:")) { event = line.substring(6).trim(); continue; }
            if (!line.startsWith("data:")) continue;
            String d = line.substring(5).trim();
            if (d.isEmpty()) continue;
            if (d.equals("[DONE]")) return;
            try {
                Map<String, Object> m = MiniJson.object(MiniJson.parse(d));
                if (m == null) continue;
                absorbUsage(m, anth);
                String t = anth ? anthropicDelta(m, event) : openAiDelta(m);
                if (t == null) continue;
                if (t.startsWith("\u0000ERR")) throw new IOException(t.substring(4));
                if (!t.isEmpty()) { full.append(t); cb.onDelta(t); }
                if (anth && "message_stop".equals(event)) return;
            } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) { /* keep-alive or partial line */ }
        }
    }

    static String openAiDelta(Map<String, Object> m) {
        Map<String, Object> err = MiniJson.object(m.get("error"));
        if (err != null) return "\u0000ERR" + String.valueOf(err.get("message"));
        List<Object> ch = MiniJson.array(m.get("choices"));
        if (ch == null || ch.isEmpty()) return null;
        Map<String, Object> c0 = MiniJson.object(ch.get(0));
        if (c0 == null) return null;
        Map<String, Object> d = MiniJson.object(c0.get("delta"));
        if (d == null) d = MiniJson.object(c0.get("message"));
        if (d == null) return null;
        Object c = d.get("content");
        return c instanceof String ? (String) c : null;
    }

    static String anthropicDelta(Map<String, Object> m, String event) {
        String type = MiniJson.str(m.get("type"));
        if ("error".equals(type)) {
            Map<String, Object> err = MiniJson.object(m.get("error"));
            return "\u0000ERR" + (err == null ? "Provider error" : String.valueOf(err.get("message")));
        }
        if (!"content_block_delta".equals(type)) return null;
        Map<String, Object> d = MiniJson.object(m.get("delta"));
        if (d == null) return null;
        Object t = d.get("text");
        return t instanceof String ? (String) t : null;
    }

    static String parseOpenAiFull(String resp) {
        try {
            String t = openAiDelta(MiniJson.object(MiniJson.parse(resp)));
            return t == null || t.startsWith("\u0000") ? "" : t;
        } catch (Exception e) { return ""; }
    }

    static String parseAnthropicFull(String resp) {
        try {
            Map<String, Object> m = MiniJson.object(MiniJson.parse(resp));
            List<Object> content = m == null ? null : MiniJson.array(m.get("content"));
            StringBuilder sb = new StringBuilder();
            if (content != null) for (Object o : content) {
                Map<String, Object> b = MiniJson.object(o);
                if (b != null && b.get("text") instanceof String) sb.append((String) b.get("text"));
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /** Sends a tiny prompt to check the key, URL and model. Returns null when OK, otherwise an error message. */
    public static String test(AiProvider p, String model) {
        final String[] err = {null};
        final boolean[] ok = {false};
        List<Msg> l = new ArrayList<>();
        l.add(new Msg("user", "Reply with the single word: ok"));
        Options o = new Options();
        o.maxTokens = 16;
        o.stream = false;
        new ChatClient().run(p, model, l, o, new Callback() {
            @Override public void onDelta(String text) { }
            @Override public void onDone(String full) { ok[0] = true; }
            @Override public void onError(String message, String partial) { err[0] = message; }
        });
        return ok[0] ? null : (err[0] == null ? "No answer" : err[0]);
    }
}
