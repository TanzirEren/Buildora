package com.tenix.buildora.ai;

import android.content.Context;

import com.tenix.buildora.core.Prefs;

import java.io.File;

/** Process-wide access to the AI stores and settings. */
public final class Ai {
    private Ai() {}

    private static ProviderStore providers;
    private static ChatStore chats;

    public static synchronized ProviderStore providers(Context c) {
        if (providers == null) providers = new ProviderStore(new File(c.getApplicationContext().getFilesDir(), "ai/providers.json"));
        return providers;
    }

    public static synchronized ChatStore chats(Context c) {
        if (chats == null) chats = new ChatStore(new File(c.getApplicationContext().getFilesDir(), "ai/chats"));
        return chats;
    }

    public static File backups(File projectRoot) { return new File(new File(projectRoot, ".buildora"), "ai_backup"); }

    public static ChatClient.Options options(Context c) {
        ChatClient.Options o = new ChatClient.Options();
        o.temperature = Prefs.num(c, Prefs.AI_TEMP, 30) / 100.0;
        o.maxTokens = Prefs.num(c, Prefs.AI_MAXTOK, 0);
        o.stream = Prefs.bool(c, Prefs.AI_STREAM, true);
        return o;
    }

    /** Characters of project files sent with a message. */
    public static int contextBudget(Context c) {
        switch (Prefs.num(c, Prefs.AI_CTX, 1)) {
            case 0: return 12000;
            case 2: return 120000;
            default: return 40000;
        }
    }

    public static float fontSp(Context c) { return Prefs.flt(c, Prefs.AI_FONT, 13f); }

    public static boolean hasModel(Context c) { return providers(c).resolveDefault() != null; }

    /** "Provider · model" for a given pair, or "" when unknown. */
    public static String label(Context c, String providerId, String modelId) {
        AiProvider p = providers(c).get(providerId);
        if (p == null) return "";
        return p.name + " · " + modelId;
    }
}
