package com.tenix.buildora.ai;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.UI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dialog that lists every enabled model of every active provider, grouped by provider. */
public final class ModelPicker {
    private ModelPicker() {}

    public interface Callback { void onPicked(AiProvider p, AiModel m); }

    private static final class Item {
        AiProvider p; AiModel m; String header;
    }

    public static void show(final Context c, String curProvider, String curModel, final Callback cb) {
        final List<AiProvider> active = Ai.providers(c).active();
        if (active.isEmpty()) {
            UI.dialog(c).setTitle("No models yet")
                    .setMessage("Add an AI provider and choose its models first.")
                    .setPositiveButton("Add provider", (d, w) -> c.startActivity(new Intent(c, AiProvidersActivity.class)))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        final List<Item> items = new ArrayList<>();
        final boolean[] freeOnly = {false};
        final String[] q = {""};
        final String cp = curProvider == null ? "" : curProvider, cm = curModel == null ? "" : curModel;

        LinearLayout root = UI.vbox(c);
        root.setPadding(UI.dp(c, 18), UI.dp(c, 8), UI.dp(c, 18), 0);
        LinearLayout bar = UI.hbox(c);
        final TextView all = chip(c, "All", true), free = chip(c, "Free", false);
        bar.addView(all);
        bar.addView(free);
        root.addView(bar);
        EditText search = UI.input(c, "Search models", "");
        root.addView(search);
        ListView lv = new ListView(c);
        lv.setDivider(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, 380));
        lp.topMargin = UI.dp(c, 6);
        root.addView(lv, lp);

        final AlertDialog[] dlg = {null};
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public boolean isEnabled(int i) { return items.get(i).header == null; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                Item it = items.get(i);
                Context cx = parent.getContext();
                if (it.header != null) {
                    TextView h = UI.label(cx, it.header, 11.5f, true);
                    h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    h.setPadding(UI.dp(cx, 4), UI.dp(cx, 12), 0, UI.dp(cx, 4));
                    return h;
                }
                LinearLayout row = UI.hbox(cx);
                row.setPadding(UI.dp(cx, 4), UI.dp(cx, 9), UI.dp(cx, 4), UI.dp(cx, 9));
                boolean sel = it.p.id.equals(cp) && it.m.id.equals(cm);
                TextView t = UI.label(cx, it.m.id, 13.5f, false);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                if (sel) { t.setTextColor(UI.color(cx, R.attr.bAccent)); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); }
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (it.m.free) {
                    TextView tag = UI.label(cx, "FREE", 10.5f, false);
                    tag.setBackgroundResource(R.drawable.bg_tag_free);
                    tag.setPadding(UI.dp(cx, 7), UI.dp(cx, 2), UI.dp(cx, 7), UI.dp(cx, 2));
                    row.addView(tag);
                }
                if (sel) {
                    TextView ck = UI.label(cx, "✓", 14, false);
                    ck.setTextColor(UI.color(cx, R.attr.bAccent));
                    ck.setPadding(UI.dp(cx, 8), 0, 0, 0);
                    row.addView(ck);
                }
                return row;
            }
        };
        lv.setAdapter(ad);
        final Runnable rebuild = () -> {
            items.clear();
            for (AiProvider p : active) {
                List<Item> part = new ArrayList<>();
                for (AiModel m : p.models) {
                    if (!m.enabled) continue;
                    if (freeOnly[0] && !m.free) continue;
                    if (!q[0].isEmpty() && !m.id.toLowerCase(Locale.ROOT).contains(q[0]) && !p.name.toLowerCase(Locale.ROOT).contains(q[0])) continue;
                    Item it = new Item(); it.p = p; it.m = m; part.add(it);
                }
                if (part.isEmpty()) continue;
                Item h = new Item(); h.header = p.name.toUpperCase(Locale.ROOT) + "  ·  " + part.size();
                items.add(h);
                items.addAll(part);
            }
            ad.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { q[0] = s.toString().trim().toLowerCase(Locale.ROOT); rebuild.run(); }
        });
        all.setOnClickListener(v -> { freeOnly[0] = false; style(c, all, true); style(c, free, false); rebuild.run(); });
        free.setOnClickListener(v -> { freeOnly[0] = true; style(c, all, false); style(c, free, true); rebuild.run(); });
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            Item it = items.get(pos);
            if (it.header != null) return;
            if (dlg[0] != null) dlg[0].dismiss();
            cb.onPicked(it.p, it.m);
        });
        rebuild.run();
        int sel = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).m != null && items.get(i).p.id.equals(cp) && items.get(i).m.id.equals(cm)) sel = i;
        lv.setSelection(Math.max(0, sel - 2));
        dlg[0] = UI.dialog(c).setTitle("Choose model").setView(root)
                .setNeutralButton("Providers", (d, w) -> c.startActivity(new Intent(c, AiProvidersActivity.class)))
                .setNegativeButton("Close", null).create();
        dlg[0].show();
    }

    private static TextView chip(Context c, String s, boolean on) {
        TextView t = UI.label(c, s, 12.5f, false);
        t.setPadding(UI.dp(c, 14), UI.dp(c, 6), UI.dp(c, 14), UI.dp(c, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UI.dp(c, 6);
        t.setLayoutParams(lp);
        style(c, t, on);
        return t;
    }

    private static void style(Context c, TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.bg_btn_primary : R.drawable.bg_chip);
        t.setTextColor(UI.color(c, on ? R.attr.bOnAccent : R.attr.bText));
    }
}
