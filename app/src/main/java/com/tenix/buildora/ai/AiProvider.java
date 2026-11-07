package com.tenix.buildora.ai;

import com.tenix.buildora.core.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A configured AI provider (OpenAI-compatible or Anthropic) with its models. */
public final class AiProvider {
    public static final int KIND_OPENAI = 0;
    public static final int KIND_ANTHROPIC = 1;

    public String id;
    public String name = "";
    public String baseUrl = "";
    public String apiKey = "";
    public int kind = KIND_OPENAI;
    public boolean enabled = true;
    public String preset = "custom";
    public final List<AiModel> models = new ArrayList<>();

    public static String newId() { return "p" + Long.toString(System.currentTimeMillis(), 36) + (int) (Math.random() * 1000); }

    public String base() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) b = b.substring(0, b.length() - "/chat/completions".length());
        if (b.endsWith("/messages") && kind == KIND_ANTHROPIC) b = b.substring(0, b.length() - "/messages".length());
        return b;
    }

    public AiModel find(String modelId) {
        for (AiModel m : models) if (m.id.equals(modelId)) return m;
        return null;
    }

    public int enabledCount() { int n = 0; for (AiModel m : models) if (m.enabled) n++; return n; }
    public int freeCount() { int n = 0; for (AiModel m : models) if (m.free) n++; return n; }

    public AiProvider copy(boolean withKey) {
        AiProvider p = new AiProvider();
        p.id = newId();
        p.name = name;
        p.baseUrl = baseUrl;
        p.apiKey = withKey ? apiKey : "";
        p.kind = kind;
        p.enabled = enabled;
        p.preset = preset;
        for (AiModel m : models) {
            AiModel c = new AiModel(m.id);
            c.name = m.name; c.free = m.free; c.enabled = m.enabled; c.context = m.context;
            p.models.add(c);
        }
        return p;
    }

    public Map<String, Object> toMap(boolean withKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("baseUrl", baseUrl);
        if (withKey) m.put("apiKey", apiKey);
        m.put("kind", (double) kind);
        m.put("enabled", enabled);
        m.put("preset", preset);
        List<Object> ms = new ArrayList<>();
        for (AiModel a : models) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", a.id);
            x.put("name", a.name);
            x.put("free", a.free);
            x.put("enabled", a.enabled);
            x.put("context", (double) a.context);
            ms.add(x);
        }
        m.put("models", ms);
        return m;
    }

    public static AiProvider fromMap(Map<String, Object> m) {
        AiProvider p = new AiProvider();
        String id = MiniJson.str(m.get("id"));
        p.id = id == null || id.isEmpty() ? newId() : id;
        p.name = nz(MiniJson.str(m.get("name")));
        p.baseUrl = nz(MiniJson.str(m.get("baseUrl")));
        p.apiKey = nz(MiniJson.str(m.get("apiKey")));
        p.kind = m.get("kind") instanceof Number ? ((Number) m.get("kind")).intValue() : 0;
        p.enabled = !Boolean.FALSE.equals(m.get("enabled"));
        String pr = MiniJson.str(m.get("preset"));
        p.preset = pr == null ? "custom" : pr;
        List<Object> ms = MiniJson.array(m.get("models"));
        if (ms != null) for (Object o : ms) {
            Map<String, Object> x = MiniJson.object(o);
            if (x == null) continue;
            String mid = MiniJson.str(x.get("id"));
            if (mid == null || mid.isEmpty()) continue;
            AiModel a = new AiModel(mid);
            String nm = MiniJson.str(x.get("name"));
            if (nm != null && !nm.isEmpty()) a.name = nm;
            a.free = Boolean.TRUE.equals(x.get("free"));
            a.enabled = !Boolean.FALSE.equals(x.get("enabled"));
            a.context = x.get("context") instanceof Number ? ((Number) x.get("context")).intValue() : 0;
            p.models.add(a);
        }
        return p;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
