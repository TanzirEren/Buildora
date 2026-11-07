package com.tenix.buildora.ai;

import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.MiniJson;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Providers + default model, kept in one JSON file inside the app's private storage. */
public final class ProviderStore {
    private final File file;
    private final List<AiProvider> providers = new ArrayList<>();
    private String defProvider = "";
    private String defModel = "";

    public ProviderStore(File file) {
        this.file = file;
        load();
    }

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        providers.clear();
        try {
            if (!file.isFile()) return;
            Map<String, Object> root = MiniJson.object(MiniJson.parse(FileUtil.readText(file)));
            if (root == null) return;
            String dp = MiniJson.str(root.get("defaultProvider"));
            String dm = MiniJson.str(root.get("defaultModel"));
            defProvider = dp == null ? "" : dp;
            defModel = dm == null ? "" : dm;
            List<Object> arr = MiniJson.array(root.get("providers"));
            if (arr != null) for (Object o : arr) {
                Map<String, Object> m = MiniJson.object(o);
                if (m != null) providers.add(AiProvider.fromMap(m));
            }
        } catch (Exception ignored) { }
    }

    public synchronized void save() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("defaultProvider", defProvider);
            root.put("defaultModel", defModel);
            List<Object> arr = new ArrayList<>();
            for (AiProvider p : providers) arr.add(p.toMap(true));
            root.put("providers", arr);
            FileUtil.writeText(file, Jx.write(root));
        } catch (Exception ignored) { }
    }

    public synchronized List<AiProvider> all() { return new ArrayList<>(providers); }

    public synchronized AiProvider get(String id) {
        for (AiProvider p : providers) if (p.id.equals(id)) return p;
        return null;
    }

    public synchronized void add(AiProvider p) {
        providers.add(p);
        if (defProvider.isEmpty()) ensureDefault();
        save();
    }

    public synchronized void remove(String id) {
        for (int i = 0; i < providers.size(); i++) if (providers.get(i).id.equals(id)) { providers.remove(i); break; }
        if (defProvider.equals(id)) { defProvider = ""; defModel = ""; ensureDefault(); }
        save();
    }

    public synchronized void replace(AiProvider p) {
        for (int i = 0; i < providers.size(); i++) if (providers.get(i).id.equals(p.id)) { providers.set(i, p); save(); return; }
        providers.add(p);
        save();
    }

    public synchronized int count() { return providers.size(); }

    /** Providers that are switched on and have at least one enabled model. */
    public synchronized List<AiProvider> active() {
        List<AiProvider> out = new ArrayList<>();
        for (AiProvider p : providers) if (p.enabled && p.enabledCount() > 0) out.add(p);
        return out;
    }

    public synchronized void setDefault(String providerId, String modelId) {
        defProvider = providerId == null ? "" : providerId;
        defModel = modelId == null ? "" : modelId;
        save();
    }

    public synchronized String defaultProviderId() { return defProvider; }
    public synchronized String defaultModelId() { return defModel; }

    /** The selected default if still usable, otherwise the first enabled model of the first active provider. */
    public synchronized String[] resolveDefault() {
        AiProvider p = get(defProvider);
        if (p != null && p.enabled) {
            AiModel m = p.find(defModel);
            if (m != null && m.enabled) return new String[]{p.id, m.id};
        }
        for (AiProvider q : providers) {
            if (!q.enabled) continue;
            for (AiModel m : q.models) if (m.enabled) return new String[]{q.id, m.id};
        }
        return null;
    }

    private void ensureDefault() {
        String[] d = resolveDefault();
        if (d != null) { defProvider = d[0]; defModel = d[1]; }
    }

    // ----- export / import (keys are never exported)

    public synchronized String exportJson() {
        List<Object> arr = new ArrayList<>();
        for (AiProvider p : providers) arr.add(p.toMap(false));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("buildora_providers", arr);
        return Jx.write(root);
    }

    /** Imports providers from exportJson text. Returns the number added. */
    public synchronized int importJson(String text) {
        int n = 0;
        try {
            Map<String, Object> root = MiniJson.object(MiniJson.parse(text.trim()));
            List<Object> arr = root == null ? null : MiniJson.array(root.get("buildora_providers"));
            if (arr == null) return 0;
            for (Object o : arr) {
                Map<String, Object> m = MiniJson.object(o);
                if (m == null) continue;
                AiProvider p = AiProvider.fromMap(m);
                p.id = AiProvider.newId();
                providers.add(p);
                n++;
            }
            if (n > 0) { ensureDefault(); save(); }
        } catch (Exception ignored) { }
        return n;
    }
}
