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

    /** Folder holding the copies of everything attached in one chat (so images stay visible and files can be imported). */
    public static File attachDir(Context c, String chatId) {
        return new File(new File(c.getApplicationContext().getFilesDir(), "ai/attach"), chatId);
    }

    /** Longest side of attached pictures (setting is stored as an index). */
    public static int imageMaxPx(Context c) {
        int[] v = {800, 1200, 1600, 2400};
        return v[Math.max(0, Math.min(3, Prefs.num(c, Prefs.AI_IMG_MAX, 2)))];
    }

    /** How many PDF pages are sent as pictures. */
    public static int pdfPages(Context c) {
        int[] v = {2, 4, 8, 12};
        return v[Math.max(0, Math.min(3, Prefs.num(c, Prefs.AI_PDF_PAGES, 1)))];
    }

    public static ChatClient.Options options(Context c, AiProvider p) {
        ChatClient.Options o = options(c);
        o.streamUsage = p != null && ChatClient.knowsStreamUsage(p);
        return o;
    }

    /** Adds a finished request to the all-time counters shown in the token box. */
    public static synchronized void addUsage(Context c, int in, int out) {
        Prefs.put(c, "ai_tok_total_in", String.valueOf(totalIn(c) + in));
        Prefs.put(c, "ai_tok_total_out", String.valueOf(totalOut(c) + out));
        Prefs.put(c, "ai_tok_requests", String.valueOf(requests(c) + 1));
    }

    public static long totalIn(Context c) { return parse(Prefs.str(c, "ai_tok_total_in", "0")); }
    public static long totalOut(Context c) { return parse(Prefs.str(c, "ai_tok_total_out", "0")); }
    public static long requests(Context c) { return parse(Prefs.str(c, "ai_tok_requests", "0")); }

    public static void resetUsage(Context c) {
        Prefs.put(c, "ai_tok_total_in", "0");
        Prefs.put(c, "ai_tok_total_out", "0");
        Prefs.put(c, "ai_tok_requests", "0");
    }

    private static long parse(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }

    /** 950 -> "950", 12400 -> "12.4k", 1250000 -> "1.25M". */
    public static String compact(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1000000) return n < 100000 ? String.format(java.util.Locale.US, "%.1fk", n / 1000.0) : (n / 1000) + "k";
        return String.format(java.util.Locale.US, "%.2fM", n / 1000000.0);
    }

    public static float fontSp(Context c) { return Prefs.flt(c, Prefs.AI_FONT, 13f); }

    public static boolean hasModel(Context c) { return providers(c).resolveDefault() != null; }

    /** "Provider · model" for a given pair, or "" when unknown. */
    public static String label(Context c, String providerId, String modelId) {
        AiProvider p = providers(c).get(providerId);
        if (p == null) return "";
        return p.name + " · " + modelId;
    }

    private static final int[] STEPS = {4, 8, 12, 20, 30};
    public static int agentSteps(android.content.Context c) {
        int i = Prefs.num(c, Prefs.AI_AGENT_STEPS, 2);
        return STEPS[Math.max(0, Math.min(STEPS.length - 1, i))];
    }
}
