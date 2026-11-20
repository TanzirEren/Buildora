package com.tenix.buildora.ai;

import com.tenix.buildora.core.MiniJson;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Downloads the model list of a provider and tags free models. */
public final class ModelFetcher {
    private ModelFetcher() {}

    private static final String[] NOT_CHAT = {"embed", "whisper", "tts", "dall-e", "moderation", "rerank",
            "stable-diffusion", "imagen", "veo-", "sora", "transcribe", "speech", "flux"};

    public static Map<String, String> headers(AiProvider p) { return headers(p, p.activeKey()); }

    public static Map<String, String> headers(AiProvider p, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        if (key == null) key = "";
        if (p.kind == AiProvider.KIND_ANTHROPIC) {
            h.put("x-api-key", key);
            h.put("anthropic-version", "2023-06-01");
        } else if (!key.isEmpty()) {
            h.put("Authorization", "Bearer " + key);
        }
        if (p.baseUrl != null && p.baseUrl.contains("openrouter.ai")) {
            h.put("HTTP-Referer", "https://buildoraide.vercel.app");
            h.put("X-Title", "Buildora");
        }
        return h;
    }

    public static List<AiModel> fetch(AiProvider p) throws IOException {
        if (p.base().isEmpty()) throw new IOException("Base URL is empty.");
        String url = p.base() + "/models" + (p.kind == AiProvider.KIND_ANTHROPIC ? "?limit=1000" : "");
        HttpURLConnection c = Http.open(url, "GET", headers(p), 30000);
        int code = c.getResponseCode();
        if (code != 200) {
            String b = Http.errorBody(c);
            c.disconnect();
            throw new IOException(Http.niceError(code, b));
        }
        String body;
        try { body = Http.readAll(c.getInputStream()); } finally { c.disconnect(); }
        return parse(body, p.preset);
    }

    public static List<AiModel> parse(String body, String presetId) throws IOException {
        Object root;
        try { root = MiniJson.parse(body); } catch (Exception e) { throw new IOException("The provider did not return a model list (not JSON)."); }
        List<Object> items = null;
        Map<String, Object> m = MiniJson.object(root);
        if (m != null) {
            items = MiniJson.array(m.get("data"));
            if (items == null) items = MiniJson.array(m.get("models"));
            if (items == null) items = MiniJson.array(m.get("result"));
        } else items = MiniJson.array(root);
        if (items == null) throw new IOException("No models found in the response.");

        List<AiModel> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object o : items) {
            String id; String name = null; int ctx = 0; boolean apiFree = false;
            if (o instanceof String) id = (String) o;
            else {
                Map<String, Object> x = MiniJson.object(o);
                if (x == null) continue;
                id = MiniJson.str(x.get("id"));
                if (id == null) id = MiniJson.str(x.get("name"));
                if (id == null) id = MiniJson.str(x.get("model"));
                name = MiniJson.str(x.get("display_name"));
                if (name == null) { String nn = MiniJson.str(x.get("name")); if (nn != null && !nn.equals(id)) name = nn; }
                ctx = num(x.get("context_length"));
                if (ctx == 0) ctx = num(x.get("context_window"));
                if (ctx == 0) ctx = num(x.get("inputTokenLimit"));
                if (ctx == 0) ctx = num(x.get("max_context_length"));
                Map<String, Object> pr = MiniJson.object(x.get("pricing"));
                if (pr != null) apiFree = zero(pr.get("prompt")) && zero(pr.get("completion"));
                // Gemini native lists models that cannot generate text
                List<Object> methods = MiniJson.array(x.get("supportedGenerationMethods"));
                if (methods != null && !methods.contains("generateContent")) continue;
            }
            if (id == null || id.isEmpty()) continue;
            if (id.startsWith("models/")) id = id.substring(7);
            if (looksNonChat(id)) continue;
            if (!seen.add(id)) continue;
            AiModel a = new AiModel(id);
            if (name != null && !name.isEmpty()) a.name = name;
            a.context = ctx;
            a.free = ProviderPresets.isFree(presetId, id, apiFree);
            out.add(a);
        }
        Collections.sort(out, new Comparator<AiModel>() {
            @Override public int compare(AiModel a, AiModel b) {
                if (a.free != b.free) return a.free ? -1 : 1;
                return a.id.compareToIgnoreCase(b.id);
            }
        });
        return out;
    }

    static boolean looksNonChat(String id) {
        String l = id.toLowerCase();
        for (String s : NOT_CHAT) if (l.contains(s)) return true;
        return false;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    private static boolean zero(Object o) {
        if (o == null) return false;
        try { return Double.parseDouble(String.valueOf(o)) == 0.0; } catch (Exception e) { return false; }
    }

    /** Merges fetched models into an existing list: keeps enabled flags of known ids, adds new ones. */
    public static void merge(List<AiModel> into, List<AiModel> fetched, boolean enableNew) {
        for (AiModel f : fetched) {
            AiModel ex = null;
            for (AiModel m : into) if (m.id.equals(f.id)) { ex = m; break; }
            if (ex == null) { f.enabled = enableNew; into.add(f); }
            else { ex.free = f.free; ex.context = f.context; if (f.name != null && !f.name.equals(f.id)) ex.name = f.name; }
        }
    }
}
