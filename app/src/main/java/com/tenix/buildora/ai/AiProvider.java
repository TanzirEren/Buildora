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
    /** API keys in priority order. When one hits its limit the next usable key is used automatically. */
    public final List<KeySlot> keys = new ArrayList<>();
    /** Set by the store so key state (limits, invalid keys) is saved when it changes. */
    public transient Runnable onChange;
    public int kind = KIND_OPENAI;
    public boolean enabled = true;
    public String preset = "custom";
    public final List<AiModel> models = new ArrayList<>();

    /** One API key and its health. */
    public static final class KeySlot {
        public String key = "";
        public String label = "";
        public boolean enabled = true;
        public long blockedUntil;     // epoch ms, 0 = usable
        public String lastError = "";
        public long uses;

        public KeySlot() {}
        public KeySlot(String key) { this.key = key == null ? "" : key.trim(); }

        public boolean usable(long now) { return enabled && !key.isEmpty() && blockedUntil <= now; }

        public String masked() {
            if (key.length() <= 8) return key.isEmpty() ? "(empty)" : "••••";
            return key.substring(0, 4) + "••••" + key.substring(key.length() - 4);
        }
    }

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

    // ----- keys

    public synchronized int keyCount() { int n = 0; for (KeySlot k : keys) if (!k.key.isEmpty()) n++; return n; }

    public synchronized int usableKeyCount(long now) { int n = 0; for (KeySlot k : keys) if (k.usable(now)) n++; return n; }

    /** The first key that can be used now and is not in {@code tried}. */
    public synchronized KeySlot nextKey(long now, java.util.Collection<KeySlot> tried) {
        for (KeySlot k : keys) if (k.usable(now) && (tried == null || !tried.contains(k))) return k;
        return null;
    }

    /** Best key to try even when all are limited: the one that frees up soonest. */
    public synchronized KeySlot soonestKey(java.util.Collection<KeySlot> tried) {
        KeySlot best = null;
        for (KeySlot k : keys) {
            if (!k.enabled || k.key.isEmpty() || (tried != null && tried.contains(k))) continue;
            if (best == null || k.blockedUntil < best.blockedUntil) best = k;
        }
        return best;
    }

    /** The key used for plain calls (model list, test): the first usable one, else the first one set. */
    public synchronized String activeKey() {
        KeySlot k = nextKey(System.currentTimeMillis(), null);
        if (k == null) k = soonestKey(null);
        return k == null ? "" : k.key;
    }

    public synchronized void setKeys(List<KeySlot> list) {
        keys.clear();
        for (KeySlot k : list) if (k != null && !k.key.trim().isEmpty()) { k.key = k.key.trim(); keys.add(k); }
    }

    public synchronized void block(KeySlot k, long until, String error) {
        k.blockedUntil = until;
        k.lastError = error == null ? "" : error;
        fire();
    }

    public synchronized void markUsed(KeySlot k) {
        boolean changed = k.blockedUntil != 0 || !k.lastError.isEmpty();
        k.uses++;
        k.blockedUntil = 0;
        k.lastError = "";
        if (changed) fire();
    }

    public synchronized void resetLimits() {
        for (KeySlot k : keys) { k.blockedUntil = 0; k.lastError = ""; }
        fire();
    }

    private void fire() { Runnable r = onChange; if (r != null) { try { r.run(); } catch (RuntimeException ignored) { } } }

    public int enabledCount() { int n = 0; for (AiModel m : models) if (m.enabled) n++; return n; }
    public int freeCount() { int n = 0; for (AiModel m : models) if (m.free) n++; return n; }

    public AiProvider copy(boolean withKey) {
        AiProvider p = new AiProvider();
        p.id = newId();
        p.name = name;
        p.baseUrl = baseUrl;
        if (withKey) for (KeySlot k : keys) {
            KeySlot c = new KeySlot(k.key);
            c.label = k.label; c.enabled = k.enabled; c.blockedUntil = k.blockedUntil; c.lastError = k.lastError; c.uses = k.uses;
            p.keys.add(c);
        }
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
        if (withKey) {
            m.put("apiKey", keys.isEmpty() ? "" : keys.get(0).key);
            List<Object> ks = new ArrayList<>();
            for (KeySlot k : keys) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("key", k.key);
                if (!k.label.isEmpty()) x.put("label", k.label);
                x.put("enabled", k.enabled);
                if (k.blockedUntil > 0) x.put("blockedUntil", (double) k.blockedUntil);
                if (!k.lastError.isEmpty()) x.put("error", k.lastError);
                if (k.uses > 0) x.put("uses", (double) k.uses);
                ks.add(x);
            }
            m.put("keys", ks);
        }
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
        List<Object> ks = MiniJson.array(m.get("keys"));
        if (ks != null) for (Object o : ks) {
            Map<String, Object> x = MiniJson.object(o);
            if (x == null) continue;
            KeySlot k = new KeySlot(nz(MiniJson.str(x.get("key"))));
            if (k.key.isEmpty()) continue;
            k.label = nz(MiniJson.str(x.get("label")));
            k.enabled = !Boolean.FALSE.equals(x.get("enabled"));
            k.blockedUntil = x.get("blockedUntil") instanceof Number ? ((Number) x.get("blockedUntil")).longValue() : 0;
            k.lastError = nz(MiniJson.str(x.get("error")));
            k.uses = x.get("uses") instanceof Number ? ((Number) x.get("uses")).longValue() : 0;
            p.keys.add(k);
        }
        if (p.keys.isEmpty()) {            // files written before v2.1.3 have a single "apiKey"
            String old = nz(MiniJson.str(m.get("apiKey")));
            if (!old.isEmpty()) p.keys.add(new KeySlot(old));
        }
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
