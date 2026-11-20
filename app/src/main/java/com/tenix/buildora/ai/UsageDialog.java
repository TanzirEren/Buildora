package com.tenix.buildora.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.UI;

/** Details behind the token box in the chat header. */
public final class UsageDialog {
    private UsageDialog() {}

    public static void show(final Activity a, Chat chat, String providerId, String modelId, int nextEstimate, long live) {
        ScrollView sv = new ScrollView(a);
        LinearLayout box = UI.vbox(a);
        box.setPadding(UI.dp(a, 20), UI.dp(a, 6), UI.dp(a, 20), UI.dp(a, 6));
        sv.addView(box);

        long in = chat == null ? 0 : chat.tokensIn, out = chat == null ? 0 : chat.tokensOut;
        section(a, box, "This chat");
        row(a, box, "Total", Ai.compact(in + out + live) + (live > 0 ? "  (writing…)" : ""), true);
        row(a, box, "Sent to the AI", Ai.compact(in), false);
        row(a, box, "Written by the AI", Ai.compact(out), false);
        Chat.Message last = null;
        if (chat != null) for (Chat.Message m : chat.messages) if (m.role.equals("assistant") && m.tokIn + m.tokOut > 0) last = m;
        if (last != null) row(a, box, "Last reply", Ai.compact(last.tokIn) + " in · " + Ai.compact(last.tokOut) + " out", false);
        row(a, box, "Next request (estimate)", "≈ " + Ai.compact(nextEstimate), false);

        section(a, box, "All chats on this phone");
        row(a, box, "Requests", String.valueOf(Ai.requests(a)), false);
        row(a, box, "Sent", Ai.compact(Ai.totalIn(a)), false);
        row(a, box, "Written", Ai.compact(Ai.totalOut(a)), false);

        AiProvider p = Ai.providers(a).get(providerId);
        if (p != null) {
            section(a, box, "Provider");
            row(a, box, p.name, modelId == null ? "" : modelId, false);
            int n = p.keyCount();
            if (n > 1) {
                long now = System.currentTimeMillis();
                int usable = p.usableKeyCount(now);
                int active = 0, i = 0;
                for (AiProvider.KeySlot k : p.keys) { i++; if (active == 0 && k.usable(now)) active = i; }
                row(a, box, "API keys", n + " (" + usable + " ready" + (active > 0 ? ", using #" + active : "") + ")", false);
            }
        }
        TextView note = UI.label(a, "Numbers come from the provider when it reports them, otherwise they are estimated (about 3.6 characters per token).", 11, true);
        note.setPadding(0, UI.dp(a, 12), 0, 0);
        box.addView(note);

        UI.dialog(a).setTitle("Token usage").setView(sv).setPositiveButton("Close", null)
                .setNeutralButton("Reset totals", (d, w) -> { Ai.resetUsage(a); UI.toast(a, "All-chats totals reset"); }).show();
    }

    private static void section(Activity a, LinearLayout box, String t) {
        TextView s = UI.label(a, t.toUpperCase(), 10.5f, true);
        s.setLetterSpacing(0.08f);
        s.setPadding(0, UI.dp(a, 14), 0, UI.dp(a, 4));
        box.addView(s);
    }

    private static void row(Activity a, LinearLayout box, String k, String v, boolean big) {
        LinearLayout r = UI.hbox(a);
        r.setPadding(0, UI.dp(a, 5), 0, UI.dp(a, 5));
        TextView kk = UI.label(a, k, 13.5f, false);
        r.addView(kk, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vv = UI.label(a, v, big ? 17 : 13.5f, false);
        vv.setTextColor(UI.color(a, big ? R.attr.bAccent : R.attr.bText));
        vv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        r.addView(vv);
        box.addView(r);
    }
}
