package com.tenix.buildora.ai;

/** Ready-made provider entries. Base URLs are editable in the app. */
public final class ProviderPresets {
    private ProviderPresets() {}

    public static final class Preset {
        public final String id, name, baseUrl, keyHint, note;
        public final int kind;
        /** 0 = decide from API pricing / ":free" ids only, 1 = every model is on a free tier, 2 = Gemini flash models. */
        public final int freeMode;
        Preset(String id, String name, String baseUrl, int kind, int freeMode, String keyHint, String note) {
            this.id = id; this.name = name; this.baseUrl = baseUrl; this.kind = kind;
            this.freeMode = freeMode; this.keyHint = keyHint; this.note = note;
        }
    }

    public static final Preset[] ALL = {
            new Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", 0, 0, "sk-or-v1-…", "Many models; free ones are tagged from OpenRouter's pricing."),
            new Preset("omnirouter", "OmniRouter", "https://api.omnirouter.ai/v1", 0, 0, "API key", "Check the base URL in your OmniRouter dashboard and edit it if it differs."),
            new Preset("openai", "OpenAI (GPT)", "https://api.openai.com/v1", 0, 0, "sk-…", ""),
            new Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", 0, 2, "AIza…", "Uses Google's OpenAI-compatible endpoint. Flash models have a free tier."),
            new Preset("groq", "Groq", "https://api.groq.com/openai/v1", 0, 1, "gsk_…", "Free tier with rate limits."),
            new Preset("anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1", 1, 0, "sk-ant-…", ""),
            new Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", 0, 0, "sk-…", ""),
            new Preset("mistral", "Mistral AI", "https://api.mistral.ai/v1", 0, 0, "API key", ""),
            new Preset("xai", "xAI (Grok)", "https://api.x.ai/v1", 0, 0, "xai-…", ""),
            new Preset("together", "Together AI", "https://api.together.xyz/v1", 0, 0, "API key", ""),
            new Preset("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", 0, 0, "fw_…", ""),
            new Preset("cerebras", "Cerebras", "https://api.cerebras.ai/v1", 0, 1, "csk-…", "Free tier with rate limits."),
            new Preset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", 0, 1, "nvapi-…", "Free credits for developers."),
            new Preset("sambanova", "SambaNova", "https://api.sambanova.ai/v1", 0, 0, "API key", ""),
            new Preset("hyperbolic", "Hyperbolic", "https://api.hyperbolic.xyz/v1", 0, 0, "API key", ""),
            new Preset("siliconflow", "SiliconFlow", "https://api.siliconflow.com/v1", 0, 0, "sk-…", ""),
            new Preset("moonshot", "Moonshot (Kimi)", "https://api.moonshot.ai/v1", 0, 0, "sk-…", ""),
            new Preset("perplexity", "Perplexity", "https://api.perplexity.ai", 0, 0, "pplx-…", "No model list endpoint: type the model ids yourself."),
            new Preset("ollama", "Ollama (local)", "http://localhost:11434/v1", 0, 1, "(any text)", "Runs on your own device or PC; key is not needed."),
            new Preset("lmstudio", "LM Studio (local)", "http://localhost:1234/v1", 0, 1, "(any text)", "Runs on your own PC; key is not needed."),
            new Preset("custom", "Custom provider", "", 0, 0, "API key", "Any OpenAI-compatible endpoint."),
    };

    public static Preset byId(String id) {
        for (Preset p : ALL) if (p.id.equals(id)) return p;
        return ALL[ALL.length - 1];
    }

    public static String[] names() {
        String[] n = new String[ALL.length];
        for (int i = 0; i < n.length; i++) n[i] = ALL[i].name;
        return n;
    }

    /** True when the model should carry the FREE tag. */
    public static boolean isFree(String presetId, String modelId, boolean apiSaysFree) {
        if (apiSaysFree) return true;
        String id = modelId == null ? "" : modelId.toLowerCase();
        if (id.endsWith(":free")) return true;
        Preset p = byId(presetId);
        if (p.freeMode == 1) return true;
        if (p.freeMode == 2) return id.contains("flash");
        return false;
    }
}
